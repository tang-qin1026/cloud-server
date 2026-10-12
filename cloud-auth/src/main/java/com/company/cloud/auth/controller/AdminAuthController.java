package com.company.cloud.auth.controller;

import com.company.cloud.auth.dto.LoginRequest;
import com.company.cloud.auth.dto.LoginResponse;
import com.company.cloud.auth.service.AuthService;
import com.company.cloud.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员登录入口（独立于 {@code /api/auth/login}）。
 *
 * <p><b>为什么要单独一个入口</b>（《云存储侧改造说明》§7）：
 * 管理端登录<b>只验本地密码、绝不回落门户、也不建任何映射</b>。
 * 这样"门户里恰好有个普通用户叫 admin，从而拿到云存储管理员权限"在结构上就不可能发生。
 *
 * <p>与主登录页的区别：
 * <ul>
 *   <li>{@code POST /api/auth/login}：普通用户用。本地验 → 验不过回落门户。</li>
 *   <li>{@code POST /api/admin/login}：管理员用。只验本地密码；非管理员账号调用返回 {@code 40301}。</li>
 * </ul>
 *
 * <p>限流与失败锁定与主入口共用（{@code LoginThrottle}），所以这里不会成为绕过暴力破解的旁路。
 *
 * <p>注意：本入口是<b>新增</b>的，不影响既有 {@code /api/auth/login}；
 * 管理端前端是否切换到本入口由其自行决定。
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AuthService authService;

    /** 管理员登录：仅本地密码校验，不调门户。 */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req,
                                       HttpServletRequest http) {
        return Result.ok(authService.adminLogin(req, http));
    }
}
