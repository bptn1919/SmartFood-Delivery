package com.amomeal.marketplace.attachment.web;

import com.amomeal.marketplace.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end verification of the attachment module's upload lifecycle through
 * the full stack (real filter chain, real Postgres via Testcontainers, real
 * local filesystem storage under a JUnit temp dir) — exercises the
 * "presigned url" -> upload -> completed lifecycle
 * (../../backend/attachment/services.py / exceptions/attachments.py) and the
 * response envelope contract (CLAUDE.md §3).
 *
 * <p>Explicitly forces {@code app.storage.backend=local} (the opt-in backend,
 * see PROGRESS.md) since real S3 is now this project's default — see
 * {@code AttachmentS3ControllerTest} for the default-backend (LocalStack)
 * coverage of the same lifecycle.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AttachmentControllerTest {

    @TempDir
    static Path mediaRoot;

    @DynamicPropertySource
    static void storageProps(DynamicPropertyRegistry registry) {
        registry.add("app.storage.backend", () -> "local");
        registry.add("app.storage.local-root", () -> mediaRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    private String registerAndGetAccessToken() throws Exception {
        String nonce = String.valueOf(System.nanoTime());
        String email = "attach+" + nonce + "@amomeal.test";
        String body = """
                {"username":"chef-attach-%s","email":"%s","password":"correct-horse-battery","phone_number":"0900000001"}
                """.formatted(nonce, email);
        String json = mockMvc.perform(post("/api/auth/register").contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString();
        return extractField(json, "access_token");
    }

    @Test
    void presignedUrlUploadCompletedLifecycle() throws Exception {
        String accessToken = registerAndGetAccessToken();

        // 1. request a presigned (local-substitute) upload URL
        String presignBody = """
                {"file_name":"dish.png","file_size":1024,"attachment_type":"DISH"}
                """;
        String presignJson = mockMvc.perform(post("/api/attachments/presigned-url")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json")
                        .content(presignBody))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"message_code\":\"SUCCESS\"")))
                .andReturn().getResponse().getContentAsString();

        String uid = extractField(presignJson, "uid");
        String uploadUrl = extractField(presignJson, "url");
        assertThat(uid).isNotBlank();
        assertThat(uploadUrl).contains("/api/attachments/" + uid + "/upload").contains("token=");

        URI uri = URI.create(uploadUrl);
        String pathAndQuery = uri.getPath() + "?" + uri.getQuery();

        // completing before the file is actually uploaded -> 404 ATTACHMENT_NOT_FOUND
        mockMvc.perform(put("/api/attachments/" + uid + "/completed")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("ATTACHMENT_NOT_FOUND")));

        // 2. PUT the raw bytes to the (local-substitute) presigned URL — public, no auth header,
        // same as the real S3 flow / FE-admin's uploadToS3 XHR call.
        mockMvc.perform(put(pathAndQuery).content(new byte[]{1, 2, 3, 4}))
                .andExpect(status().isOk());

        // wrong token -> 403 INVALID_UPLOAD_TOKEN
        mockMvc.perform(put("/api/attachments/" + uid + "/upload?token=not-the-real-token")
                        .content(new byte[]{1, 2, 3, 4}))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("INVALID_UPLOAD_TOKEN")));

        // 3. complete the upload
        mockMvc.perform(put("/api/attachments/" + uid + "/completed")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"data\":true")));

        // completing again -> 400 ATTACHMENT_ALREADY_COMPLETED
        mockMvc.perform(put("/api/attachments/" + uid + "/completed")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("ATTACHMENT_ALREADY_COMPLETED")));
    }

    @Test
    void presignedUrlWithoutAuthReturnsUnauthorized() throws Exception {
        String presignBody = """
                {"file_name":"dish.png","file_size":1024,"attachment_type":"DISH"}
                """;
        mockMvc.perform(post("/api/attachments/presigned-url")
                        .contentType("application/json")
                        .content(presignBody))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("UNAUTHORIZED")));
    }

    @Test
    void completedUploadForUnknownUidReturnsNotFound() throws Exception {
        String accessToken = registerAndGetAccessToken();
        mockMvc.perform(put("/api/attachments/" + java.util.UUID.randomUUID() + "/completed")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("ATTACHMENT_NOT_FOUND")));
    }

    private static String extractField(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
