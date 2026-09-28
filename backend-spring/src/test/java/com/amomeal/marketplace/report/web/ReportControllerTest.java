package com.amomeal.marketplace.report.web;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.profile.entity.ChefProfile;
import com.amomeal.marketplace.profile.entity.ChefSuspensionLevel;
import com.amomeal.marketplace.report.entity.ChefReport;
import com.amomeal.marketplace.report.entity.ChefSuspension;
import com.amomeal.marketplace.report.entity.ChefWarning;
import com.amomeal.marketplace.report.entity.ReportStatus;
import com.amomeal.marketplace.report.entity.SuspensionStatus;
import com.amomeal.marketplace.report.entity.SuspensionType;
import com.amomeal.marketplace.report.entity.WarningType;
import com.amomeal.marketplace.report.repository.ChefReportRepository;
import com.amomeal.marketplace.report.repository.ChefSuspensionRepository;
import com.amomeal.marketplace.report.repository.ChefWarningRepository;
import com.amomeal.marketplace.report.support.ReportIntegrationTestBase;
import com.amomeal.marketplace.users.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack tests of the report module over real HTTP + real Postgres (Testcontainers) with a FAKE Gemini:
 * create rules, Gemini severity, escalation to WARNING / DISH_LOCK / FULL_LOCK, appeal / lift / reject, role gates.
 */
class ReportControllerTest extends ReportIntegrationTestBase {

    @Autowired ChefReportRepository reportRepository;
    @Autowired ChefSuspensionRepository suspensionRepository;
    @Autowired ChefWarningRepository warningRepository;

    private static final String LONG_TEXT = "Món ăn có vấn đề nghiêm trọng, mình nghi ngờ bị hỏng";

    private String reportBody(Order order, Dish dish, String category) {
        return """
                {"order_uid":"%s",%s"category":"%s","description":"%s"}
                """.formatted(order.getUid(), dish == null ? "" : "\"dish_uid\":\"" + dish.getUid() + "\",", category, LONG_TEXT);
    }

    private JsonNode createOk(Account who, String body) throws Exception {
        return data(call(post("/api/report").contentType("application/json").content(body), who)
                .andExpect(status().isOk()).andExpect(jsonPath("$.error_code").value(0)));
    }

    private ChefReport onlyReportOf(Account customer) {
        List<ChefReport> l = reportRepository.findByReporterIdAndDeletedFalseOrderByCreatedAtDesc(customer.user().getId());
        assertThat(l).hasSize(1);
        return l.get(0);
    }

    // =====================================================================
    // create: happy path + Gemini
    // =====================================================================

    @Test
    void createFoodSafetyReport_geminiCritical_raisesWeight_andShowsUpInBothLists() throws Exception {
        Account chef = chef("rchef1");
        Account customer = register("rcust1", UserRole.CUSTOMER);
        Dish dish = dish(chef, "Pho");
        Order order = completedOrder(customer, dish);
        geminiSays("CRITICAL", true);

        JsonNode created = createOk(customer, reportBody(order, dish, "FOOD_SAFETY"));

        assertThat(created.get("order_id").asString()).isEqualTo(order.getUid().toString());
        assertThat(created.get("dish_uid").asString()).isEqualTo(dish.getUid().toString());
        assertThat(created.get("chef_id").asLong()).isEqualTo(chef.user().getId());
        assertThat(created.get("category").asString()).isEqualTo("FOOD_SAFETY");
        assertThat(created.get("status").asString()).isEqualTo("PENDING");
        assertThat(created.get("ai_severity").asString()).isEqualTo("CRITICAL");
        assertThat(created.get("ai_food_safety_risk").asBoolean()).isTrue();
        assertThat(created.get("ai_severity_reason").asString()).isEqualTo("test");
        assertThat(created.get("credibility_weight").asDouble()).isEqualTo(3.0); // 1.5 (text) -> AI confirmed 3.0
        assertThat(gemini.calls()).hasSize(1);
        assertThat(gemini.calls().get(0).prompt()).contains("Danh mục phản ánh: FOOD_SAFETY").contains(LONG_TEXT);

        ChefReport saved = onlyReportOf(customer);
        assertThat(saved.getAiAnalyzedAt()).isNotNull();

        JsonNode mine = data(call(get("/api/report/my-reports"), customer).andExpect(status().isOk()));
        assertThat(mine).hasSize(1);
        JsonNode aboutChef = data(call(get("/api/report"), chef).andExpect(status().isOk()));
        assertThat(aboutChef).hasSize(1);
        assertThat(aboutChef.get(0).get("uid").asString()).isEqualTo(created.get("uid").asString());
    }

