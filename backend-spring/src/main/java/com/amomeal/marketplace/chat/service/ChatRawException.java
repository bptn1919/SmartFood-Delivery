package com.amomeal.marketplace.chat.service;

import java.util.Map;

/**
 * A DRF-style raw error: Django's chat views answer {@code Response({"error": ...}, status=N)} directly
 * (no ninja envelope), so the chat controller writes {@link #getBody()} verbatim with {@link #getStatus()}.
 */
public class ChatRawException extends RuntimeException {

    private final int status;
    private final transient Map<String, Object> body;

    public ChatRawException(int status, Map<String, Object> body) {
        super(String.valueOf(body));
        this.status = status;
        this.body = body;
    }

    public static ChatRawException error(int status, String message) {
        return new ChatRawException(status, Map.of("error", message));
    }

    public int getStatus() {
        return status;
    }

    public Map<String, Object> getBody() {
        return body;
    }
}
