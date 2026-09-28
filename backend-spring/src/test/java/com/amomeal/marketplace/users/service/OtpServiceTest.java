package com.amomeal.marketplace.users.service;

import com.amomeal.marketplace.security.InvalidOrExpiredTokenException;
import com.amomeal.marketplace.users.entity.OtpPurpose;
import com.amomeal.marketplace.users.entity.UserOtp;
import com.amomeal.marketplace.users.exception.InvalidOtpException;
import com.amomeal.marketplace.users.repository.UserOtpRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the OTP cascade ported from
 * {@code ../../backend/users/models.py::UserOTP.verify} +
 * {@code users/services.py::Service.verify_otp} — expiry, the 3-attempt
 * lockout, wrong-code bookkeeping, and single-use consumption.
 *
 * <p>No Testcontainers: the repository is mocked, because everything under test
 * here is in-process logic (Argon2 hashing, the ordered guard cascade,
 * attempt counting). The DB-backed behavior is covered end to end by
 * {@code AuthOtpControllerTest}.
 */
@ExtendWith(MockitoExtension.class)
class OtpServiceTest {

    @Mock
    private UserOtpRepository userOtpRepository;

    private OtpService otpService;

    @BeforeEach
    void setUp() {
        otpService = new OtpService(userOtpRepository, new OtpAttemptRecorder(userOtpRepository));
        lenientSave();
    }

