package com.company.cloud.transfer.service;

import lombok.extern.slf4j.Slf4j;
import com.company.cloud.common.audit.AuditActions;
import com.company.cloud.common.audit.AuditEvent;
import com.company.cloud.common.audit.AuditService;
import com.company.cloud.common.result.BizException;
import com.company.cloud.common.result.ErrorCode;
import com.company.cloud.transfer.entity.FileEntity;
import com.company.cloud.transfer.entity.UploadSessionEntity;
import com.company.cloud.transfer.repository.FileEntityRepository;
import com.company.cloud.transfer.repository.UploadSessionEntityRepository;
import com.company.cloud.transfer.repository.UserQuotaRepository;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Part;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 文件传输核心 Service——后端 B 组主干。
 *
 * 覆盖任务书 CS-DOC-03 全部 P0 需求：
 *  R-B01 init 三步协议首步 / R-B02 秒传 / R-B03+R-B04 分片上传与断点续传 /
 *  R-B05 complete / R-B06 配额防超卖 / R-B07 下载签发 / R-B08 24h 会话清理 /
 *  R-B09 大小与扩展名限制 / R-B10 引用计数维护（供 C 组）。
 *
 * 内容寻址：对象 key = objects/<sha256>，同内容全站同 key 天然去重；
 * 引用计数归 files 表 ref_count（对齐 spec），彻底删除时按「是否仍有存活文件记录」判断是否物理删对象。
 *
 * 说明：complete 的「MinIO 合并」与「DB 落库」跨系统无法用 DB 事务原子化，
 * 靠「幂等重试 + 脏对象对账清理」兜底，与任务书验收「重启可续、无脏数据」一致。
 */
@Service
@Slf4j
public class UploadService {

    public static final String STATUS_UPLOADING = "uploading";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_ABORTED = "aborted";

    private final MinioStorageService minio;
    private final FileEntityRepository fileRepo;
    private final UploadSessionEntityRepository sessionRepo;
    private final UserQuotaRepository quotaRepo;
    private final AuditService auditService;

    private final long maxFileSize;               // 默认 10GB
    private final Set<String> allowedExtensions;  // 扩展名白名单
    private final int presignExpirySeconds;       // 默认 300 秒
    private final int sessionTtlHours;            // 默认 24 小时

    public UploadService(MinioStorageService minio,
                         FileEntityRepository fileRepo,
                         UploadSessionEntityRepository sessionRepo,
                         UserQuotaRepository quotaRepo,
                         AuditService auditService,
                         @Value("${upload.max-file-size:10737418240}") long maxFileSize,
                         @Value("${upload.allowed-extensions:}") String allowedExt,
                         @Value("${download.presign-expiry-seconds:300}") int presignExpirySeconds,
                         @Value("${upload.session-ttl-hours:24}") int sessionTtlHours) {
        this.minio = minio;
        this.fileRepo = fileRepo;
        this.sessionRepo = sessionRepo;
        this.quotaRepo = quotaRepo;
        this.auditService = auditService;
        this.maxFileSize = maxFileSize;
        this.allowedExtensions = parseExtensions(allowedExt);
        this.presignExpirySeconds = presignExpirySeconds;
        this.sessionTtlHours = sessionTtlHours;
    }

