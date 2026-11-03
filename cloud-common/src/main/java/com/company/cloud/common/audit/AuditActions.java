package com.company.cloud.common.audit;

/**
 * 审计动作字典（任务书 04 §3 R-C08，由 C 组维护，改动需提前与 A/B 组对齐）。
 *
 * <p>覆盖范围：登录、上传、下载、删除、恢复、配额变更、用户管理操作、计费动作。
 * 计费动作（§4.6 冻结契约）由 D 组 billing 模块写入，A/B/C 组不改。
 */
public final class AuditActions {

    private AuditActions() {
    }

    /** 登录（A 组调用） */
    public static final String LOGIN = "login";
    /** 登出（A 组调用） */
    public static final String LOGOUT = "logout";
    /** 上传（B 组调用） */
    public static final String UPLOAD = "upload";
    /** 下载（B 组调用） */
    public static final String DOWNLOAD = "download";
    /** 新建文件夹（C 组） */
    public static final String MKDIR = "mkdir";
    /** 删除入回收站（C 组） */
    public static final String DELETE = "delete";
    /** 彻底删除（C 组） */
    public static final String DELETE_FORCE = "delete_force";
    /** 从回收站恢复（C 组） */
    public static final String RESTORE = "restore";
    /** 移动文件/目录（C 组，含前端拖拽移动；detail 带 fromParentId/toParentId） */
    public static final String MOVE = "move";
    /** 配额变更（A 组调用） */
    public static final String QUOTA_CHANGE = "quota_change";
    /** 用户管理操作（A 组调用：新建/禁用/重置密码等，细分动作放 detail） */
    public static final String USER_MANAGE = "user_manage";

    // ---- 计费动作（D 组 billing，§4.6 冻结契约）----
    /** 用户提交增额申请 */
    public static final String BILLING_REQUEST = "billing_request";
    /** 审批通过并生成计费记录 */
    public static final String BILLING_APPROVE = "billing_approve";
    /** 审批驳回 */
    public static final String BILLING_REJECT = "billing_reject";
    /** 定时到期收回增量额度 */
    public static final String BILLING_EXPIRE = "billing_expire";
    /** 修改计费全局配置 */
    public static final String BILLING_CONFIG = "billing_config";

    /** 购买套餐（用户端下单成功）。target = 门户订单号 */
    public static final String BILLING_ORDER = "billing_order";

    /** 套餐续费（延长原有开通）。target = 开通记录的 orderItemId */
    public static final String BILLING_RENEW = "billing_renew";

    // ---- 用户降级（A 组新增接口）----
    /** 管理员将用户角色降级（admin→user 等） */
    public static final String USER_DEMOTE = "user_demote";
}