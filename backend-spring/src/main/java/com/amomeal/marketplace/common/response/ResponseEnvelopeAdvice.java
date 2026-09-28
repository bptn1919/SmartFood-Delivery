package com.amomeal.marketplace.common.response;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Wraps every successful controller return value in {@link ApiResponse} and
 * forces the actual HTTP transport status to 200 — mirrors the (unusual)
 * Django/ninja contract documented in CLAUDE.md §3. Error responses are
 * produced separately by {@code common.exception.GlobalExceptionHandler},
 * which already returns an {@link ApiResponse} body — {@link #beforeBodyWrite}
 * detects those (by runtime type, see note below) and passes them through
 * untouched, real status included.
 *
 * NOTE: the "already wrapped" check MUST happen in {@code beforeBodyWrite},
 * not {@code supports()} — a handler returning {@code ResponseEntity<ApiResponse<T>>}
 * has an erased {@code MethodParameter.getParameterType()} of
 * {@code ResponseEntity.class}, not {@code ApiResponse.class}, so
 * {@code supports()} can never reliably see through the generic wrapper.
 * {@code beforeBodyWrite}'s {@code body} argument, by contrast, is the
 * already-unwrapped runtime object (Spring's {@code HttpEntityMethodProcessor}
 * unwraps {@code ResponseEntity} before invoking this advice) — checking
 * {@code instanceof} there is reliable.
 */
@RestControllerAdvice
public class ResponseEnvelopeAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        Class<?> paramType = returnType.getParameterType();
        // String/byte[]/Resource returns pick a dedicated HttpMessageConverter (e.g.
        // StringHttpMessageConverter) before this advice runs — returning a different
        // object type from beforeBodyWrite for those blows up at write time. Controllers
        // in this project always return DTOs/void, never raw text, so just exclude them.
        if (CharSequence.class.isAssignableFrom(paramType) || paramType == byte[].class) {
            return false;
        }
        String pkg = returnType.getContainingClass().getPackageName();
        // Don't touch springdoc/actuator's own responses — they have their own contracts.
        return !pkg.startsWith("org.springdoc") && !pkg.startsWith("org.springframework.boot.actuate");
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request, ServerHttpResponse response) {

        if (body instanceof ApiResponse<?>) {
            // Already an envelope (GlobalExceptionHandler's error path) — its ResponseEntity
            // already set the real error status; leave it alone, don't force 200.
            return body;
        }

        int declaredStatus = 200;
        if (response instanceof ServletServerHttpResponse servletResponse) {
            // By this point Spring has already applied @ResponseStatus / ResponseEntity's
            // status onto the servlet response (even though nothing has been flushed yet) —
            // reading it here is the reliable way to know what the handler "declared",
            // regardless of which mechanism it used to declare it.
            declaredStatus = servletResponse.getServletResponse().getStatus();
            servletResponse.getServletResponse().setStatus(200);
        }
        return ApiResponse.success(body, declaredStatus);
    }
}