    @Test
    void geminiFailure_fallsBackToLow_reportStillCreated_weightUnchanged() throws Exception {
        Account chef = chef("rchef2");
        Account customer = register("rcust2", UserRole.CUSTOMER);
        Dish dish = dish(chef, "Bun");
        Order order = completedOrder(customer, dish);
        gemini.respondWith(p -> {
            throw new IllegalStateException("gemini down");
        });

        JsonNode created = createOk(customer, reportBody(order, null, "HYGIENE"));

        assertThat(created.get("ai_severity").asString()).isEqualTo("LOW");
        assertThat(created.get("ai_food_safety_risk").asBoolean()).isFalse();
        assertThat(created.get("ai_severity_reason").asString()).isEqualTo("Không thể phân tích tự động.");
        assertThat(created.get("credibility_weight").asDouble()).isEqualTo(1.5);
    }

    // =====================================================================
    // create: rules
    // =====================================================================

    @Test
    void duplicateReportForSameOrderAndDish_is409() throws Exception {
        Account chef = chef("rchef3");
        Account customer = register("rcust3", UserRole.CUSTOMER);
        Dish dish = dish(chef, "Com");
        Order order = completedOrder(customer, dish);
        geminiSays("LOW", false);

        createOk(customer, reportBody(order, dish, "FOOD_QUALITY"));

        call(post("/api/report").contentType("application/json").content(reportBody(order, dish, "FOOD_QUALITY")), customer)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message_code").value("REPORT_ALREADY_EXISTS"));
        assertThat(reportRepository.findByReporterIdAndDeletedFalseOrderByCreatedAtDesc(customer.user().getId())).hasSize(1);
    }

    @Test
    void reportingSomeoneElsesOrder_is403_andNotCompletedOrder_is400() throws Exception {
        Account chef = chef("rchef4");
        Account owner = register("rowner4", UserRole.CUSTOMER);
        Account stranger = register("rstranger4", UserRole.CUSTOMER);
        Dish dish = dish(chef, "Mi");
        Order completed = completedOrder(owner, dish);
        Order pending = orders(stranger, dish, 1, OrderStatus.PENDING).get(0);

        call(post("/api/report").contentType("application/json").content(reportBody(completed, null, "FOOD_SAFETY")), stranger)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message_code").value("REPORT_ORDER_NOT_OWNED"));
        call(post("/api/report").contentType("application/json").content(reportBody(pending, null, "FOOD_SAFETY")), stranger)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message_code").value("REPORT_ORDER_NOT_COMPLETED"));
        assertThat(reportRepository.findByChefIdAndDeletedFalseOrderByCreatedAtDesc(chef.user().getId())).isEmpty();
    }

    @Test
    void unknownOrder_is404_andSchemaValidationFailuresAre401() throws Exception {
        Account customer = register("rcust5", UserRole.CUSTOMER);

        call(post("/api/report").contentType("application/json").content("""
                {"order_uid":"%s","category":"FOOD_SAFETY","description":"%s"}
                """.formatted(UUID.randomUUID(), LONG_TEXT)), customer)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message_code").value("REPORT_TARGET_NOT_FOUND"));

        // order-required category without order_uid; description too short -> project-wide 401 VALIDATION_ERROR
        call(post("/api/report").contentType("application/json").content("""
                {"category":"FOOD_SAFETY","chef_id":1,"description":"%s"}
                """.formatted(LONG_TEXT)), customer)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message_code").value("VALIDATION_ERROR"));
        call(post("/api/report").contentType("application/json").content("""
                {"order_uid":"%s","category":"FOOD_SAFETY","description":"ngắn"}
                """.formatted(UUID.randomUUID())), customer)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message_code").value("VALIDATION_ERROR"));
    }

