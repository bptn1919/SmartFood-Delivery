package com.amomeal.marketplace.certificate.web;

import com.amomeal.marketplace.TestcontainersConfiguration;
import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.entity.AttachmentType;
import com.amomeal.marketplace.attachment.repository.AttachmentRepository;
import com.amomeal.marketplace.certificate.dto.CertificateRequest;
import com.amomeal.marketplace.certificate.entity.CertificateType;
import com.amomeal.marketplace.certificate.service.CertificateService;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full-stack test of /api/certificates plus the profile seam (is_food_safety_certified). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CertificateControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private CustomUserRepository customUserRepository;
    @Autowired private AttachmentRepository attachmentRepository;
    @Autowired private CertificateService certificateService;

    private record Account(String token, Long userId) {
    }

    private Account register(String prefix, UserRole role) throws Exception {
        String nonce = String.valueOf(System.nanoTime());
        String email = prefix + "+" + nonce + "@amomeal.test";
        String body = """
                {"username":"%s-%s","email":"%s","password":"correct-horse-battery","phone_number":"0900000014"}
                """.formatted(prefix, nonce, email);
        String json = mockMvc.perform(post("/api/auth/register").contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        CustomUser user = customUserRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.getRoles().clear();
        user.addRole(role);
        customUserRepository.save(user);
        return new Account(extract(json, "access_token"), user.getId());
    }

    private static String extract(String json, String field) {
        String needle = "\"" + field + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) {
            throw new IllegalStateException(field + " not found in: " + json);
        }
        start += needle.length();
        return json.substring(start, json.indexOf('"', start));
    }

    private String submit(Account owner, String name, CertificateType type) {
        CustomUser u = customUserRepository.findById(owner.userId()).orElseThrow();
        return certificateService.createCertificate(u, new CertificateRequest(name, "mô tả", "Sở Y tế",
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), type)).uid().toString();
    }

    private UUID completedAttachment() {
        return attachmentRepository.save(Attachment.builder().type(AttachmentType.DISH).originalName("c.jpg")
                .hashedName("c-" + UUID.randomUUID() + ".jpg").size(10).contentType("image/jpeg")
                .bucket("b").directory("dish").publicUrl("https://cdn.example.test/c.jpg")
                .isCompleted(true).build()).getUid();
    }

    private String auth(Account a) {
        return "Bearer " + a.token();
    }

    private void setStatus(Account admin, String uid, String status, String reason) throws Exception {
        String body = reason == null ? "{\"status\":\"" + status + "\"}"
                : "{\"status\":\"" + status + "\",\"rejection_reason\":\"" + reason + "\"}";
        mockMvc.perform(patch("/api/certificates/" + uid + "/status").header("Authorization", auth(admin))
                .contentType("application/json").content(body)).andExpect(status().isOk());
    }

    @Test
    void lifecycle_submitApproveReject_withOwnershipAndAdminGating() throws Exception {
        Account chef = register("cert-chef", UserRole.CHEF);
        Account other = register("cert-other", UserRole.CHEF);
        Account admin = register("cert-admin", UserRole.ADMIN);
        String uid = submit(chef, "Giấy chứng nhận ATTP", CertificateType.FOOD_SAFETY);

        // owner sees it, other chef can't, admin can
        mockMvc.perform(get("/api/certificates/" + uid).header("Authorization", auth(chef)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.owner").value(chef.userId()))
                .andExpect(jsonPath("$.data.certificate_type").value("FOOD_SAFETY"))
                .andExpect(jsonPath("$.data.attachments").isEmpty());
        mockMvc.perform(get("/api/certificates/" + uid).header("Authorization", auth(other)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message_code").value("PERMISSION_DENIED"));
        mockMvc.perform(get("/api/certificates/" + uid).header("Authorization", auth(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/certificates/" + UUID.randomUUID()).header("Authorization", auth(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message_code").value("CERTIFICATE_NOT_FOUND"));

        // chef can't review
        mockMvc.perform(patch("/api/certificates/" + uid + "/status").header("Authorization", auth(chef))
                        .contentType("application/json").content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isForbidden());

        // reject needs a reason
        mockMvc.perform(patch("/api/certificates/" + uid + "/status").header("Authorization", auth(admin))
                        .contentType("application/json").content("{\"status\":\"REVOKED\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/certificates/" + uid + "/status").header("Authorization", auth(admin))
                        .contentType("application/json")
                        .content("{\"status\":\"REVOKED\",\"rejection_reason\":\"Ảnh mờ\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"))
                .andExpect(jsonPath("$.data.rejection_reason").value("Ảnh mờ"))
                .andExpect(jsonPath("$.data.verified_by").value(admin.userId()));

        // approve
        mockMvc.perform(patch("/api/certificates/" + uid + "/status").header("Authorization", auth(admin))
                        .contentType("application/json").content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.verified_at").isNotEmpty());

        // an ACTIVE certificate can no longer be hidden
        mockMvc.perform(patch("/api/certificates/" + uid + "/deleted").header("Authorization", auth(admin)))
                .andExpect(status().isForbidden());
    }

    @Test
    void listMineAndAdminList_filterSearchStatusCategoriesChefAndPaginate() throws Exception {
        Account chef = register("cert-list", UserRole.CHEF);
        Account other = register("cert-list-other", UserRole.CHEF);
        Account admin = register("cert-list-admin", UserRole.ADMIN);
        String a = submit(chef, "Chứng nhận Vệ sinh", CertificateType.FOOD_SAFETY);
        submit(chef, "Giấy phép kinh doanh", CertificateType.BUSINESS_LICENSE);
        submit(other, "Chứng nhận khác", CertificateType.FOOD_SAFETY);
        setStatus(admin, a, "ACTIVE", null);

        mockMvc.perform(get("/api/certificates/").header("Authorization", auth(chef)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_rows").value(2))
                .andExpect(jsonPath("$.data.current_page").value(1))
                .andExpect(jsonPath("$.data.page_size").value(50));
        mockMvc.perform(get("/api/certificates/").param("search", "ve sinh").header("Authorization", auth(chef)))
                .andExpect(jsonPath("$.data.total_rows").value(1))
                .andExpect(jsonPath("$.data.content[0].uid").value(a));
        mockMvc.perform(get("/api/certificates/").param("status", "ACTIVE").header("Authorization", auth(chef)))
                .andExpect(jsonPath("$.data.total_rows").value(1));
        mockMvc.perform(get("/api/certificates/").param("status", "all").header("Authorization", auth(chef)))
                .andExpect(jsonPath("$.data.total_rows").value(2));
        mockMvc.perform(get("/api/certificates/").param("categories", "BUSINESS_LICENSE").header("Authorization", auth(chef)))
                .andExpect(jsonPath("$.data.total_rows").value(1));
        mockMvc.perform(get("/api/certificates/").param("page_size", "1").header("Authorization", auth(chef)))
                .andExpect(jsonPath("$.data.total_pages").value(2))
                .andExpect(jsonPath("$.data.content.length()").value(1));

        // admin list: forbidden to chefs, chef_id filter works
        mockMvc.perform(get("/api/certificates/all").header("Authorization", auth(chef)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/certificates/all").param("chef_id", String.valueOf(other.userId()))
                        .header("Authorization", auth(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_rows").value(1));
    }

    @Test
    void softDeleteAndRestore_pendingOnly_adminOnly_hiddenFromLists() throws Exception {
        Account chef = register("cert-del", UserRole.CHEF);
        Account admin = register("cert-del-admin", UserRole.ADMIN);
        String uid = submit(chef, "Để xóa", CertificateType.FOOD_SAFETY);

        mockMvc.perform(patch("/api/certificates/" + uid + "/deleted").header("Authorization", auth(chef)))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/certificates/" + uid + "/deleted").header("Authorization", auth(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
        mockMvc.perform(get("/api/certificates/").header("Authorization", auth(chef)))
                .andExpect(jsonPath("$.data.total_rows").value(0));
        mockMvc.perform(get("/api/certificates/" + uid).header("Authorization", auth(admin)))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/certificates/" + uid + "/restored").header("Authorization", auth(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
        // restoring something that is not deleted is a 200 with data=false
        mockMvc.perform(patch("/api/certificates/" + uid + "/restored").header("Authorization", auth(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(false));
        mockMvc.perform(get("/api/certificates/").header("Authorization", auth(chef)))
                .andExpect(jsonPath("$.data.total_rows").value(1));
    }

    @Test
    void attachments_appendedAtEnd_thenReorderRewritesPositionsByListIndex() throws Exception {
        Account chef = register("cert-att", UserRole.CHEF);
        Account admin = register("cert-att-admin", UserRole.ADMIN);
        String uid = submit(chef, "Có ảnh", CertificateType.FOOD_SAFETY);
        UUID a1 = completedAttachment();
        UUID a2 = completedAttachment();
        certificateService.addAttachments(UUID.fromString(uid), List.of(a1, a2));

        mockMvc.perform(get("/api/certificates/" + uid).header("Authorization", auth(chef)))
                .andExpect(jsonPath("$.data.attachments.length()").value(2))
                .andExpect(jsonPath("$.data.attachments[0].uid").value(a1.toString()))
                .andExpect(jsonPath("$.data.attachments[0].position").value(1))
                .andExpect(jsonPath("$.data.attachments[0].public_url").value("https://cdn.example.test/c.jpg"))
                .andExpect(jsonPath("$.data.attachments[1].position").value(2));

        String reorder = "[{\"attachment_uid\":\"" + a2 + "\",\"position\":99},{\"attachment_uid\":\"" + a1 + "\",\"position\":5}]";
        mockMvc.perform(patch("/api/certificates/" + uid + "/attachments/reorder")
                        .header("Authorization", auth(chef)).contentType("application/json").content(reorder))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/certificates/" + uid + "/attachments/reorder")
                        .header("Authorization", auth(admin)).contentType("application/json").content(reorder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attachments[0].uid").value(a2.toString()))
                .andExpect(jsonPath("$.data.attachments[0].position").value(0))
                .andExpect(jsonPath("$.data.attachments[1].uid").value(a1.toString()))
                .andExpect(jsonPath("$.data.attachments[1].position").value(1));

        // an attachment not on the certificate -> 404 ATTACHMENT_NOT_FOUND
        mockMvc.perform(patch("/api/certificates/" + uid + "/attachments/reorder")
                        .header("Authorization", auth(admin)).contentType("application/json")
                        .content("[{\"attachment_uid\":\"" + UUID.randomUUID() + "\",\"position\":0}]"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message_code").value("ATTACHMENT_NOT_FOUND"));
    }

    @Test
    void profileSeam_reflectsRealApprovedFoodSafetyCertificate() throws Exception {
        Account chef = register("cert-seam", UserRole.CHEF);
        Account admin = register("cert-seam-admin", UserRole.ADMIN);
        mockMvc.perform(post("/api/chef-profiles/").header("Authorization", auth(chef))
                .contentType("application/json").content("{\"bio\":\"hi\"}")).andExpect(status().isOk());

        // no certificate yet
        mockMvc.perform(get("/api/chef-profiles/" + chef.userId()).header("Authorization", auth(chef)))
                .andExpect(jsonPath("$.data.is_food_safety_certified").value(false));

        // a PENDING food-safety certificate and an ACTIVE business licence do not count
        String food = submit(chef, "ATTP", CertificateType.FOOD_SAFETY);
        String biz = submit(chef, "GPKD", CertificateType.BUSINESS_LICENSE);
        setStatus(admin, biz, "ACTIVE", null);
        mockMvc.perform(get("/api/chef-profiles/" + chef.userId()).header("Authorization", auth(chef)))
                .andExpect(jsonPath("$.data.is_food_safety_certified").value(false));

        // admin approves the food-safety certificate -> badge shows, on public + own detail endpoints
        setStatus(admin, food, "ACTIVE", null);
        mockMvc.perform(get("/api/chef-profiles/" + chef.userId()).header("Authorization", auth(chef)))
                .andExpect(jsonPath("$.data.is_food_safety_certified").value(true));
        mockMvc.perform(get("/api/chef-profiles/me").header("Authorization", auth(chef)))
                .andExpect(jsonPath("$.data.is_food_safety_certified").value(true));

        // revoked again -> badge gone
        setStatus(admin, food, "REVOKED", "hết hạn");
        mockMvc.perform(get("/api/chef-profiles/" + chef.userId()).header("Authorization", auth(chef)))
                .andExpect(jsonPath("$.data.is_food_safety_certified").value(false));
    }
}
