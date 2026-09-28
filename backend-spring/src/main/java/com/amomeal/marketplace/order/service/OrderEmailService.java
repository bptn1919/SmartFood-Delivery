package com.amomeal.marketplace.order.service;

import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.users.entity.CustomUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Port of ../../backend/utils/services/email/template.py::EmailTemplate.send_order_status_email
 * + {@code EmailClient.send}. Same plain-text simplification as
 * {@code users.service.AuthEmailService} (subjects and status texts verbatim,
 * no Django template inheritance — see that class's PORT-NOTE).
 *
 * <p><b>Failure handling matches Django's {@code EmailClient.send}: SMTP errors are
 * caught and logged, never propagated.</b> Consequence worth knowing (true in
 * Django too): {@code send_order_notification_task}'s retry path is NOT triggered
 * by an SMTP outage — only by failures before the send (loading the order,
 * building the message). Preserved, flagged in PROGRESS.md.
 */
@Slf4j
@Service
public class OrderEmailService {

    static final Map<String, String> STATUS_TEXT = Map.of(
            "CONFIRMED", "đã được xác nhận thanh toán và đang được chuẩn bị",
            "CANCELLED_EXPIRED", "đã tự động bị huỷ vì quá thời gian giữ chỗ tồn kho mà chưa hoàn tất thanh toán",
            "CANCELLED_REFUNDED_LATE_PAYMENT",
            "đã được thanh toán thành công, nhưng do xác nhận đến trễ nên món ăn đã hết trong lúc chờ — "
                    + "đơn hàng đã bị huỷ và số tiền đã thanh toán sẽ được hoàn lại");

    static final Map<String, String> SUBJECT = Map.of(
            "CONFIRMED", "Đơn hàng đã được xác nhận / Order confirmed",
            "CANCELLED_EXPIRED", "Đơn hàng đã bị huỷ do quá hạn thanh toán / Order cancelled (payment timeout)",
            "CANCELLED_REFUNDED_LATE_PAYMENT", "Đơn hàng đã bị huỷ và hoàn tiền / Order cancelled and refunded");

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    public OrderEmailService(ObjectProvider<JavaMailSender> mailSenderProvider) {
        this.mailSenderProvider = mailSenderProvider;
    }

    public static String subjectFor(String eventType) {
        return SUBJECT.getOrDefault(eventType, "Cập nhật đơn hàng / Order update");
    }

    public static String statusTextFor(String eventType) {
        return STATUS_TEXT.getOrDefault(eventType, "đã chuyển sang trạng thái " + eventType);
    }

    /** Django: {@code EmailTemplate().send_order_status_email(user, order, event_type)} + {@code EmailClient().send}. */
    public void sendOrderStatusEmail(CustomUser user, Order order, String eventType) {
        String body = """
                Kính gửi %s,

                Đơn hàng %s của bạn %s.

                Trân trọng!
                Đội ngũ hỗ trợ A Mobile Marketplace Platform
                """.formatted(OrderMapper.fullName(user), order.getUid(), statusTextFor(eventType));
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            log.warn("No JavaMailSender configured — order email '{}' to {} not sent", subjectFor(eventType), user.getEmail());
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(user.getEmail());
            message.setSubject(subjectFor(eventType));
            message.setText(body);
            sender.send(message);
        } catch (Exception ex) {
            log.error("Failed to send order email: {}", ex.toString());
        }
    }
}
