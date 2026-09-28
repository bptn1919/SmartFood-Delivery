package com.amomeal.marketplace.report.web;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.report.repository.ChefReportRepository;
import com.amomeal.marketplace.report.repository.ChefWarningRepository;
import com.amomeal.marketplace.report.support.ReportIntegrationTestBase;
import com.amomeal.marketplace.users.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code app.report.preserve-order-uuid-bug=true} reproduces Django exactly: {@code ReportSchema.order_id} is
 * {@code Optional[int]} but Order's pk is a UUID, so any report with an order 500s on serialization - on POST
 * AFTER the report was saved and analysed - and on every list that contains one. Also the financial handler
 * crashes on the UUID inside its JSONField.
 */
@TestPropertySource(properties = "app.report.preserve-order-uuid-bug=true")
class ReportOrderUuidBugPreservedTest extends ReportIntegrationTestBase {

    @Autowired ChefReportRepository reportRepository;
    @Autowired ChefWarningRepository warningRepository;

    @Test
    void createWithOrder_is500_butTheReportWasAlreadySaved_andListsCrashToo() throws Exception {
        Account chef = chef("pchef1");
        Account customer = register("pcust1", UserRole.CUSTOMER);
        Dish dish = dish(chef, "Pho");
        Order order = completedOrder(customer, dish);
        geminiSays("LOW", false);

        call(post("/api/report").contentType("application/json").content("""
                {"order_uid":"%s","category":"FOOD_QUALITY","description":"Món ăn có vấn đề nghiêm trọng, mình nghi ngờ"}
                """.formatted(order.getUid())), customer)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message_code").value("CONTACT_ADMIN_FOR_SUPPORT"));

        // persisted before the response failed (Django commits create_report first)
        assertThat(reportRepository.findByReporterIdAndDeletedFalseOrderByCreatedAtDesc(customer.user().getId())).hasSize(1);
        call(get("/api/report/my-reports"), customer).andExpect(status().isInternalServerError());
        call(get("/api/report"), chef).andExpect(status().isInternalServerError());
    }

    @Test
    void financialReportWithOrder_crashesStoringTheUuid_butReportAndAiAreSaved() throws Exception {
        Account chef = chef("pchef2");
        Account customer = register("pcust2", UserRole.CUSTOMER);
        Dish dish = dish(chef, "Bun");
        Order order = completedOrder(customer, dish);

        call(post("/api/report").contentType("application/json").content("""
                {"order_uid":"%s","category":"PAYMENT_ISSUE","description":"Bị trừ tiền hai lần cho cùng một đơn"}
                """.formatted(order.getUid())), customer)
                .andExpect(status().isInternalServerError());

        assertThat(reportRepository.findByReporterIdAndDeletedFalseOrderByCreatedAtDesc(customer.user().getId())).hasSize(1);
        assertThat(warningRepository.findAll().stream().filter(w -> w.getChef().getId().equals(chef.user().getId()))).isEmpty();
    }

    @Test
    void reportsWithoutAnOrder_stillSerialize() throws Exception {
        Account chef = chef("pchef3");
        Account customer = register("pcust3", UserRole.CUSTOMER);

        call(post("/api/report").contentType("application/json").content("""
                {"chef_id":%d,"category":"INAPPROPRIATE","description":"Nội dung phản cảm trong mô tả bếp","evidence_uid":"%s"}
                """.formatted(chef.user().getId(), evidence().getUid())), customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_id").value(org.hamcrest.Matchers.nullValue()));
    }
}
