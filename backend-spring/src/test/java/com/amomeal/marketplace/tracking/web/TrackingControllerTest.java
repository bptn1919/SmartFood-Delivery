package com.amomeal.marketplace.tracking.web;

import com.amomeal.marketplace.TestcontainersConfiguration;
import com.amomeal.marketplace.certificate.entity.Certificate;
import com.amomeal.marketplace.certificate.entity.CertificateStatus;
import com.amomeal.marketplace.certificate.entity.CertificateType;
import com.amomeal.marketplace.certificate.repository.CertificateRepository;
import com.amomeal.marketplace.order.entity.Checkout;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.order.entity.PaymentMethod;
import com.amomeal.marketplace.order.repository.CheckoutRepository;
import com.amomeal.marketplace.order.repository.OrderRepository;
import com.amomeal.marketplace.security.JwtService;
import com.amomeal.marketplace.tracking.repository.ChefLocationRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TrackingControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired CustomUserRepository users;
    @Autowired ChefLocationRepository locations;
    @Autowired CertificateRepository certificates;
    @Autowired CheckoutRepository checkouts;
    @Autowired OrderRepository orders;

    private CustomUser user(String prefix, UserRole role, String first) {
        String nonce = String.valueOf(System.nanoTime());
        CustomUser u = CustomUser.builder().username(prefix + nonce).email(prefix + nonce + "@amomeal.test")
                .password("x").firstName(first).build();
        u.addRole(role);
        return users.save(u);
    }

    private ResultActions call(MockHttpServletRequestBuilder b, CustomUser who) throws Exception {
        return mvc.perform(b.header("Authorization", "Bearer " + jwt.issueAccessToken(who.getId())));
    }

    private ResultActions postLoc(CustomUser chef, double lat, double lng) throws Exception {
        return call(post("/api/tracking/chef/location").contentType("application/json")
                .content("{\"latitude\":" + lat + ",\"longitude\":" + lng + "}"), chef);
    }

    @Test
    void updateLocation_chefOnly_upserts_andValidates() throws Exception {
        CustomUser chef = user("tc", UserRole.CHEF, null);
        CustomUser customer = user("tu", UserRole.CUSTOMER, null);

        postLoc(chef, 10.0, 106.0).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.message").value("Location updated successfully"));
        postLoc(chef, 11.0, 107.0).andExpect(status().isOk());
        assertThat(locations.findAll().stream().filter(l -> l.getChef().getId().equals(chef.getId()))).hasSize(1);
        assertThat(locations.findByChefId(chef.getId()).orElseThrow().getLatitude()).isEqualTo(11.0);

        postLoc(customer, 1, 1).andExpect(status().isUnauthorized());
        call(post("/api/tracking/chef/location").contentType("application/json").content("{\"latitude\":1}"), chef)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message_code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/tracking/chef/location").contentType("application/json")
                .content("{\"latitude\":1,\"longitude\":1}")).andExpect(status().isUnauthorized());
    }

    @Test
    void nearby_filtersByRadius_ordersByDistance_andCarriesBadgeAndDjangoQuirks() throws Exception {
        CustomUser customer = user("tn", UserRole.CUSTOMER, null);
        CustomUser near = user("near", UserRole.CHEF, "Gan");
        CustomUser mid = user("mid", UserRole.CHEF, null);
        CustomUser far = user("far", UserRole.CHEF, null);
        postLoc(near, 10.7700, 106.7000).andExpect(status().isOk());
        postLoc(mid, 10.7900, 106.7000).andExpect(status().isOk());   // ~2.2 km
        postLoc(far, 11.5000, 106.7000).andExpect(status().isOk());   // ~81 km
        certificates.save(Certificate.builder().name("ATTP").issuedBy("So").issueDate(LocalDate.now())
                .owner(near).certificateType(CertificateType.FOOD_SAFETY).status(CertificateStatus.ACTIVE).build());
        certificates.save(Certificate.builder().name("pending").issuedBy("So").issueDate(LocalDate.now())
                .owner(mid).certificateType(CertificateType.FOOD_SAFETY).status(CertificateStatus.PENDING).build());

        // searching exactly at a chef's position must not blow up acos (clamped)
        call(get("/api/tracking/chefs/nearby").param("lat", "10.77").param("lng", "106.70").param("radius_km", "5"), customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.search_center.lat").value(10.77))
                .andExpect(jsonPath("$.data.radius_km").value(5.0))
                .andExpect(jsonPath("$.data.results.length()").value(2))
                .andExpect(jsonPath("$.data.results[0].chef_id").value(near.getId()))
                .andExpect(jsonPath("$.data.results[0].chef_name").value("Gan"))
                .andExpect(jsonPath("$.data.results[0].distance_km").value(org.hamcrest.Matchers.lessThan(0.001)))
                .andExpect(jsonPath("$.data.results[0].is_food_safety_certified").value(true))
                .andExpect(jsonPath("$.data.results[0].avg_rating").value(0.0))
                .andExpect(jsonPath("$.data.results[0].avatar").doesNotExist())
                .andExpect(jsonPath("$.data.results[1].chef_id").value(mid.getId()))
                .andExpect(jsonPath("$.data.results[1].chef_name").value("Chef " + mid.getId()))
                .andExpect(jsonPath("$.data.results[1].is_food_safety_certified").value(false))
                .andExpect(jsonPath("$.data.results[1].distance_km").value(org.hamcrest.Matchers.closeTo(2.22, 0.1)));

        // default radius 5.0 applies; a large radius includes the far chef
        call(get("/api/tracking/chefs/nearby").param("lat", "10.77").param("lng", "106.70"), customer)
                .andExpect(jsonPath("$.data.radius_km").value(5.0));
        call(get("/api/tracking/chefs/nearby").param("lat", "10.77").param("lng", "106.70").param("radius_km", "200"), customer)
                .andExpect(jsonPath("$.data.results[2].chef_id").value(far.getId()));

        // role + params
        call(get("/api/tracking/chefs/nearby").param("lat", "1").param("lng", "1"), near).andExpect(status().isUnauthorized());
        call(get("/api/tracking/chefs/nearby").param("lat", "1"), customer).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message_code").value("VALIDATION_ERROR"));
    }

    @Test
    void orderTracking_returnsChefLocationAndDestination_forTheOwnerOnly() throws Exception {
        CustomUser customer = user("to", UserRole.CUSTOMER, null);
        CustomUser stranger = user("ts", UserRole.CUSTOMER, null);
        CustomUser chef = user("tch", UserRole.CHEF, "Bep Truong");
        Checkout checkout = checkouts.save(Checkout.builder().owner(customer).fullName("x").phoneNumber("0900000000")
                .deliveryDate(LocalDate.now()).deliveryTime(LocalTime.NOON).paymentMethod(PaymentMethod.COD).build());
        Order order = orders.save(Order.builder().checkout(checkout).owner(customer).chef(chef)
                .status(OrderStatus.PENDING).deliveryLatitude(10.9).deliveryLongitude(106.9).build());
        String url = "/api/tracking/order/" + order.getUid() + "/tracking";

        // chef has not reported a location yet
        call(get(url), customer).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message_code").value("CHEF_LOCATION_NOT_FOUND"));
        postLoc(chef, 10.8, 106.8).andExpect(status().isOk());

        call(get(url), customer).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_id").value(order.getUid().toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.chef_name").value("Bep Truong"))
                .andExpect(jsonPath("$.data.chef_location.latitude").value(10.8))
                .andExpect(jsonPath("$.data.chef_location.longitude").value(106.8))
                .andExpect(jsonPath("$.data.destination.latitude").value(10.9))
                .andExpect(jsonPath("$.data.destination.longitude").value(106.9));

        call(get(url), stranger).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message_code").value("ORDER_NOT_FOUND"));
        call(get(url), chef).andExpect(status().isUnauthorized());
    }
}
