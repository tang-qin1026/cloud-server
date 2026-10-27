package com.company.cloud.auth.tenant.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 企业状态只读查询。
 *
 * <p><b>为什么需要它</b>：门户在<b>登录时</b>会按企业四态拒绝
 * （`pending→2011` / `rejected→2012` / `disabled→2007`），但**token 已经签发之后**
 * 企业被停用/驳回，产品侧如果不复核，该企业成员最长仍可用一个 access-token 生命周期
 * （默认 120 分钟）。这里补上"每次请求复核一次状态"。
 *
 * <p>⚠️ 业务归属：<b>只有 active 对外服务</b>。查不到企业（返回 null）也按不可用处理（fail-closed）。
 */
@Mapper
public interface EnterpriseStatusMapper {

    /** 返回企业状态；企业不存在时返回 {@code null}。 */
    @Select("SELECT status FROM enterprise WHERE id = #{enterpriseId}")
    String selectStatus(@Param("enterpriseId") long enterpriseId);
}