    @Test
    void rateLimit_sixthReportWithin24Hours_is429() throws Exception {
        Account chef = chef("rchef6");
        Account customer = register("rcust6", UserRole.CUSTOMER);
        Dish dish = dish(chef, "Banh");
        List<Order> orders = orders(customer, dish, 6, OrderStatus.COMPLETED);
        geminiSays("LOW", false);

        for (int i = 0; i < 5; i++) {
            createOk(customer, reportBody(orders.get(i), null, "FOOD_QUALITY"));
        }
        call(post("/api/report").contentType("application/json").content(reportBody(orders.get(5), null, "FOOD_QUALITY")), customer)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message_code").value("REPORT_RATE_LIMIT"));
        assertThat(reportRepository.findByReporterIdAndDeletedFalseOrderByCreatedAtDesc(customer.user().getId())).hasSize(5);
    }

    @Test
    void platformReport_needsEvidence_thenIsRecordedWithoutGeminiOrAnalysis() throws Exception {
        Account chef = chef("rchef7");
        Account customer = register("rcust7", UserRole.CUSTOMER);

        // missing evidence_uid -> schema validation (401)
        call(post("/api/report").contentType("application/json").content("""
                {"chef_id":%d,"category":"INAPPROPRIATE","description":"%s"}
                """.formatted(chef.user().getId(), LONG_TEXT)), customer)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message_code").value("VALIDATION_ERROR"));
        // evidence_uid that does not exist -> service REPORT_EVIDENCE_REQUIRED (400)
        call(post("/api/report").contentType("application/json").content("""
                {"chef_id":%d,"category":"INAPPROPRIATE","description":"%s","evidence_uid":"%s"}
                """.formatted(chef.user().getId(), LONG_TEXT, UUID.randomUUID())), customer)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message_code").value("REPORT_EVIDENCE_REQUIRED"));

        JsonNode created = createOk(customer, """
                {"chef_id":%d,"category":"INAPPROPRIATE","description":"%s","evidence_uid":"%s"}
                """.formatted(chef.user().getId(), LONG_TEXT, evidence().getUid()));

        assertThat(created.get("credibility_weight").asDouble()).isEqualTo(2.0); // has evidence
        assertThat(created.get("order_id").isNull()).isTrue();
        assertThat(created.get("ai_severity").isNull()).isTrue();
        assertThat(gemini.calls()).isEmpty();
        assertThat(warningRepository.findAll().stream().filter(w -> w.getChef().getId().equals(chef.user().getId()))).isEmpty();

        // a second identical platform report (no order, no dish) is the duplicate case (NULL == NULL in Django's filter)
        call(post("/api/report").contentType("application/json").content("""
                {"chef_id":%d,"category":"INAPPROPRIATE","description":"%s","evidence_uid":"%s"}
                """.formatted(chef.user().getId(), LONG_TEXT, evidence().getUid())), customer)
                .andExpect(status().isConflict());
    }

