package com.company.cloud.app.config;

import com.company.cloud.common.result.BizException;
import com.company.cloud.common.result.ErrorCode;
import com.company.cloud.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 全局异常处理：统一转为 {code, message, data} 返回（总纲 §5）。
 *
 * <p><b>为什么必须单独处理"未知路由"</b>：
 * Spring Boot 3.2+ 对没有匹配 handler 的请求抛的是
 * {@link NoResourceFoundException}（而不是旧版的 {@link NoHandlerFoundException}）。
 * 如果只写 {@code @ExceptionHandler(Exception.class)} 兜底，前端拿到的会是
 * <b>HTTP 200 + code=50000「系统繁忙」</b> ——
 * 于是"接口不存在"和"服务真的挂了"在前端看起来完全一样，
 * 排查时会误判成后端故障（本次 RBAC 未开发的 11 个端点就是这样被误报的）。
 *
 * <p>所以这里显式返回 <b>HTTP 404 + code=40400</b>，让"没这个接口"一眼可辨。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：按 ErrorCode 返回 */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /** 参数校验/解析失败：统一 40000 段位（契约参数类错误） */
    @ExceptionHandler({
            BindException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public Result<Void> handleBadRequest(Exception e) {
        log.warn("参数错误: {}", e.getMessage());
        return Result.error(40000, "请求参数错误");
    }

    /**
     * 未知路由 / 静态资源不存在 → **HTTP 404 + 40400**。
     *
     * <p>覆盖两种异常：
     * <ul>
     *   <li>{@link NoResourceFoundException} —— Spring Boot 3.2+ 默认抛这个</li>
     *   <li>{@link NoHandlerFoundException} —— 配置了
     *       {@code spring.mvc.throw-exception-if-no-handler-found=true} 时才抛</li>
     * </ul>
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNotFound(Exception e, HttpServletRequest request) {
        log.warn("接口不存在: {} {}", request.getMethod(), request.getRequestURI());
        return Result.error(ErrorCode.API_NOT_FOUND.getCode(),
                "接口不存在：" + request.getMethod() + " " + request.getRequestURI());
    }

    /** HTTP 方法不支持（如对 GET 接口发 POST）→ **405 + 40500** */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e,
                                               HttpServletRequest request) {
        log.warn("请求方法不支持: {} {} ({})", request.getMethod(), request.getRequestURI(), e.getMessage());
        return Result.error(ErrorCode.METHOD_NOT_ALLOWED.getCode(),
                "请求方法不支持：" + request.getMethod() + " " + request.getRequestURI());
    }

    /** 兜底：系统错误 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        // 存储（MinIO）不可用单独识别：否则"存储连不上"和"代码有 bug"都是 50000，
        // 前端没法提示、运维要翻日志才知道是存储的问题。
        if (isStorageUnavailable(e)) {
            log.error("存储服务不可用: {}", rootMessage(e), e);
            return Result.error(ErrorCode.STORAGE_UNAVAILABLE.getCode(),
                    ErrorCode.STORAGE_UNAVAILABLE.getMessage() + "（" + rootMessage(e) + "）");
        }
        log.error("系统异常", e);
        return Result.error(ErrorCode.SYSTEM_ERROR);
    }

    // ── 存储不可用的识别 ────────────────────────────────────────────
    //
    // 刻意不 import io.minio.* ：靠**类名**判断，避免 cloud-app 在编译期耦合到 minio，
    // 也顺带覆盖了被包装多层（ExecutionException → ConnectException）的情况。

    /** 明确表示"连不上/超时/无路由/域名解析不了"的 JDK 网络异常。 */
    private static final String[] NETWORK_THROWABLES = {
            "java.net.ConnectException",
            "java.net.SocketTimeoutException",
            "java.net.NoRouteToHostException",
            "java.net.UnknownHostException",
            "java.net.PortUnreachableException",
    };

    private boolean isStorageUnavailable(Throwable e) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = e; t != null && seen.add(t); t = t.getCause()) {
            String n = t.getClass().getName();
            if (n.startsWith("io.minio.")) {
                return true;
            }
            for (String net : NETWORK_THROWABLES) {
                if (net.equals(n)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 取最内层异常的消息（"Failed to connect to /192.168.9.160:9000" 这类才关键）。 */
    private String rootMessage(Throwable e) {
        Throwable root = e;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = e; t != null && seen.add(t); t = t.getCause()) {
            root = t;
        }
        String msg = root.getMessage();
        return msg == null || msg.isBlank() ? root.getClass().getSimpleName() : msg;
    }
}
