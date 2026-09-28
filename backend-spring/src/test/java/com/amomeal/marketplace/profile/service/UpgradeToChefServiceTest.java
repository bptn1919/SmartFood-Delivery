package com.amomeal.marketplace.profile.service;

import com.amomeal.marketplace.profile.dto.ChefPaymentInfoRequest;
import com.amomeal.marketplace.profile.dto.ChefProfileDetailRequest;
import com.amomeal.marketplace.profile.dto.ChefProfileDetailResponse;
import com.amomeal.marketplace.profile.dto.CustomerToChefRequest;
import com.amomeal.marketplace.profile.entity.ChefProfile;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link UpgradeToChefService} — the CUSTOMER→CHEF completion
 * this task exists to wire up. Verifies the exact Django call order (role
 * pre-check fails fast before any writes; chef profile, then payment info,
 * then the role transition last) and that a non-CUSTOMER is rejected without
 * calling into {@code AuthService} at all.
 */
@ExtendWith(MockitoExtension.class)
class UpgradeToChefServiceTest {

    @Mock private AuthService authService;
    @Mock private ProfileService profileService;
    @Mock private ChefPaymentService chefPaymentService;

    private UpgradeToChefService service;

    private CustomUser customer;
    private ChefProfileDetailRequest chefProfileRequest;
    private ChefPaymentInfoRequest chefPaymentRequest;

    @BeforeEach
    void setUp() {
        service = new UpgradeToChefService(authService, profileService, chefPaymentService);
        customer = CustomUser.builder().id(1L).username("cust").build();
        customer.addRole(UserRole.CUSTOMER);
        chefProfileRequest = new ChefProfileDetailRequest(null, "bio", "specialty", null, null, null, null, null,
                null, null, null, null);
        chefPaymentRequest = new ChefPaymentInfoRequest("Vietcombank", "123456", "12345678", "NGUYEN VAN A", null,
                null, null);
    }

    @Test
    void upgradeToChef_nonCustomer_throwsWithoutTouchingAnyWrite() {
        CustomUser chef = CustomUser.builder().id(2L).username("already-chef").build();
        chef.addRole(UserRole.CHEF);
        CustomerToChefRequest req = new CustomerToChefRequest(chefProfileRequest, chefPaymentRequest);

        assertThatThrownBy(() -> service.upgradeToChef(chef, req)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(profileService, chefPaymentService, authService);
    }

    @Test
    void upgradeToChef_customer_callsInDjangoOrder_chefProfileThenPaymentThenRoleTransition() {
        ChefProfile chefProfile = ChefProfile.builder().id(10L).user(customer).build();
        when(profileService.createChefProfile(eq(customer), eq(chefProfileRequest))).thenReturn(chefProfile);
        ChefProfileDetailResponse expected = ChefProfileDetailResponse.of(chefProfile, false, null);
        when(profileService.toDetailResponse(chefProfile)).thenReturn(expected);

        CustomerToChefRequest req = new CustomerToChefRequest(chefProfileRequest, chefPaymentRequest);
        ChefProfileDetailResponse result = service.upgradeToChef(customer, req);

        InOrder order = inOrder(profileService, chefPaymentService, authService);
        order.verify(profileService).createChefProfile(customer, chefProfileRequest);
        order.verify(chefPaymentService).createOrUpdatePaymentInfo(customer, chefPaymentRequest);
        order.verify(authService).upgradeCustomerToChef(customer);

        assertThat(result).isSameAs(expected);
    }
}
