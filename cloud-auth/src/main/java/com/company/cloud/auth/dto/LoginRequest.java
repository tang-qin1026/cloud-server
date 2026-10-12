package com.company.cloud.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.ToString;

/**
 * 登录请求：用户名 + 密码
 */
@Data
public class LoginRequest {
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 明文密码。
     *
     * <p>{@code @ToString.Exclude}：{@code @Data} 生成的 {@code toString()} 默认包含所有字段，
     * 一旦有人把 {@code LoginRequest} 打进日志（含 debug 日志、异常信息、APM 采样），
     * 明文密码就会落盘。这里显式排除，属于"密码不入日志"的兜底防线。
     */
    @NotBlank(message = "密码不能为空")
    @ToString.Exclude
    private String password;
}
