package com.amomeal.marketplace.payment.service;

import com.amomeal.marketplace.users.entity.CustomUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Django {@code EmailTemplate.send_withdraw_failed_email} + {@code EmailClient.send}
 * (subject verbatim; plain-text body like every other ported email — see PROGRESS.md
 * {@code users} notes). Failures are swallowed and logged, as Django's
 * {@code _send_withdraw_failure_email} does.
 */
@Service
@Slf4j
public class PaymentEmailService {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    public PaymentEmailService(ObjectProvider<JavaMailSender> mailSenderProvider) {
        this.mailSenderProvider = mailSenderProvider;
    }

    public void sendWithdrawFailedEmail(CustomUser user, String amount, String referenceId, String reason) {
        try {
            JavaMailSender sender = mailSenderProvider.getIfAvailable();
            if (sender == null) {
                log.warn("No mail sender configured; withdrawal-failure email to {} not sent", user.getEmail());
                return;
            }
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(user.getEmail());
            message.setSubject("Withdrawal failed / Rut tien that bai");
            message.setText("""
                    Kính gửi %s,

                    Yêu cầu rút %s VND (mã tham chiếu %s) không thành công.
                    Lý do: %s

                    Số tiền đã được hoàn lại vào ví của bạn.

                    Trân trọng!
                    Đội ngũ hỗ trợ A Mobile Marketplace Platform
                    """.formatted(PaymentService.fullName(user), amount, referenceId, reason));
            sender.send(message);
        } catch (Exception ex) {
            log.error("Failed to send withdrawal failure email: {}", ex.toString());
        }
    }
}
