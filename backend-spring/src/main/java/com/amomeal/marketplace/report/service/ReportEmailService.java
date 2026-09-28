package com.amomeal.marketplace.report.service;

import com.amomeal.marketplace.order.service.OrderMapper;
import com.amomeal.marketplace.report.config.ReportProperties;
import com.amomeal.marketplace.report.entity.ChefReport;
import com.amomeal.marketplace.report.entity.ChefSuspension;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Port of report/services/report_service.py's {@code _send_*_email} helpers + the report emails of
 * utils/services/email/template.py. Same plain-text simplification as the other modules' email services
 * (subjects verbatim, no Django HTML templates).
 *
 * <p><b>Every method swallows and logs all failures</b> - like Django, where each helper wraps its whole body in
 * try/except and {@code EmailClient.send} swallows SMTP errors - so callers can never fail because of email. (Also
 * means {@code ChefWarning.email_sent} is set true even when the mail could not be delivered; preserved.)
 */
@Slf4j
@Service
public class ReportEmailService {

    private final CustomUserRepository userRepository;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final ReportProperties properties;
    private final String frontendUrl;

    public ReportEmailService(CustomUserRepository userRepository, ObjectProvider<JavaMailSender> mailSenderProvider,
                              ReportProperties properties, @Value("${app.frontend-url:}") String frontendUrl) {
        this.userRepository = userRepository;
        this.mailSenderProvider = mailSenderProvider;
        this.properties = properties;
        this.frontendUrl = frontendUrl;
    }

    public void sendFullLock(Long chefId, ChefSuspension suspension) {
        withChef(chefId, "full_lock", chef -> send(chef.getEmail(),
                "Tài khoản Chef bị tạm khóa / Chef account suspended",
                greeting(chef) + "Tài khoản Chef của bạn đã bị tạm khóa nhận đơn.\n\nLý do: " + suspension.getReason()
                        + "\n\nDữ liệu phân tích: " + suspension.getTriggerData()
                        + "\n\nGửi giải trình: " + appealUrl(suspension) + "\n"));
    }

    public void sendDishLock(Long chefId, String dishName, ChefSuspension suspension) {
        String name = dishName != null ? dishName : "món ăn";
        withChef(chefId, "dish_lock", chef -> send(chef.getEmail(),
                "Món ăn '" + name + "' bị tạm khóa / Dish suspended",
                greeting(chef) + "Món ăn '" + name + "' của bạn đã bị tạm khóa.\n\nLý do: " + suspension.getReason()
                        + "\n\nDữ liệu phân tích: " + suspension.getTriggerData()
                        + "\n\nGửi giải trình: " + appealUrl(suspension) + "\n"));
    }

    public void sendWarning(Long chefId, String dishName, String reason) {
        withChef(chefId, "warning", chef -> send(chef.getEmail(),
                "Cảnh báo chất lượng thực phẩm / Food quality warning",
                greeting(chef) + (dishName != null ? "Món ăn: " + dishName + "\n" : "") + reason + "\n"));
    }

    public void sendLifted(Long chefId, ChefSuspension suspension, String dishName) {
        withChef(chefId, "lift", chef -> send(chef.getEmail(),
                "Tài khoản đã được mở khóa / Account unlocked",
                greeting(chef) + "Lệnh khóa (" + suspension.getSuspensionType() + ")"
                        + (dishName != null ? " món " + dishName : "") + " đã được gỡ.\n"
                        + (suspension.getLiftNote() != null ? "Ghi chú: " + suspension.getLiftNote() + "\n" : "")));
    }

    public void sendDeliveryWarning(Long chefId, String reason, Map<String, Object> metrics, boolean adminAlert) {
        String subject = adminAlert
                ? "Cảnh báo nghiêm trọng: Tỷ lệ giao hàng sai/thiếu cao / Delivery issue alert"
                : "Cảnh báo giao hàng / Delivery warning";
        withChef(chefId, "delivery warning", chef -> send(chef.getEmail(), subject,
                greeting(chef) + reason + "\n\nDữ liệu: " + metrics + "\n"));
    }

    /** Django: chef ack email + (only when ADMIN_ALERT_EMAIL is configured) the admin alert. */
    public void sendFinancialAlerts(Long chefId, ChefReport report, String orderId) {
        withChef(chefId, "financial alert", chef -> {
            send(chef.getEmail(), "Phản ánh tài chính đã được ghi nhận / Financial report received",
                    greeting(chef) + "Phản ánh " + report.getUid() + (orderId != null ? " (đơn " + orderId + ")" : "")
                            + " đã được ghi nhận, chúng tôi sẽ liên hệ trong 1-2 ngày.\n");
            String adminEmail = properties.getAdminAlertEmail();
            if (adminEmail != null && !adminEmail.isBlank()) {
                send(adminEmail, "[ADMIN] Báo cáo tài chính cần xem xét — order #" + orderId,
                        "Chef: " + chef.getEmail() + "\nReport: " + report.getUid() + "\nOrder: " + orderId
                                + "\n\n" + report.getDescription() + "\n");
            }
        });
    }

    private String appealUrl(ChefSuspension suspension) {
        return frontendUrl + "/chef/suspension/" + suspension.getUid() + "/appeal";
    }

    private static String greeting(CustomUser chef) {
        return "Kính gửi " + OrderMapper.fullName(chef) + ",\n\n";
    }

    private void withChef(Long chefId, String what, java.util.function.Consumer<CustomUser> action) {
        try {
            CustomUser chef = userRepository.findById(chefId).orElseThrow();
            action.accept(chef);
        } catch (Exception ex) {
            log.error("Failed to send {} email to chef {}: {}", what, chefId, ex.toString());
        }
    }

    private void send(String to, String subject, String body) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            log.warn("No JavaMailSender configured - report email '{}' to {} not sent", subject, to);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            sender.send(message);
        } catch (Exception ex) {
            log.error("Failed to send report email '{}' to {}: {}", subject, to, ex.toString());
        }
    }
}