    private void lenientSave() {
        org.mockito.Mockito.lenient().when(userOtpRepository.save(any(UserOtp.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private OtpService.CreatedOtp create(OtpPurpose purpose) {
        return otpService.create(42L, purpose, null);
    }

    private void stubLookup(UserOtp record) {
        when(userOtpRepository.findFirstByResetSessionTokenAndActiveTrue(anyString()))
                .thenReturn(Optional.of(record));
    }

    // =====================================================================
    // create
    // =====================================================================

    @Test
    void create_issuesAFourDigitCodeThatIsHashedNotStored() {
        OtpService.CreatedOtp created = create(OtpPurpose.SIGNUP);

        assertThat(created.plainOtp()).matches("\\d{4}");
        assertThat(created.record().getOtpHash())
                .startsWith("$argon2id$")
                .doesNotContain(created.plainOtp());
        assertThat(created.record().getResetSessionToken()).isNotBlank().doesNotContain("=");
        assertThat(created.record().getAttempts()).isZero();
        assertThat(created.record().isActive()).isTrue();
        assertThat(created.record().isOtpVerified()).isFalse();
    }

    @Test
    void create_issuesADistinctSessionTokenEveryTime() {
        assertThat(create(OtpPurpose.SIGNUP).record().getResetSessionToken())
                .isNotEqualTo(create(OtpPurpose.SIGNUP).record().getResetSessionToken());
    }

    // =====================================================================
    // verify — happy path and single use
    // =====================================================================

    @Test
    void verify_correctCode_consumesTheRecord() {
        OtpService.CreatedOtp created = create(OtpPurpose.RESET_PASSWORD);
        stubLookup(created.record());

        UserOtp verified = otpService.verify(created.record().getResetSessionToken(), created.plainOtp());

        assertThat(verified.isOtpVerified()).isTrue();
        // active is cleared in the same save — an OTP is strictly single-use.
        assertThat(verified.isActive()).isFalse();
        assertThat(verified.getAttempts()).isZero();
    }

    @Test
    void verify_unknownSessionToken_is401InvalidOrExpiredToken() {
        when(userOtpRepository.findFirstByResetSessionTokenAndActiveTrue(anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> otpService.verify("nope", "1234"))
                .isInstanceOf(InvalidOrExpiredTokenException.class);
    }

    // =====================================================================
    // verify — wrong code / lockout
    // =====================================================================

    @Test
    void verify_wrongCode_incrementsAttemptsAndIs403InvalidOtp() {
        OtpService.CreatedOtp created = create(OtpPurpose.SIGNUP);
        String wrong = created.plainOtp().equals("0000") ? "1111" : "0000";
        stubLookup(created.record());

        assertThatThrownBy(() -> otpService.verify(created.record().getResetSessionToken(), wrong))
                .isInstanceOf(InvalidOtpException.class)
                .hasMessage("Invalid OTP");
        assertThat(created.record().getAttempts()).isEqualTo(1);
        // Still usable — the record is not burned by a single miss.
        assertThat(created.record().isActive()).isTrue();
    }

    @Test
    void verify_threeWrongCodes_thenLocksOutEvenWithTheRightCode() {
        OtpService.CreatedOtp created = create(OtpPurpose.SIGNUP);
        String token = created.record().getResetSessionToken();
        String wrong = created.plainOtp().equals("0000") ? "1111" : "0000";
        stubLookup(created.record());

        for (int i = 1; i <= OtpService.OTP_MAX_ATTEMPTS; i++) {
            final int attempt = i;
            assertThatThrownBy(() -> otpService.verify(token, wrong))
                    .as("attempt %d", attempt)
                    .isInstanceOf(InvalidOtpException.class);
        }
        assertThat(created.record().getAttempts()).isEqualTo(OtpService.OTP_MAX_ATTEMPTS);

        // The 4th call is rejected by the cap BEFORE the code is even compared —
        // so the correct code no longer helps, and the record is deactivated.
        assertThatThrownBy(() -> otpService.verify(token, created.plainOtp()))
                .isInstanceOf(InvalidOtpException.class)
                .hasMessage("Max OTP attempts exceeded");
        assertThat(created.record().isActive()).isFalse();
        assertThat(created.record().isOtpVerified()).isFalse();
    }

    // =====================================================================
    // verify — expiry
    // =====================================================================

    @Test
    void verify_expiredRecord_deactivatesItAndIs401() {
        OtpService.CreatedOtp created = create(OtpPurpose.SIGNUP);
        // SIGNUP expires after 15 minutes (settings.OTP_EXPIRY_MINUTES).
        created.record().setCreatedAt(Instant.now().minus(16, ChronoUnit.MINUTES));
        stubLookup(created.record());

        assertThatThrownBy(() -> otpService.verify(created.record().getResetSessionToken(), created.plainOtp()))
                .isInstanceOf(InvalidOrExpiredTokenException.class);
        assertThat(created.record().isActive()).isFalse();
    }

    @Test
    void isExpired_usesThePerPurposeWindowFromSettings() {
        Instant threeMinutesAgo = Instant.now().minus(3, ChronoUnit.MINUTES);

        // WITHDRAW_VERIFY/BANK_VERIFY: 2 minutes. SIGNUP/RESET_PASSWORD: 15.
        assertThat(UserOtp.builder().purpose(OtpPurpose.WITHDRAW_VERIFY).createdAt(threeMinutesAgo).build()
                .isExpired()).isTrue();
        assertThat(UserOtp.builder().purpose(OtpPurpose.BANK_VERIFY).createdAt(threeMinutesAgo).build()
                .isExpired()).isTrue();
        assertThat(UserOtp.builder().purpose(OtpPurpose.SIGNUP).createdAt(threeMinutesAgo).build()
                .isExpired()).isFalse();
        // EMAIL_CHANGE is absent from OTP_EXPIRY_MINUTES -> falls back to 15 minutes.
        assertThat(OtpPurpose.EMAIL_CHANGE.expiryMinutes()).isEqualTo(15);
    }

    @Test
    void verify_alreadyVerifiedRecord_is403_thoughDjangoCanNeverReachIt() {
        // PORT-NOTE guard: verification also clears `active`, and the lookup filters
        // active=true, so a verified record can never come back from the query.
        // Ported anyway — asserted here by handing the branch a record directly.
        OtpService.CreatedOtp created = create(OtpPurpose.SIGNUP);
        created.record().setOtpVerified(true);
        stubLookup(created.record());

        assertThatThrownBy(() -> otpService.verify(created.record().getResetSessionToken(), created.plainOtp()))
                .isInstanceOf(InvalidOtpException.class)
                .hasMessage("OTP already used");
    }

    // =====================================================================
    // matches (the email-change path's bare comparison)
    // =====================================================================

    @Test
    void matches_comparesAgainstTheHashWithoutTouchingAttempts() {
        OtpService.CreatedOtp created = create(OtpPurpose.EMAIL_CHANGE);

        assertThat(otpService.matches(created.record(), created.plainOtp())).isTrue();
        assertThat(otpService.matches(created.record(), "9999".equals(created.plainOtp()) ? "8888" : "9999"))
                .isFalse();
        assertThat(created.record().getAttempts()).isZero();
        assertThatCode(() -> otpService.matches(created.record(), "0000")).doesNotThrowAnyException();
    }
}
