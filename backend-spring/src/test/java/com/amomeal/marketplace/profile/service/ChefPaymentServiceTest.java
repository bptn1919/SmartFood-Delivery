package com.amomeal.marketplace.profile.service;

import com.amomeal.marketplace.profile.dto.ChefPaymentInfoRequest;
import com.amomeal.marketplace.profile.entity.ChefPaymentInfo;
import com.amomeal.marketplace.profile.exception.ChefPaymentValidationException;
import com.amomeal.marketplace.profile.exception.ProfileDoesNotExistException;
import com.amomeal.marketplace.profile.repository.ChefPaymentInfoRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChefPaymentService} — ports every
 * ../../backend/profile/schemas/chef_payment.py Pydantic field_validator 1:1,
 * including the normalize-then-check order and the "empty string skips
 * validation" quirk on citizen_id/tax_code.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChefPaymentServiceTest {

    @Mock private ChefPaymentInfoRepository chefPaymentInfoRepository;

    private ChefPaymentService service;
    private CustomUser user;

    @BeforeEach
    void setUp() {
        service = new ChefPaymentService(chefPaymentInfoRepository);
        user = CustomUser.builder().id(1L).username("chef").build();
    }

    private static ChefPaymentInfoRequest validRequest() {
        return new ChefPaymentInfoRequest("Vietcombank", "123456", "12345678", "NGUYEN VAN A", "Hanoi", "079123456789", "1234567890");
    }

    @Test
    void createOrUpdatePaymentInfo_validPayload_normalizesAndPersists() {
        when(chefPaymentInfoRepository.findByUser(user)).thenReturn(Optional.empty());
        when(chefPaymentInfoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ChefPaymentInfoRequest req = new ChefPaymentInfoRequest("VCB", " 12 34 56 ", "1234-5678", "  NGUYEN  VAN   A  ",
                null, "", "");
        ChefPaymentInfo saved = service.createOrUpdatePaymentInfo(user, req);

        assertThat(saved.getBankName()).isEqualTo("VCB");
        assertThat(saved.getBankCode()).isEqualTo("123456");
        assertThat(saved.getBankAccountNumber()).isEqualTo("12345678");
        assertThat(saved.getBankAccountName()).isEqualTo("NGUYEN VAN A");
        assertThat(saved.isVerified()).isFalse();
    }

    @Test
    void createOrUpdatePaymentInfo_invalidBankName_isRejected() {
        when(chefPaymentInfoRepository.findByUser(user)).thenReturn(Optional.empty());
        ChefPaymentInfoRequest req = new ChefPaymentInfoRequest("NotARealBank", "123456", "12345678", "NGUYEN VAN A",
                null, null, null);
        assertThatThrownBy(() -> service.createOrUpdatePaymentInfo(user, req))
                .isInstanceOf(ChefPaymentValidationException.class);
    }

    @Test
    void bankCode_mustBe6To8Digits() {
        when(chefPaymentInfoRepository.findByUser(user)).thenReturn(Optional.empty());
        ChefPaymentInfoRequest tooShort = new ChefPaymentInfoRequest("VCB", "123", "12345678", "NGUYEN VAN A", null, null, null);
        assertThatThrownBy(() -> service.createOrUpdatePaymentInfo(user, tooShort))
                .isInstanceOf(ChefPaymentValidationException.class);
    }

    @Test
    void accountNumber_stripsSpacesAndDashes_thenValidatesDigitsOnly() {
        when(chefPaymentInfoRepository.findByUser(user)).thenReturn(Optional.empty());
        ChefPaymentInfoRequest withLetters = new ChefPaymentInfoRequest("VCB", "123456", "12A45678", "NGUYEN VAN A", null, null, null);
        assertThatThrownBy(() -> service.createOrUpdatePaymentInfo(user, withLetters))
                .isInstanceOf(ChefPaymentValidationException.class);
    }

    @Test
    void accountName_lowercaseIsRejected_evenThoughTrimmedResultWouldBeValid() {
        when(chefPaymentInfoRepository.findByUser(user)).thenReturn(Optional.empty());
        // Django validates the regex on the RAW value BEFORE stripping/collapsing.
        ChefPaymentInfoRequest lowercase = new ChefPaymentInfoRequest("VCB", "123456", "12345678", "nguyen van a", null, null, null);
        assertThatThrownBy(() -> service.createOrUpdatePaymentInfo(user, lowercase))
                .isInstanceOf(ChefPaymentValidationException.class);
    }

    @Test
    void accountName_tooShortAfterCollapsing_isRejected() {
        when(chefPaymentInfoRepository.findByUser(user)).thenReturn(Optional.empty());
        ChefPaymentInfoRequest tooShort = new ChefPaymentInfoRequest("VCB", "123456", "12345678", "A", null, null, null);
        assertThatThrownBy(() -> service.createOrUpdatePaymentInfo(user, tooShort))
                .isInstanceOf(ChefPaymentValidationException.class);
    }

    @Test
    void citizenId_emptyString_skipsValidation_matchingPythonFalsyQuirk() {
        when(chefPaymentInfoRepository.findByUser(user)).thenReturn(Optional.empty());
        when(chefPaymentInfoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ChefPaymentInfoRequest req = new ChefPaymentInfoRequest("VCB", "123456", "12345678", "NGUYEN VAN A", null, "", null);
        ChefPaymentInfo saved = service.createOrUpdatePaymentInfo(user, req);
        assertThat(saved.getCitizenId()).isEmpty();
    }

    @Test
    void citizenId_nonEmptyButWrongLength_isRejected() {
        when(chefPaymentInfoRepository.findByUser(user)).thenReturn(Optional.empty());
        ChefPaymentInfoRequest req = new ChefPaymentInfoRequest("VCB", "123456", "12345678", "NGUYEN VAN A", null, "123", null);
        assertThatThrownBy(() -> service.createOrUpdatePaymentInfo(user, req))
                .isInstanceOf(ChefPaymentValidationException.class);
    }

    @Test
    void updatingExistingInfo_resetsVerificationFlag() {
        ChefPaymentInfo existing = ChefPaymentInfo.builder().user(user).isVerified(true).build();
        when(chefPaymentInfoRepository.findByUser(user)).thenReturn(Optional.of(existing));
        when(chefPaymentInfoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ChefPaymentInfo saved = service.createOrUpdatePaymentInfo(user, validRequest());
        assertThat(saved.isVerified()).isFalse();
    }

    @Test
    void getPaymentInfo_notFound_throwsProfileDoesNotExist() {
        when(chefPaymentInfoRepository.findByUser(user)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getPaymentInfo(user)).isInstanceOf(ProfileDoesNotExistException.class);
    }

    @Test
    void deletePaymentInfo_softDeletesOnly() {
        ChefPaymentInfo existing = ChefPaymentInfo.builder().user(user).deleted(false).build();
        when(chefPaymentInfoRepository.findByUser(user)).thenReturn(Optional.of(existing));
        when(chefPaymentInfoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        boolean result = service.deletePaymentInfo(user);

        assertThat(result).isTrue();
        assertThat(existing.isDeleted()).isTrue();
    }

    @Test
    void maskAccountNumber_showsOnlyLast4Digits() {
        assertThat(service.maskAccountNumber("12345678")).isEqualTo("****5678");
        assertThat(service.maskAccountNumber("123")).isEqualTo("123");
    }
}
