package com.amomeal.marketplace.attachment.web;

import com.amomeal.marketplace.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real S3-API-compatible integration test for {@link com.amomeal.marketplace.attachment.service.S3AttachmentStorageService},
 * run against a LocalStack container instead of real AWS (no AWS credentials
 * needed to run this suite). Exercises the exact same "presigned url -> PUT the
 * bytes -> completed" lifecycle as {@code AttachmentControllerTest}'s
 * local-storage test, but with a real presigned S3 PUT URL, a real HTTP client
 * PUT (not MockMvc — the bytes go straight to LocalStack, bypassing our app
 * entirely, exactly like a real S3 upload would), and a direct S3 head-object
 * check to confirm the file actually landed in the bucket.
 *
 * <p>{@code app.storage.backend=s3} is already this project's default (see
 * PROGRESS.md's resolved storage decision) — set explicitly here anyway for
 * clarity/robustness against future default changes.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AttachmentS3ControllerTest {

    private static final String BUCKET = "amomeal-test-bucket";

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
            .withServices("s3")
            .waitingFor(Wait.forLogMessage(".*Ready\\.\n", 1));

    @DynamicPropertySource
    static void s3Props(DynamicPropertyRegistry registry) {
        registry.add("app.storage.backend", () -> "s3");
        registry.add("app.storage.s3-endpoint-override", () -> localstack.getEndpoint().toString());
        registry.add("app.storage.s3-access-key-id", localstack::getAccessKey);
        registry.add("app.storage.s3-secret-access-key", localstack::getSecretKey);
        registry.add("app.storage.s3-region", localstack::getRegion);
        registry.add("app.storage.s3-bucket-name", () -> BUCKET);
    }

    @BeforeAll
    static void createBucket() {
        try (S3Client s3 = s3Client()) {
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
    }

    private static S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(localstack.getEndpoint())
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .forcePathStyle(true)
                .build();
    }

    @Autowired
    private MockMvc mockMvc;

    private String registerAndGetAccessToken() throws Exception {
        String nonce = String.valueOf(System.nanoTime());
        String email = "attach-s3+" + nonce + "@amomeal.test";
        String body = """
                {"username":"chef-attach-s3-%s","email":"%s","password":"correct-horse-battery","phone_number":"0900000002"}
                """.formatted(nonce, email);
        String json = mockMvc.perform(post("/api/auth/register").contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString();
        return extractField(json, "access_token");
    }

    @Test
    void presignedS3UrlUploadCompletedLifecycle() throws Exception {
        String accessToken = registerAndGetAccessToken();

        String presignBody = """
                {"file_name":"dish.png","file_size":4,"attachment_type":"DISH"}
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
        // Real presigned S3 (LocalStack) URL: signed query params, points at the bucket/key,
        // NOT at our own /api/attachments/{uid}/upload endpoint (that's local-backend only).
        assertThat(uploadUrl).contains(BUCKET).contains("X-Amz-Signature");

        // completing before the file is actually uploaded to S3 -> 404 ATTACHMENT_NOT_FOUND
        mockMvc.perform(put("/api/attachments/" + uid + "/completed")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("ATTACHMENT_NOT_FOUND")));

        // PUT the raw bytes straight to the presigned S3 (LocalStack) URL via a plain HTTP
        // client — never touches our app, exactly like a real browser-to-S3 upload.
        byte[] fileBytes = {1, 2, 3, 4};
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpResponse<String> putResponse = httpClient.send(
                HttpRequest.newBuilder(URI.create(uploadUrl))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(fileBytes))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(putResponse.statusCode()).isEqualTo(200);

        // Confirm directly against S3 (LocalStack) that the object actually landed in the bucket.
        try (S3Client s3 = s3Client()) {
            // directory is derived from AttachmentType.DISH -> "dish"; hashed_name is whatever
            // the service generated — easiest reliable way to find it is to list by prefix.
            var listing = s3.listObjectsV2(b -> b.bucket(BUCKET).prefix("dish/"));
            assertThat(listing.contents()).isNotEmpty();
            String actualKey = listing.contents().get(0).key();
            s3.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(actualKey).build());
        }

        // now completing succeeds
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

    private static String extractField(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
