package com.company.cloud.files.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.cloud.files.audit.entity.AuditLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * audit_logs 表数据访问（Owner：C 组）。
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {

    /**
     * 写入一条审计日志。detail 为 JSON 文本，CAST 成 jsonb 落库——
     * MP 默认 insert 处理不了 jsonb cast，必须自定义 SQL。
     *
     * <p><b>V2025（M2）</b>：显式带 {@code enterprise_id}。
     * 审计走 {@code @Async}，<b>异步线程里 TenantContext 是空的</b>，
     * 所以企业 id 必须由<b>调用线程</b>取好后传进来（见 {@code AuditServiceImpl}）。
     */
    @Insert("""
            INSERT INTO audit_logs(user_id, action, target, ip, detail, enterprise_id)
            VALUES (#{userId}, #{action}, #{target}, #{ip}, CAST(#{detail} AS jsonb), #{enterpriseId})
            """)
    int insertEvent(@Param("userId") Long userId,
                    @Param("action") String action,
                    @Param("target") String target,
                    @Param("ip") String ip,
                    @Param("detail") String detail,
                    @Param("enterpriseId") Long enterpriseId);

    /**
     * 按 user_id 批量取用户名（查询补 VO 的 username 用）。
     * 返回 List of Map：{id=Long, username=String}；users 表 Owner 为 A 组，只读。
     */
    @Select("""
            <script>
            SELECT id, username FROM users
            WHERE id IN
            <foreach collection="userIds" item="uid" open="(" separator="," close=")">#{uid}</foreach>
            </script>
            """)
    List<Map<String, Object>> selectUsernames(@Param("userIds") List<Long> userIds);
    /**
     * 批量取「用户 id → 展示名」。nickname 优先（门户用户的 username 是 portal_<uuid>，不能直接展示）。
     */
    @Select("""
            <script>
            SELECT id, username, nickname FROM users
             WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Map<String, Object>> selectUserNames(@Param("ids") List<Long> ids);

    /** 批量取文件名：mkdir / move / delete / restore / download 这类记录的 target 是节点 id。 */
    @Select("""
            <script>
            SELECT id, name FROM files
             WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Map<String, Object>> selectFileNames(@Param("ids") List<Long> ids);

    /** 按登录名批量取昵称：login / user_manage 这类记录的 target 直接是用户名。 */
    @Select("""
            <script>
            SELECT username, nickname FROM users
             WHERE username IN
            <foreach collection="names" item="n" open="(" separator="," close=")">#{n}</foreach>
            </script>
            """)
    List<Map<String, Object>> selectNicknamesByUsernames(@Param("names") List<String> names);
}