    // ============ R-B01/R-B02/R-B06：init（含秒传分支） ============
    @Transactional
    public InitResult init(Long userId, String name, long size, Long parentId, String sha256) {
        validateSize(size);
        validateExtension(name);
        validateSha256(sha256);

        // ── 超卖闸门必须放在**最前面**（2026-11-02 修正）──────────────
        // 原来只在普通上传分支调 guardEnterprisePool()，结果**秒传分支**（L97 附近）
        // 在它之前就 return 了 —— 表现为"企业已超额、前端横幅也提示了，
        // 但传一个同 sha256 的文件照样成功"（真实踩过）。
        // 秒传、普通上传、断点续传都不该绕过这条平台级限制。
        ensureNotOversold(userId);

        long targetParent = parentId == null ? 0L : parentId;

        // 秒传分支：sha256 命中存活 files 记录 → 零字节传输，直接复用对象
        Optional<FileEntity> hit = fileRepo.findFirstByEnterpriseIdAndSha256AndDeletedAtIsNull(currentEnterpriseId(), sha256);
        if (hit.isPresent()) {
            // 预扣配额（条件 UPDATE 防超卖）
            if (!quotaRepo.tryReserve(userId, size)) {
                throw new BizException(ErrorCode.STORAGE_QUOTA_EXCEEDED);
            }
            FileEntity f = fileRepo.save(FileEntity.builder()
                    .ownerId(userId).enterpriseId(currentEnterpriseId())
                    .parentId(targetParent)
                    .name(resolveUniqueName(userId, targetParent, name))
                    .isDir(false).size(size).sha256(sha256).refCount(1)
                    .build());
            // 秒传也落一条传输任务记录（status 直接 done），让传输任务列表能查到
            sessionRepo.save(UploadSessionEntity.builder()
                    .userId(userId).uploadId("INSTANT").targetParent(targetParent)
                    .name(f.getName()).sha256(sha256).sizeBytes(size)
                    .chunkSize(minio.partSize()).status(STATUS_DONE)
                    .fileId(f.getId())
                    .expiresAt(OffsetDateTime.now().plusHours(sessionTtlHours))
                    .build());
            auditService.record(new AuditEvent(
                    userId, AuditActions.UPLOAD, String.valueOf(f.getId()), null,
                    Map.of("name", f.getName(), "size", size)));
            return InitResult.done(f.getId());
        }

        // 幂等续传：同 sha256 存在未过期、未完成的会话 → 复用（前端丢 sessionId 后可用 sha256 找回继续传）
        Optional<UploadSessionEntity> existing = sessionRepo
                .findFirstByUserIdAndSha256AndStatusAndExpiresAtAfter(
                        userId, sha256, STATUS_UPLOADING, OffsetDateTime.now());
        if (existing.isPresent() && !"PENDING".equals(existing.get().getUploadId())) {
            UploadSessionEntity s = existing.get();
            return InitResult.uploading(s.getId(), s.getUploadId(), s.getChunkSize());
        }

        // 未命中：预检配额（只读快速拒绝）
        if (!quotaRepo.checkAvailable(userId, size)) {
            throw new BizException(ErrorCode.STORAGE_QUOTA_EXCEEDED);
        }
        // 闸 2：本次会动用到"企业分配"那部分时，还要企业池总量够（防超卖）
        guardEnterprisePool(userId, size);

        // 先落会话（拿 id），再建 MinIO multipart，最后回填 uploadId
        UploadSessionEntity s = sessionRepo.save(UploadSessionEntity.builder()
                .userId(userId).uploadId("PENDING").targetParent(targetParent)
                .name(name).sha256(sha256).sizeBytes(size)
                .chunkSize(minio.partSize()).status(STATUS_UPLOADING)
                .expiresAt(OffsetDateTime.now().plusHours(sessionTtlHours))
                .build());

        String uploadId;
        try {
            uploadId = minio.initMultipart(objectKeyOf(sha256));
        } catch (Exception e) {
            // 区分「存储连不上」与「其它失败」：否则前端只看到笼统的失败，
            // 运维也要翻日志才知道是 MinIO 挂了，而不是参数或代码问题。
            if (isStorageUnavailable(e)) {
                log.error("初始化上传失败：存储服务不可用 objectKey={} err={}",
                        objectKeyOf(sha256), e.toString());
                throw new BizException(ErrorCode.STORAGE_UNAVAILABLE,
                        "存储服务不可用：" + e.getMessage());
            }
            log.error("初始化上传失败 objectKey={} err={}", objectKeyOf(sha256), e.toString());
            throw new BizException(ErrorCode.SYSTEM_ERROR, "初始化上传失败");
        }
        s.setUploadId(uploadId);
        sessionRepo.save(s);

        return InitResult.uploading(s.getId(), uploadId, minio.partSize());
    }

    // ============ R-B03：分片上传（流式，幂等覆盖） ============
    public void part(Long userId, Long sessionId, int partNo, InputStream stream, long size) {
        UploadSessionEntity s = ownedSession(userId, sessionId);
        if (!STATUS_UPLOADING.equals(s.getStatus())) {
            throw new BizException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        long expectedChunks = chunkCount(s.getSizeBytes(), s.getChunkSize());
        if (partNo < 1 || partNo > expectedChunks) {
            throw new BizException(ErrorCode.PART_INDEX_OUT_OF_RANGE);
        }
        if (size > s.getChunkSize()) {
            throw new BizException(ErrorCode.PART_SIZE_EXCEEDED);
        }
        try {
            // 同一 partNo 重复上传幂等覆盖（MinIO 原生行为）
            minio.uploadPart(objectKeyOf(s.getSha256()), s.getUploadId(), partNo, stream, size);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "分片上传失败: " + rootMessage(e));
        }
    }

