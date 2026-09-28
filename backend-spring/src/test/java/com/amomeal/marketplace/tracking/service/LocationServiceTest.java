package com.amomeal.marketplace.tracking.service;

import com.amomeal.marketplace.order.repository.OrderRepository;
import com.amomeal.marketplace.profile.repository.ChefProfileRepository;
import com.amomeal.marketplace.profile.service.ChefCertificationProvider;
import com.amomeal.marketplace.tracking.entity.ChefLocation;
import com.amomeal.marketplace.tracking.repository.ChefLocationRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocationServiceTest {

    @Mock ChefLocationRepository locations;
    @Mock ChefProfileRepository profiles;
    @Mock ChefCertificationProvider certs;
    @Mock OrderRepository orders;
    @Mock LocationBroadcaster broadcaster;

    CustomUser chef;

    @BeforeEach
    void setUp() {
        chef = CustomUser.builder().id(7L).username("c").email("c@x").password("x").build();
    }

    private LocationService service(boolean zeroRating, boolean orderBug) {
        return new LocationService(locations, profiles, certs, orders, broadcaster, zeroRating, orderBug);
    }

    @Test
    void firstUpdate_createsRow_setsHeading_andBroadcastsRequestPayload() {
        when(locations.findByChefId(7L)).thenReturn(Optional.empty());
        when(locations.save(any())).thenAnswer(i -> i.getArgument(0));

        ChefLocation saved = service(true, false).updateChefLocation(chef, 10.5, 106.5, 90.0);

        assertThat(saved.getChef()).isSameAs(chef);
        assertThat(saved.getLatitude()).isEqualTo(10.5);
        assertThat(saved.getHeading()).isEqualTo(90.0);
        verify(broadcaster).broadcastLocation(7L, 10.5, 106.5, 90.0);
    }

    @Test
    void updateWithoutHeading_keepsStoredHeading_butBroadcastsNullHeading() {
        ChefLocation existing = ChefLocation.builder().chef(chef).latitude(1.0).longitude(2.0).heading(45.0).build();
        when(locations.findByChefId(7L)).thenReturn(Optional.of(existing));
        when(locations.save(any())).thenAnswer(i -> i.getArgument(0));

        service(true, false).updateChefLocation(chef, 3.0, 4.0, null);

        assertThat(existing.getHeading()).isEqualTo(45.0);
        assertThat(existing.getLatitude()).isEqualTo(3.0);
        ArgumentCaptor<Double> heading = ArgumentCaptor.forClass(Double.class);
        verify(broadcaster).broadcastLocation(eq(7L), eq(3.0), eq(4.0), heading.capture());
        assertThat(heading.getValue()).isNull();
    }

    @Test
    void displayName_fallsBackToChefId() {
        assertThat(LocationService.displayName(chef)).isEqualTo("Chef 7");
        chef.setFirstName("An");
        chef.setLastName("Nguyen");
        assertThat(LocationService.displayName(chef)).isEqualTo("An Nguyen");
    }

    @Test
    void orderTracking_preservedBug_alwaysFails_andNeverTouchesTheDb() {
        assertThatThrownBy(() -> service(true, true).getOrderTracking(chef, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ImportError");
        verify(orders, never()).findById(any());
    }
}