    @Test
    void financialReportWithOrder_recordsAFinancialWarning_withOrderUuidAsString() throws Exception {
        Account chef = chef("rchef8");
        Account customer = register("rcust8", UserRole.CUSTOMER);
        Dish dish = dish(chef, "Che");
        Order order = completedOrder(customer, dish);

        JsonNode created = createOk(customer, reportBody(order, null, "PAYMENT_ISSUE"));

        assertThat(created.get("order_id").asString()).isEqualTo(order.getUid().toString());
        List<ChefWarning> warnings = warningRepository.findAll().stream()
                .filter(w -> w.getChef().getId().equals(chef.user().getId())).toList();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getWarningType()).isEqualTo(WarningType.FINANCIAL);
        assertThat(warnings.get(0).getMetricsSnapshot()).containsEntry("order_id", order.getUid().toString())
                .containsEntry("report_uid", created.get("uid").asString());
        assertThat(warnings.get(0).isEmailSent()).isTrue(); // Django: helpers swallow every failure -> always true
        assertThat(gemini.calls()).isEmpty(); // not a food-quality category
    }

    // =====================================================================
    // role gates
    // =====================================================================

    @Test
    void roleGates_matchDjangoGroups() throws Exception {
        Account chef = chef("rchef9");
        Account customer = register("rcust9", UserRole.CUSTOMER);
        Account admin = register("radmin9", UserRole.ADMIN);
        Dish dish = dish(chef, "Xoi");
        Order order = completedOrder(customer, dish);

        // an ADMIN-only user cannot report (customer-or-chef) nor read my-reports
        call(post("/api/report").contentType("application/json").content(reportBody(order, null, "FOOD_SAFETY")), admin)
                .andExpect(status().isUnauthorized());
        call(get("/api/report/my-reports"), admin).andExpect(status().isUnauthorized());
        // chef-only endpoints reject a customer and an admin
        call(get("/api/report"), customer).andExpect(status().isUnauthorized());
        call(get("/api/report/suspension/current"), customer).andExpect(status().isUnauthorized());
        call(get("/api/report/suspension/history"), admin).andExpect(status().isUnauthorized());
        // admin endpoints reject customer and chef
        call(get("/api/admin/reports"), customer).andExpect(status().isUnauthorized());
        call(get("/api/admin/reports/suspensions"), chef).andExpect(status().isUnauthorized());
        call(patch("/api/admin/reports/" + UUID.randomUUID() + "/confirm"), chef).andExpect(status().isUnauthorized());
        // a CHEF may also file reports (customer-or-chef)
        geminiSays("LOW", false);
        Account otherChef = chef("rchef9b");
        Order chefsOrder = completedOrder(otherChef, dish);
        createOk(otherChef, reportBody(chefsOrder, null, "FOOD_QUALITY"));
    }

    // =====================================================================
    // escalation
    // =====================================================================

    @Test
    void escalation_warningThenFullLock_thenAppealAndLift_endToEnd() throws Exception {
        Account chef = chef("rchefF");
        Account admin = register("radminF", UserRole.ADMIN);
        Dish dish = dish(chef, "Lau");
        List<Account> customers = new ArrayList<>();
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Account c = register("rcustF" + i, UserRole.CUSTOMER);
            customers.add(c);
            orders.addAll(orders(c, dish, 10, OrderStatus.COMPLETED)); // 50 completed orders in the window
        }
        geminiSays("CRITICAL", true); // every report weighs 3.0 -> ratio far above the thresholds

        // first report: combined ratio 3/50 = 6% >= 4% -> WARNING (one, deduped for 24h afterwards)
        createOk(customers.get(0), reportBody(orders.get(0), null, "FOOD_SAFETY"));
        List<ChefWarning> warnings = warningRepository.findAll().stream()
                .filter(w -> w.getChef().getId().equals(chef.user().getId())).toList();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getWarningType()).isEqualTo(WarningType.FOOD_QUALITY);
        assertThat(warnings.get(0).getMetricsSnapshot()).containsEntry("chef_report_count", 1);
        assertThat(warnings.get(0).isEmailSent()).isTrue();
        assertThat(suspensionRepository.findByChefIdOrderByCreatedAtDescIdDesc(chef.user().getId())).isEmpty();

        // reports 2..9 (5 reporters, still < 10 reports): still only the deduped warning, no suspension
        int[][] next = {{1, 10}, {2, 20}, {3, 30}, {4, 40}, {0, 1}, {1, 11}, {2, 21}, {3, 31}};
        for (int[] n : next) {
            createOk(customers.get(n[0]), reportBody(orders.get(n[1]), null, "FOOD_SAFETY"));
        }
        assertThat(suspensionRepository.findByChefIdOrderByCreatedAtDescIdDesc(chef.user().getId())).isEmpty();
        assertThat(warningRepository.findAll().stream().filter(w -> w.getChef().getId().equals(chef.user().getId()))).hasSize(1);

        // 10th report -> >= 10 reports from >= 5 reporters, ratio >= 8% -> FULL_LOCK
        createOk(customers.get(4), reportBody(orders.get(41), null, "FOOD_SAFETY"));

        List<ChefSuspension> suspensions = suspensionRepository.findByChefIdOrderByCreatedAtDescIdDesc(chef.user().getId());
        assertThat(suspensions).hasSize(1);
        ChefSuspension lock = suspensions.get(0);
        assertThat(lock.getSuspensionType()).isEqualTo(SuspensionType.FULL_LOCK);
        assertThat(lock.getStatus()).isEqualTo(SuspensionStatus.ACTIVE);
        assertThat(lock.getTriggerData()).containsEntry("chef_report_count", 10).containsEntry("unique_reporters", 5)
                .containsEntry("completed_orders", 50);
        assertThat(lock.getReason()).contains("vượt ngưỡng 8%");
        ChefProfile profile = chefProfileRepository.findByUserId(chef.user().getId()).orElseThrow();
        assertThat(profile.isAcceptingOrders()).isFalse();
        assertThat(profile.getSuspensionLevel()).isEqualTo(ChefSuspensionLevel.SUSPENDED);

        // never downgrade / no duplicate: an 11th report does not add a suspension
        createOk(customers.get(3), reportBody(orders.get(32), null, "FOOD_SAFETY"));
        assertThat(suspensionRepository.findByChefIdOrderByCreatedAtDescIdDesc(chef.user().getId())).hasSize(1);

        // chef sees the lock
        JsonNode current = data(call(get("/api/report/suspension/current"), chef).andExpect(status().isOk()));
        assertThat(current.get("is_accepting_orders").asBoolean()).isFalse();
        assertThat(current.get("suspension_level").asString()).isEqualTo("SUSPENDED");
        assertThat(current.get("active_suspension").get("uid").asString()).isEqualTo(lock.getUid().toString());
        assertThat(current.get("active_suspension").get("suspension_type").asString()).isEqualTo("FULL_LOCK");
        assertThat(current.get("active_suspension").get("trigger_source").asString()).isEqualTo("SYSTEM");

        // appeal: too short -> validation; then ok -> APPEALING; second appeal -> APPEAL_NOT_ALLOWED
        String appealUrl = "/api/report/suspension/" + lock.getUid() + "/appeal";
        call(post(appealUrl).contentType("application/json").content("{\"appeal_text\":\"ngắn quá\"}"), chef)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message_code").value("VALIDATION_ERROR"));
        JsonNode appealed = data(call(post(appealUrl).contentType("application/json")
                .content("{\"appeal_text\":\"  Chúng tôi đã khắc phục toàn bộ quy trình bảo quản.  \"}"), chef)
                .andExpect(status().isOk()));
        assertThat(appealed.get("status").asString()).isEqualTo("APPEALING");
        assertThat(appealed.get("appeal_text").asString()).isEqualTo("Chúng tôi đã khắc phục toàn bộ quy trình bảo quản.");
        assertThat(appealed.get("appealed_at").isNull()).isFalse();
        call(post(appealUrl).contentType("application/json").content("{\"appeal_text\":\"Lần hai, xin xem xét lại giúp.\"}"), chef)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message_code").value("APPEAL_NOT_ALLOWED"));
        // another chef's suspension is not found for me
        Account otherChef = chef("rchefF2");
        call(post(appealUrl).contentType("application/json").content("{\"appeal_text\":\"Tôi không phải chủ lệnh khóa này.\"}"), otherChef)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message_code").value("SUSPENSION_NOT_FOUND"));

        // admin: filter suspensions by status, paginated envelope
        JsonNode page = data(call(get("/api/admin/reports/suspensions?status=APPEALING&chef_id=" + chef.user().getId()), admin)
                .andExpect(status().isOk()));
        assertThat(page.get("content")).hasSize(1);
        assertThat(page.get("total_rows").asLong()).isEqualTo(1);
        assertThat(page.get("total_pages").asInt()).isEqualTo(1);
        assertThat(page.get("current_page").asInt()).isEqualTo(1);
        assertThat(page.get("page_size").asInt()).isEqualTo(50);

        // lift -> LIFTED, profile restored, history keeps it
        JsonNode lifted = data(call(patch("/api/admin/reports/suspensions/" + lock.getUid() + "/lift")
                .contentType("application/json").content("{\"lift_note\":\"đã kiểm tra bếp\"}"), admin)
                .andExpect(status().isOk()));
        assertThat(lifted.get("status").asString()).isEqualTo("LIFTED");
        assertThat(lifted.get("lift_note").asString()).isEqualTo("đã kiểm tra bếp");
        assertThat(lifted.get("lifted_at").isNull()).isFalse();
        profile = chefProfileRepository.findByUserId(chef.user().getId()).orElseThrow();
        assertThat(profile.isAcceptingOrders()).isTrue();
        assertThat(profile.getSuspensionLevel()).isEqualTo(ChefSuspensionLevel.NONE);
        JsonNode history = data(call(get("/api/report/suspension/history"), chef).andExpect(status().isOk()));
        assertThat(history).hasSize(1);
        assertThat(data(call(get("/api/report/suspension/current"), chef)).get("active_suspension").isNull()).isTrue();
        // lifting again is not allowed
        call(patch("/api/admin/reports/suspensions/" + lock.getUid() + "/lift").contentType("application/json").content("{}"), admin)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message_code").value("APPEAL_NOT_ALLOWED"));
    }

    @Test
    void escalation_dishLock_onThirdReportForTheSameDish_thenLiftUnsuspendsIt() throws Exception {
        Account chef = chef("rchefD");
        Account admin = register("radminD", UserRole.ADMIN);
        Dish dish = dish(chef, "Cha");
        List<Account> customers = new ArrayList<>();
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Account c = register("rcustD" + i, UserRole.CUSTOMER);
            customers.add(c);
            orders.addAll(orders(c, dish, i == 2 ? 16 : 17, OrderStatus.COMPLETED)); // 50 completed, 50 with the dish
        }
        geminiSays("HIGH", true);

        createOk(customers.get(0), reportBody(orders.get(0), dish, "FOOD_QUALITY"));
        createOk(customers.get(1), reportBody(orders.get(17), dish, "FOOD_QUALITY"));
        assertThat(suspensionRepository.findByChefIdOrderByCreatedAtDescIdDesc(chef.user().getId())).isEmpty();
        assertThat(dishRepository.findByUid(dish.getUid()).orElseThrow().isSuspended()).isFalse();

        createOk(customers.get(2), reportBody(orders.get(34), dish, "FOOD_QUALITY"));

        List<ChefSuspension> suspensions = suspensionRepository.findByChefIdOrderByCreatedAtDescIdDesc(chef.user().getId());
        assertThat(suspensions).hasSize(1);
        ChefSuspension lock = suspensions.get(0);
        assertThat(lock.getSuspensionType()).isEqualTo(SuspensionType.DISH_LOCK);
        assertThat(lock.getLockedDishUid()).isEqualTo(dish.getUid());
        assertThat(lock.getTriggerData()).containsEntry("dish_report_count", 3).containsEntry("dish_orders", 50);
        assertThat(dishRepository.findByUid(dish.getUid()).orElseThrow().isSuspended()).isTrue();
        // a dish lock does not stop the chef accepting orders
        assertThat(chefProfileRepository.findByUserId(chef.user().getId()).orElseThrow().isAcceptingOrders()).isTrue();

        JsonNode lifted = data(call(patch("/api/admin/reports/suspensions/" + lock.getUid() + "/lift")
                .contentType("application/json").content("{}"), admin).andExpect(status().isOk()));
        assertThat(lifted.get("locked_dish_uid").asString()).isEqualTo(dish.getUid().toString());
        assertThat(dishRepository.findByUid(dish.getUid()).orElseThrow().isSuspended()).isFalse();
    }

    // =====================================================================
    // admin
    // =====================================================================

    @Test
    void admin_dismissAndConfirmReports_listWithFilters_andUnknownUids() throws Exception {
        Account chef = chef("rchefA");
        Account admin = register("radminA", UserRole.ADMIN);
        Account c1 = register("rcustA1", UserRole.CUSTOMER);
        Account c2 = register("rcustA2", UserRole.CUSTOMER);
        Dish dish = dish(chef, "Goi");
        geminiSays("LOW", false);
        JsonNode r1 = createOk(c1, reportBody(completedOrder(c1, dish), null, "FOOD_QUALITY"));
        JsonNode r2 = createOk(c2, reportBody(completedOrder(c2, dish), null, "HYGIENE"));

        JsonNode dismissed = data(call(patch("/api/admin/reports/" + r1.get("uid").asString() + "/dismiss")
                .contentType("application/json").content("{\"admin_note\":\"spam\"}"), admin).andExpect(status().isOk()));
        assertThat(dismissed.get("status").asString()).isEqualTo("DISMISSED");
        assertThat(dismissed.get("admin_note").asString()).isEqualTo("spam");
        JsonNode confirmed = data(call(patch("/api/admin/reports/" + r2.get("uid").asString() + "/confirm"), admin)
                .andExpect(status().isOk()));
        assertThat(confirmed.get("status").asString()).isEqualTo("REVIEWED");
        assertThat(confirmed.get("credibility_weight").asDouble()).isEqualTo(5.0);
        ChefReport stored = reportRepository.findByUid(UUID.fromString(r2.get("uid").asString())).orElseThrow();
        assertThat(stored.getReviewedBy().getId()).isEqualTo(admin.user().getId());
        assertThat(stored.getStatus()).isEqualTo(ReportStatus.REVIEWED);

        JsonNode all = data(call(get("/api/admin/reports?chef_id=" + chef.user().getId()), admin).andExpect(status().isOk()));
        assertThat(all.get("total_rows").asLong()).isEqualTo(2);
        JsonNode dismissedOnly = data(call(get("/api/admin/reports?chef_id=" + chef.user().getId() + "&status=DISMISSED"), admin));
        assertThat(dismissedOnly.get("content")).hasSize(1);
        JsonNode hygieneOnly = data(call(get("/api/admin/reports?chef_id=" + chef.user().getId() + "&category=HYGIENE"), admin));
        assertThat(hygieneOnly.get("content")).hasSize(1);
        JsonNode bogus = data(call(get("/api/admin/reports?status=NOPE"), admin).andExpect(status().isOk()));
        assertThat(bogus.get("content")).isEmpty();
        assertThat(bogus.get("total_pages").asInt()).isEqualTo(1);
        JsonNode paged = data(call(get("/api/admin/reports?chef_id=" + chef.user().getId() + "&page=2&page_size=1"), admin));
        assertThat(paged.get("content")).hasSize(1);
        assertThat(paged.get("current_page").asInt()).isEqualTo(2);
        assertThat(paged.get("total_pages").asInt()).isEqualTo(2);
        JsonNode beyond = data(call(get("/api/admin/reports?chef_id=" + chef.user().getId() + "&page=3&page_size=1"), admin));
        assertThat(beyond.get("content")).isEmpty();

        call(patch("/api/admin/reports/" + UUID.randomUUID() + "/dismiss").contentType("application/json").content("{}"), admin)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message_code").value("REPORT_NOT_FOUND"));
        call(patch("/api/admin/reports/" + UUID.randomUUID() + "/confirm"), admin)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message_code").value("REPORT_NOT_FOUND"));
        call(patch("/api/admin/reports/suspensions/" + UUID.randomUUID() + "/lift").contentType("application/json").content("{}"), admin)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message_code").value("SUSPENSION_NOT_FOUND"));
    }

    @Test
    void admin_manualSuspension_fullLock_thenAppealRejected_canStillBeLifted() throws Exception {
        Account chef = chef("rchefM");
        Account admin = register("radminM", UserRole.ADMIN);

        // FULL_LOCK must not carry a dish_uid; DISH_LOCK must
        call(post("/api/admin/reports/suspensions/manual").contentType("application/json").content("""
                {"chef_id":%d,"suspension_type":"FULL_LOCK","dish_uid":"%s","reason":"x"}
                """.formatted(chef.user().getId(), UUID.randomUUID())), admin).andExpect(status().isUnauthorized());
        call(post("/api/admin/reports/suspensions/manual").contentType("application/json").content("""
                {"chef_id":%d,"suspension_type":"DISH_LOCK","reason":"x"}
                """.formatted(chef.user().getId())), admin).andExpect(status().isUnauthorized());

        JsonNode s = data(call(post("/api/admin/reports/suspensions/manual").contentType("application/json").content("""
                {"chef_id":%d,"suspension_type":"FULL_LOCK","reason":"  vi phạm nghiêm trọng  "}
                """.formatted(chef.user().getId())), admin).andExpect(status().isOk()));
        assertThat(s.get("trigger_source").asString()).isEqualTo("ADMIN");
        assertThat(s.get("reason").asString()).isEqualTo("vi phạm nghiêm trọng");
        assertThat(s.get("trigger_data").get("admin_id").asLong()).isEqualTo(admin.user().getId());
        assertThat(chefProfileRepository.findByUserId(chef.user().getId()).orElseThrow().isAcceptingOrders()).isFalse();

        // reject-appeal only works on an APPEALING suspension
        String uid = s.get("uid").asString();
        call(patch("/api/admin/reports/suspensions/" + uid + "/reject-appeal").contentType("application/json").content("{}"), admin)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message_code").value("APPEAL_NOT_ALLOWED"));
        call(post("/api/report/suspension/" + uid + "/appeal").contentType("application/json")
                .content("{\"appeal_text\":\"Xin xem xét lại quyết định khóa bếp.\"}"), chef).andExpect(status().isOk());
        JsonNode rejected = data(call(patch("/api/admin/reports/suspensions/" + uid + "/reject-appeal")
                .contentType("application/json").content("{\"lift_note\":\"không đạt\"}"), admin).andExpect(status().isOk()));
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("lift_note").asString()).isEqualTo("không đạt");

        // Post-port fix: a REJECTED suspension is not a dead end - admin can still lift it, which restores the chef
        JsonNode current = data(call(get("/api/report/suspension/current"), chef).andExpect(status().isOk()));
        assertThat(current.get("active_suspension").isNull()).isTrue();
        assertThat(current.get("is_accepting_orders").asBoolean()).isFalse();
        JsonNode lifted = data(call(patch("/api/admin/reports/suspensions/" + uid + "/lift").contentType("application/json")
                .content("{}"), admin).andExpect(status().isOk()));
        assertThat(lifted.get("status").asString()).isEqualTo("LIFTED");
        assertThat(chefProfileRepository.findByUserId(chef.user().getId()).orElseThrow().isAcceptingOrders()).isTrue();
        // ... but an already-LIFTED one still can't be lifted twice
        call(patch("/api/admin/reports/suspensions/" + uid + "/lift").contentType("application/json").content("{}"), admin)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message_code").value("APPEAL_NOT_ALLOWED"));
    }

    @Test
    void admin_manualDishLock_flagsDish_andUnknownDishIsA500() throws Exception {
        Account chef = chef("rchefM2");
        Account admin = register("radminM2", UserRole.ADMIN);
        Dish dish = dish(chef, "Banh Xeo");

        JsonNode s = data(call(post("/api/admin/reports/suspensions/manual").contentType("application/json").content("""
                {"chef_id":%d,"suspension_type":"DISH_LOCK","dish_uid":"%s","reason":"món có vấn đề"}
                """.formatted(chef.user().getId(), dish.getUid())), admin).andExpect(status().isOk()));
        assertThat(s.get("locked_dish_uid").asString()).isEqualTo(dish.getUid().toString());
        assertThat(dishRepository.findByUid(dish.getUid()).orElseThrow().isSuspended()).isTrue();

        call(post("/api/admin/reports/suspensions/manual").contentType("application/json").content("""
                {"chef_id":%d,"suspension_type":"DISH_LOCK","dish_uid":"%s","reason":"x"}
                """.formatted(chef.user().getId(), UUID.randomUUID())), admin).andExpect(status().isInternalServerError());
    }
}