    // ============ R-B04：断点续传——查询会话与已传分片 ============
    public SessionStatus status(Long userId, Long sessionId) {
        UploadSessionEntity s = ownedSession(userId, sessionId);
        List<Integer> uploaded = new ArrayList<>();
        List<PartInfo> detail = new ArrayList<>();
        try {
            for (Part p : minio.listParts(objectKeyOf(s.getSha256()), s.getUploadId())) {
                uploaded.add(p.partNumber());
                detail.add(new PartInfo(p.partNumber(), p.partSize()));
            }
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "查询上传会话失败: " + rootMessage(e));
        }
        return new SessionStatus(s.getId(), s.getStatus(), s.getUploadId(), s.getChunkSize(), uploaded, detail);
    }

    // ============ R-B04b：传输任务列表（分页，倒序；可选 status 筛选） ============
    public UploadTaskPage listTasks(Long userId, String status, int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), 100); // 单页最多 100，防超大分页
        Pageable pageable = PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<UploadSessionEntity> result = hasText(status)
                ? sessionRepo.findByUserIdAndStatus(userId, status, pageable)
                : sessionRepo.findByUserId(userId, pageable);
        List<UploadTaskInfo> list = result.getContent().stream()
                .map(e -> new UploadTaskInfo(e.getId(), e.getName(), e.getSizeBytes(),
                        e.getStatus(), e.getFileId(), e.getCreatedAt()))
                .toList();
        return new UploadTaskPage(list, result.getTotalElements(), p, s);
    }

    // ============ R-B05/R-B06：complete（可重试 + 实扣配额） ============
    @Transactional
    public CompleteResult complete(Long userId, Long sessionId) {
        UploadSessionEntity s = ownedSession(userId, sessionId);
        if (STATUS_DONE.equals(s.getStatus())) {
            return new CompleteResult(s.getFileId(), true); // 幂等重试
        }
        if (!STATUS_UPLOADING.equals(s.getStatus())) {
            throw new BizException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }

        String objectKey = objectKeyOf(s.getSha256());
        // 校验分片完整并合并
        String partsDebug = "";
        try {
            List<Part> parts = minio.listParts(objectKey, s.getUploadId());
            long expected = chunkCount(s.getSizeBytes(), s.getChunkSize());
            if (parts.size() != expected) {
                throw new BizException(ErrorCode.PARTS_INCOMPLETE);
            }
            StringBuilder sb = new StringBuilder();
            List<Part> simple = new ArrayList<>();
            for (Part p : parts) {
                sb.append("[#").append(p.partNumber()).append(" size=").append(p.partSize()).append("]");
                simple.add(new Part(p.partNumber(), "\"" + p.etag() + "\""));
            }
            partsDebug = sb.toString();
            minio.completeMultipart(objectKey, s.getUploadId(), simple.toArray(new Part[0]));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // NoSuchUpload：MinIO 端 uploadId 已失效（重启/清理），或并发 complete 已消费该 uploadId
            if (isNoSuchUpload(e)) {
                // 并发完成兜底：另一个 complete 已成功落库 → 幂等返回，别误报失败
                Optional<UploadSessionEntity> latest = sessionRepo.findById(sessionId);
                if (latest.isPresent() && STATUS_DONE.equals(latest.get().getStatus())) {
                    return new CompleteResult(latest.get().getFileId(), true);
                }
                // uploadId 真失效：置 aborted，给前端可引导「重新上传」的明确错误，替代笼统 50000
                s.setStatus(STATUS_ABORTED);
                sessionRepo.save(s);
                throw new BizException(ErrorCode.UPLOAD_SESSION_EXPIRED);
            }
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "合并分片失败: " + e.getClass().getSimpleName() + " - " + e.getMessage() + " | MinIO分片=" + partsDebug);
        }

        // 实扣配额（条件 UPDATE 原子防超卖）
        guardEnterprisePool(userId, s.getSizeBytes());
        if (!quotaRepo.tryReserve(userId, s.getSizeBytes())) {
            try { minio.removeObject(objectKey); } catch (Exception ignored) { }
            throw new BizException(ErrorCode.STORAGE_QUOTA_EXCEEDED);
        }
        // 企业池冗余列同步累加（5 分钟校准任务会修正偏差）
        quotaRepo.addPoolUsed(userId, s.getSizeBytes());

        // 写元数据（对象为内容寻址 objects/<sha256>，引用计数归 files 表 ref_count）
        // 同级重名自动改名，避免撞 uk_files_sibling_name 唯一索引（修复上传同名文件 500 系统繁忙）
        long targetParent = s.getTargetParent() == null ? 0L : s.getTargetParent();
        FileEntity saved = fileRepo.save(FileEntity.builder()
                .ownerId(userId)
                .enterpriseId(currentEnterpriseId())
                .parentId(targetParent)
                .name(resolveUniqueName(userId, targetParent, s.getName()))
                .isDir(false).size(s.getSizeBytes())
                .sha256(s.getSha256()).refCount(1)
                .build());

        s.setFileId(saved.getId());
        s.setStatus(STATUS_DONE);
        sessionRepo.save(s);
        auditService.record(new AuditEvent(
                userId, AuditActions.UPLOAD, String.valueOf(saved.getId()), null,
                Map.of("name", saved.getName(), "size", s.getSizeBytes())));
        return new CompleteResult(saved.getId(), false);
    }

    // ============ abort ============
    @Transactional
    public void abort(Long userId, Long sessionId) {
        UploadSessionEntity s = ownedSession(userId, sessionId);
        if (STATUS_UPLOADING.equals(s.getStatus())) {
            try { minio.abortMultipart(objectKeyOf(s.getSha256()), s.getUploadId()); } catch (Exception ignored) { }
            s.setStatus(STATUS_ABORTED);
            sessionRepo.save(s);
        }
    }

    // ============ R-B04c/R-B04d：删除任务记录（清空已完成） ============
    @Transactional
    public void deleteTask(Long userId, Long sessionId) {
        UploadSessionEntity s = ownedSession(userId, sessionId);
        if (STATUS_UPLOADING.equals(s.getStatus())) {
            throw new BizException(ErrorCode.UPLOAD_SESSION_IN_PROGRESS);
        }
        sessionRepo.delete(s);
    }

    @Transactional
    public int clearCompleted(Long userId) {
        return (int) sessionRepo.deleteByUserIdAndStatus(userId, STATUS_DONE);
    }

    // ============ R-B07：下载签发 ============
    public String presignDownload(Long userId, Long fileId, boolean inline) {
        // 企业超额停用：下载与预览一并关闭（inline=true 就是预览）
        if (quotaRepo.enterpriseOversold(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "企业空间已超额（可分配额度为负），请联系企业管理员处理；"
                            + "当前无法上传、下载、预览文件");
        }
        FileEntity f = fileRepo.findById(fileId)
                .orElseThrow(() -> new BizException(ErrorCode.FILE_NOT_FOUND));
        if (!f.getOwnerId().equals(userId) || f.getDeletedAt() != null) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        try {
            // 5 分钟预签名 URL；字节流走 Nginx → MinIO，不过应用进程
            // inline=true 供预览（不强制 attachment），否则供下载（带文件名 attachment）
            String url = minio.presignGet(objectKeyOf(f.getSha256()), f.getName(), presignExpirySeconds, inline);
            auditService.record(new AuditEvent(
                    userId, AuditActions.DOWNLOAD, String.valueOf(f.getId()), null,
                    Map.of("name", f.getName(), "size", f.getSize(), "inline", inline)));
            return url;
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "生成下载地址失败");
        }
    }

    // ============ R-B08：24h 未完成会话清理 ============
    @Scheduled(cron = "0 0 * * * *")  // 每小时整点：清理过期未完成会话 + abort MinIO multipart
    @Transactional
    public int cleanupExpiredSessions() {
        List<UploadSessionEntity> expired = sessionRepo
                .findByStatusAndExpiresAtBefore(STATUS_UPLOADING, OffsetDateTime.now());
        int cleaned = 0;
        for (UploadSessionEntity s : expired) {
            try { minio.abortMultipart(objectKeyOf(s.getSha256()), s.getUploadId()); } catch (Exception ignored) { }
            s.setStatus(STATUS_ABORTED);
            sessionRepo.save(s);
            cleaned++;
        }
        return cleaned;
    }

    // ============ R-B10：引用计数维护（供 C 组彻底删除调用） ============
    @Transactional
    public void decrementRef(String sha256, long fileSize) {
        if (!hasText(sha256)) {
            return;
        }
        // 仍存在存活文件记录 → 内容对象仍有引用，不删；否则物理删除 MinIO 对象
        long alive = fileRepo.countBySha256AndDeletedAtIsNull(sha256);
        if (alive <= 0) {
            try { minio.removeObject(objectKeyOf(sha256)); } catch (Exception ignored) { }
        }
    }

    // ============ 私有工具 ============
    /** 同级重名自动改名：base 被占用则在扩展名前插入 (2)(3)... 序号（对齐 C 组 resolveUniqueName + uk_files_sibling_name）。 */
    private String resolveUniqueName(Long userId, long parentId, String base) {
        if (!fileRepo.existsByOwnerIdAndParentIdAndNameAndDeletedAtIsNull(userId, parentId, base)) {
            return base;
        }
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String ext = dot > 0 ? base.substring(dot) : "";
        int i = 2;
        while (fileRepo.existsByOwnerIdAndParentIdAndNameAndDeletedAtIsNull(userId, parentId, stem + " (" + i + ")" + ext)) {
            i++;
        }
        return stem + " (" + i + ")" + ext;
    }

    private UploadSessionEntity ownedSession(Long userId, Long sessionId) {
        UploadSessionEntity s = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new BizException(ErrorCode.UPLOAD_SESSION_NOT_FOUND));
        if (!s.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return s;
    }

    /** 对象 key：内容寻址 objects/<sha256>（同内容同 key，天然去重）。 */
    private String objectKeyOf(String sha256) {
        return "objects/" + sha256;
    }

    /** 沿异常链判断是否为 MinIO NoSuchUpload（multipart uploadId 失效/被消费）。 */
    private boolean isNoSuchUpload(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ErrorResponseException ere && "NoSuchUpload".equals(ere.errorResponse().code())) {
                return true;
            }
        }
        return false;
    }

    /** 沿异常链提取错误摘要：优先 MinIO 的 code + message，否则取最深层 cause 的类名 + message（避免 getMessage() 为 null）。 */
    private String rootMessage(Throwable e) {
        ErrorResponseException minioErr = null;
        Throwable deepest = e;
        for (Throwable t = e; t != null; t = t.getCause()) {
            deepest = t;
            if (t instanceof ErrorResponseException ere) {
                minioErr = ere;
            }
        }
        if (minioErr != null) {
            String code = minioErr.errorResponse().code();
            String msg = minioErr.errorResponse().message();
            return (code == null ? "" : code) + (msg == null || msg.isBlank() ? "" : ": " + msg);
        }
        String cn = deepest.getClass().getSimpleName();
        String m = deepest.getMessage();
        return m == null || m.isBlank() ? cn : cn + ": " + m;
    }

    private long chunkCount(long size, int chunkSize) {
        return size == 0 ? 0 : (size + chunkSize - 1) / chunkSize;
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private void validateSize(long size) {
        if (size <= 0) throw new BizException(ErrorCode.FILE_SIZE_INVALID);
        if (size > maxFileSize) throw new BizException(ErrorCode.FILE_TOO_LARGE);
    }

    private void validateExtension(String name) {
        if (allowedExtensions.isEmpty()) return;
        int dot = name.lastIndexOf('.');
        if (dot < 0) throw new BizException(ErrorCode.EXTENSION_NOT_ALLOWED);
        String ext = name.substring(dot + 1).toLowerCase();
        if (!allowedExtensions.contains(ext)) throw new BizException(ErrorCode.EXTENSION_NOT_ALLOWED);
    }

    private void validateSha256(String sha256) {
        if (!hasText(sha256)) {
            throw new BizException(ErrorCode.SHA256_REQUIRED);
        }
    }

    private Set<String> parseExtensions(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        Set<String> set = new HashSet<>();
        for (String s : csv.split(",")) {
            String t = s.trim().toLowerCase();
            if (!t.isEmpty()) set.add(t);
        }
        return set;
    }

    // ============ 结果 DTO（内联，简化） ============
    public record InitResult(boolean done, Long fileId, Long sessionId, String uploadId, int chunkSize) {
        public static InitResult done(Long fileId) {
            return new InitResult(true, fileId, null, null, 0);
        }
        public static InitResult uploading(Long sessionId, String uploadId, int chunkSize) {
            return new InitResult(false, null, sessionId, uploadId, chunkSize);
        }
    }

    public record SessionStatus(Long sessionId, String status, String uploadId, int chunkSize,
                                List<Integer> uploadedParts, List<PartInfo> partsDetail) { }

    public record PartInfo(int partNumber, long size) { }

    public record CompleteResult(Long fileId, boolean alreadyDone) { }

    /** 传输任务列表条目。 */
    public record UploadTaskInfo(Long id, String name, long sizeBytes, String status,
                                 Long fileId, OffsetDateTime createdAt) { }

    /** 传输任务分页结果。 */
    public record UploadTaskPage(List<UploadTaskInfo> list, long total, int page, int size) { }
    /**
     * 判断异常是否由「存储（MinIO）不可用」引起。
     *
     * <p>按**类名**判断而不是 import 具体类型：既避免多包一层
     * （{@code ExecutionException → ConnectException}）时漏判，
     * 也顺带覆盖了 JDK 的网络异常。
     */
    private static boolean isStorageUnavailable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String n = t.getClass().getName();
            if (n.startsWith("io.minio.")) {
                return true;
            }
            if ("java.net.ConnectException".equals(n)
                    || "java.net.SocketTimeoutException".equals(n)
                    || "java.net.UnknownHostException".equals(n)
                    || "java.net.NoRouteToHostException".equals(n)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
    /**
     * 当前租户 id（写入 {@code files.enterprise_id}）。
     *
     * <p>上传是"写文件记录"的关键路径：这里不赋值，文件就会落成
     * {@code enterprise_id = 0}（个人域），企业用户上传的文件变成"个人文件"，
     * 在按企业过滤的查询里直接消失——是静默的数据错误，必须显式取。
     */
    private static long currentEnterpriseId() {
        try {
            return com.company.cloud.common.tenant.TenantContext.isPresent()
                    ? com.company.cloud.common.tenant.TenantContext.require()
                    : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }
    /**
     * 闸 2：企业池总量校验（docs/31 §6）。
     *
     * <p>只有当本次上传会**用到企业分配额度**时才检查：
     * 个人剩余（免费 + 个人增量 − 已用）够则完全不涉及企业池，直接放行。
     *
     * <p>无企业 / 无池（返回 -1）时跳过 —— 个人用户行为与之前完全一致。
     */
    /**
     * 超卖闸门（平台级，凌驾于角色权限之上）。
     *
     * <p>可分配额度为负时关闭「上传 / 下载 / 预览」。**所有上传入口都必须先过这一关**，
     * 包括秒传 —— 秒传只是省掉字节传输，仍然占用额度，也不该绕过停用状态。
     */
    private void ensureNotOversold(long userId) {
        if (quotaRepo.enterpriseOversold(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "企业空间已超额（可分配额度为负），请联系企业管理员处理；"
                            + "当前无法上传、下载、预览文件");
        }
    }

    private void guardEnterprisePool(long userId, long size) {
        // 超卖已由 ensureNotOversold 在入口处处理；这里再兜一次（complete 等路径）
        ensureNotOversold(userId);
        // 企业套餐到期后：可读可下载，**禁止上传**（2026-10-30 口径：无宽限期，到期即回收）。
        // 判定放在上传口而不是只靠登录口 —— 已登录的会话也要拦住。
        if (quotaRepo.enterpriseInGraceOnly(userId)) {
            throw new BizException(ErrorCode.STORAGE_QUOTA_EXCEEDED,
                    "企业套餐已到期，续费后可继续上传（现有文件仍可查看与下载）");
        }
        long personalLeft = quotaRepo.personalRemaining(userId);
        if (personalLeft >= size) {
            return;   // 个人额度够，不碰企业池
        }
        long poolLeft = quotaRepo.enterprisePoolRemaining(userId);
        if (poolLeft < 0) {
            return;   // 不在企业内 / 无池
        }
        if (poolLeft < size) {
            // 分配额度可能虚高（池缩水/分配写错），这里兜住，避免真写爆
            throw new BizException(ErrorCode.STORAGE_QUOTA_EXCEEDED,
                    "企业空间不足：" + (poolLeft / 1024 / 1024) + " MB（请联系管理员续费或调整分配）");
        }
    }
}
