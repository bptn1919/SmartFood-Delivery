package com.amomeal.marketplace.common.response;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Writes an {@link ApiResponse} error envelope directly to the servlet
 * response — used where the failure happens in the Spring Security filter
 * chain, before/outside {@code common.exception.GlobalExceptionHandler}
 * (which only sees exceptions thrown from inside controller method
 * invocation).
 */
@Component
@RequiredArgsConstructor
public class ApiErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public void write(HttpServletResponse response, int status, String messageCode, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(status, messageCode, message, null)));
    }
}
