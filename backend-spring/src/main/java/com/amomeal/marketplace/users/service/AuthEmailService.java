package com.amomeal.marketplace.users.service;

import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.OtpPurpose;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Port of {@code ../backend/utils/services/email/{template,client}.py}, limited
 * to the messages the {@code users} module sends (OTP verification +
 * password-changed notice). The other {@code EmailTemplate} methods belong to
 * {@code order}/{@code payment}/{@code report} and are ported with those modules.
 *
 * <p><b>Deliberate simplification (PORT-NOTE).</b> Django renders Django
 * templates ({@code templates/email/*.html}, all extending
 * {@code email/base.html}) and sends them as an HTML alternative via
 * {@code EmailMultiAlternatives}. Reimplementing that template inheritance in a
 * different engine would be busywork with no contract surface — FE never sees
 * these bodies. Subjects are ported <em>verbatim</em> (they are the only part a
 * user reads before opening), the purpose→template routing is ported exactly
 * (including Django's reuse of {@code signup_verification.html} for
 * EMAIL_CHANGE/BANK_VERIFY/WITHDRAW_VERIFY, and its
 * {@code ValueError("Invalid verification purpose")} for anything else), and the
 * bodies carry the same information as plain text.
 *
 * <p>Failure handling matches Django's {@code EmailClient.send}: every exception
 * is caught and logged, never propagated — a dead SMTP server must not fail a
 * signup. {@link JavaMailSender} is resolved through an {@link ObjectProvider}
 * so the application (and the test suite) still boots with no mail host
 * configured.
 */
@Slf4j
@Service
public class AuthEmailService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm - dd/MM/yyyy");

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final Environment environment;

    public AuthEmailService(ObjectProvider<JavaMailSender> mailSenderProvider, Environment environment) {
        this.mailSenderProvider = mailSenderProvider;
        this.environment = environment;
    }

    /** Django: {@code EmailTemplate.send_verification_email} + {@code EmailClient.send}. */
    public void sendVerificationEmail(CustomUser user, String otp, OtpPurpose purpose, String toEmail) {
        String subject = switch (purpose) {
            case SIGNUP -> "Verify your account / Xác thực tài khoản";
            case RESET_PASSWORD -> "Forgot password / Quên mật khẩu";
            case EMAIL_CHANGE -> "Verify your new email / Xác thực email mới";
            case BANK_VERIFY -> "Verify your bank account / Xác thực tài khoản ngân hàng";
            case WITHDRAW_VERIFY -> "Confirm withdrawal / Xác nhận rút tiền";
        };
        String body = """
                Kính gửi %s,

                Mã OTP của bạn là: %s

                Mã có hiệu lực trong %d phút. Nếu bạn không thực hiện yêu cầu này, vui lòng bỏ qua email.

                Trân trọng!
                Đội ngũ hỗ trợ A Mobile Marketplace Platform
                """.formatted(fullName(user), otp, purpose.expiryMinutes());
        send(toEmail != null ? toEmail : user.getEmail(), subject, body);
    }

    /** Django: {@code EmailTemplate.change_password}. */
    public void sendPasswordChangedEmail(CustomUser user) {
        String body = """
                Kính gửi %s,

                Mật khẩu của bạn đã được thay đổi lúc %s.
                Nếu đây không phải là bạn, vui lòng liên hệ đội ngũ hỗ trợ ngay lập tức.

                Trân trọng!
                Đội ngũ hỗ trợ A Mobile Marketplace Platform
                """.formatted(fullName(user), ZonedDateTime.now(ZoneId.systemDefault()).format(TIME_FORMAT));
        send(user.getEmail(), "Change password / Thay đổi mật khẩu", body);
    }

    /** Django: {@code User.get_full_name()} — "first last", stripped. */
    private static String fullName(CustomUser user) {
        String first = user.getFirstName() == null ? "" : user.getFirstName();
        String last = user.getLastName() == null ? "" : user.getLastName();
        return (first + " " + last).trim();
    }

    private void send(String to, String subject, String body) {
        // DEV ONLY: with `--spring.profiles.active=dev` the mail (incl. the OTP) is logged so a
        // developer can complete OTP flows without SMTP. Never active unless the profile is set.
        if (environment.acceptsProfiles(Profiles.of("dev"))) {
            log.info("[DEV-MAIL] to={} subject='{}'{}{}", to, subject, System.lineSeparator(), body);
        }
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            log.warn("No JavaMailSender configured — skipping email '{}' to {}", subject, to);
            return;
        }
        try {
            log.info("Sending email");
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            sender.send(message);
            log.info("Email sent successfully");
        } catch (Exception ex) {
            // Django's EmailClient.send swallows and logs every exception — preserved,
            // otherwise a misconfigured SMTP host would 500 a signup.
            log.error("Failed to send email '{}' to {}: {}", subject, to, ex.toString());
        }
    }
}
