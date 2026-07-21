package com.interview.agent.common;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 统一响应包装：控制器直接返回业务数据 {@code T}，这里自动裹成 {@link ApiResponse}
 * （{@code {"code":0,"data":T,"message":"success"}}），省去每个方法手写 {@code ApiResponse.success(...)}。
 *
 * <h3>规则</h3>
 * <ul>
 *   <li>仅作用于 Jackson 序列化的 JSON 响应；String / byte[] / Resource / SSE(SseEmitter) /
 *       RedirectView 等走别的转换器 → 一律不介入。</li>
 *   <li>body 已是 {@link ApiResponse} → 原样透传（{@link GlobalExceptionHandler} 的错误响应、
 *       以及尚未改造的老接口，都不会被双重包装）。</li>
 *   <li>body 为 {@code null}（无返回值的操作类接口）→ 包成 {@code data=null} 的成功响应。</li>
 * </ul>
 */
@RestControllerAdvice(basePackages = "com.interview.agent")
public class ApiResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // 只包 JSON：String/字节流/SSE 等用别的转换器，不介入
        return AbstractJackson2HttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        // 已包装（含异常处理器返回的错误码）→ 透传，避免双重包装
        if (body instanceof ApiResponse) {
            return body;
        }
        return ApiResponse.success(body);
    }
}
