package com.amomeal.marketplace.common.response;

import java.time.Instant;

/**
 * Envelope shape mirrors Django's {@code utils/router/api.py::BaseAPI.create_response}
 * and {@code utils/router/exception.py::create_response} exactly — see CLAUDE.md §3.
 *
 * <ul>
 *   <li>Success: {@code errorCode} is 0 unless the endpoint declared a non-200
 *       status (e.g. 201 Created), in which case it carries that status while
 *       the actual HTTP transport status is still forced to 200
 *       (see {@link ResponseEnvelopeAdvice}).</li>
 *   <li>Error: {@code errorCode} equals the real HTTP transport status.</li>
 * </ul>
 */
public record ApiResponse<T>(
        T data,
        String messageCode,
        String message,
        int errorCode,
        Instant currentTime
) {

    public static <T> ApiResponse<T> success(T data, int declaredStatus) {
        int errorCode = declaredStatus == 200 ? 0 : declaredStatus;
        return new ApiResponse<>(data, "SUCCESS", "Success", errorCode, Instant.now());
    }

    public static <T> ApiResponse<T> error(int httpStatus, String messageCode, String message, T detail) {
        return new ApiResponse<>(detail, messageCode, message, httpStatus, Instant.now());
    }
}
