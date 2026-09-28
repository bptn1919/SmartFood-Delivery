package com.amomeal.marketplace.report.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * report module settings.
 *
 * <ul>
 *   <li>{@code preserveOrderUuidBug} - Django's {@code ReportSchema.order_id: Optional[int]} but
 *       {@code Order}'s primary key is a UUID ({@code BaseModel.uid}), so pydantic cannot serialise ANY report that has
 *       an order (POST /report answers 500 AFTER saving the report; my-reports / chef list / admin list 500 as soon
 *       as one row has an order). Same root cause makes {@code _handle_financial_report} crash (a UUID inside the
 *       JSONField {@code metrics_snapshot}) for PAYMENT_ISSUE/REFUND_ISSUE. Default {@code false} = the WORKING
 *       behaviour ({@code order_id} serialised as the order UUID string, snapshot stores the string);
 *       {@code true} reproduces Django (500s). CLAUDE.md 0.6.</li>
 *   <li>{@code adminAlertEmail} - Django {@code settings.ADMIN_ALERT_EMAIL} (financial-report alerts; blank = no
 *       admin email, like Django's {@code getattr(..., None)}).</li>
 * </ul>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.report")
public class ReportProperties {

    private boolean preserveOrderUuidBug = false;
    private String adminAlertEmail = "";
}
