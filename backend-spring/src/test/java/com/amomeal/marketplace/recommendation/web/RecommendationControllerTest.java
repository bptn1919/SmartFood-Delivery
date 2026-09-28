package com.amomeal.marketplace.recommendation.web;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.recommendation.support.RecommendationIntegrationTestBase;
import com.amomeal.marketplace.review.entity.Review;
import com.amomeal.marketplace.review.repository.ReviewRepository;
import com.amomeal.marketplace.users.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MockMvc over the real stack (JWT, real roles, envelope, snake_case). */
class RecommendationControllerTest extends RecommendationIntegrationTestBase {

    @Autowired ReviewRepository reviewRepository;

    private JsonNode data(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return objectMapper.readTree(r.andReturn().getResponse().getContentAsString()).get("data");
    }

    @Test
    void feed_requiresToken_andReturnsEnvelopeWithSnakeCaseFields() throws Exception {
        mockMvc.perform(get("/api/recommendation/me/dishes")).andExpect(status().isUnauthorized());

        var chef = chef("ctlchef");
        var customer = register("ctlcust", UserRole.CUSTOMER);
        Dish d = dish(chef, unique("Bò kho"), 4.4);
        line(d, null, 200, 30, 12, 20, 700, 2, 1.0);

        JsonNode body = data(mockMvc.perform(get("/api/recommendation/me/dishes?limit=3&include_explain=true")
                        .header("Authorization", customer.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error_code").value(0))
                .andExpect(jsonPath("$.data.meta.limit").value(3))
                .andExpect(jsonPath("$.data.meta.mmr_lambda").value(0.5))
                .andExpect(jsonPath("$.data.meta.weights.w1").value(0.4))
                .andExpect(jsonPath("$.data.meta.user_vector_source").exists()));
        JsonNode first = body.get("items").get(0);
        for (String key : new String[]{"dish_uid", "dish_name", "public_url", "price", "avg_rating", "is_favorite",
                "allergy_warning", "score", "base_score", "favorite_score", "history_score", "issue_penalty",
                "nutrition_penalty", "preference_nutrition_mismatch_penalty", "reasons"}) {
            assertThat(first.has(key)).as(key).isTrue();
        }
        assertThat(first.get("reasons").size()).isBetween(1, 3);
    }

    @Test
    void features_missingRowIs500WithDjangosStatusCodeQuirk_andProfileSyncHookFillsThem() throws Exception {
        var chef = chef("ftchef");
        var customer = register("ftcust", UserRole.CUSTOMER);
        mockMvc.perform(get("/api/recommendation/me/features").header("Authorization", customer.bearer()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message_code").value("PREFERENCES_DOES_NOT_EXIST"))
                .andExpect(jsonPath("$.error_code").value(500));

        Dish d = dish(chef, unique("Chả giò"), 4.0);
        // profile's real endpoint -> sync_user_feature(["fav_dish"]) re-attached by RecommendationSyncAspect
        mockMvc.perform(post("/api/customer-profiles/favorite-dish/" + d.getUid()).header("Authorization", customer.bearer()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/recommendation/me/features").header("Authorization", customer.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user").value(customer.id()))
                .andExpect(jsonPath("$.data.favorite_dish_ids[0]").value(d.getUid().toString()))
                .andExpect(jsonPath("$.data.diet_mode").value("NONE"))
                .andExpect(jsonPath("$.data.allergy_mode").value("WARN"))
                .andExpect(jsonPath("$.data.embedding").isEmpty());

        // unfavourite -> rebuilt again (removeFavoriteDish returned true)
        mockMvc.perform(patch("/api/customer-profiles/favorite-dish/" + d.getUid()).header("Authorization", customer.bearer()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/recommendation/me/features").header("Authorization", customer.bearer()))
                .andExpect(jsonPath("$.data.favorite_dish_ids").isEmpty());
    }

    @Test
    void adminOnlyUserEndpoints_areOpenToAnyAuthenticatedUser_preservedDjangoDecoratorOrderBug() throws Exception {
        var customer = register("admcust", UserRole.CUSTOMER);
        var other = register("admother", UserRole.CUSTOMER);
        mockMvc.perform(get("/api/recommendation/users/" + other.id() + "/profile").header("Authorization", customer.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user_id").value(other.id()))
                .andExpect(jsonPath("$.data.issue_profile['mặn']").value(0.0))
                .andExpect(jsonPath("$.data.confidence").value(0.0))
                .andExpect(jsonPath("$.data.data_points").value(0));
        mockMvc.perform(get("/api/recommendation/users/999999999/profile").header("Authorization", customer.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message_code").value("USER_NOT_FOUND"));
    }

    @Test
    void dailyNutritionEndpoints_validation_profileNotFound_andFullRoundTrip() throws Exception {
        var customer = register("dnctl", UserRole.CUSTOMER);
        String auth = customer.bearer();

        mockMvc.perform(get("/api/recommendation/me/daily-nutrition/profile").header("Authorization", auth))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message_code").value("DAILY_NUTRITION_PROFILE_NOT_FOUND"));

        // missing required age -> VALIDATION_ERROR (project-wide 401 quirk)
        mockMvc.perform(post("/api/recommendation/me/daily-nutrition/init").header("Authorization", auth)
                        .contentType("application/json").content("{\"height_cm\":170,\"weight_kg\":60}"))
                .andExpect(jsonPath("$.message_code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/recommendation/me/daily-nutrition/init").header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"age\":30,\"gender\":\"MALE\",\"height_cm\":175,\"weight_kg\":75,\"activity_level\":\"MODERATE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bmr_kcal").value(1762.652)) // 88.362+13.397*75+4.799*175-5.677*30 (NUTRITION_API.md's 1762.627 is an arithmetic slip)
                .andExpect(jsonPath("$.data.target.protein_g").value(75.0))
                .andExpect(jsonPath("$.data.remaining.sodium_mg").value(2300.0))
                .andExpect(jsonPath("$.data.remaining.sodium_mg_lower").doesNotExist());

        mockMvc.perform(patch("/api/recommendation/me/daily-nutrition/profile").header("Authorization", auth)
                        .contentType("application/json").content("{\"weight_kg\":77}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.weight_kg").value(77.0))
                .andExpect(jsonPath("$.data.age").value(30))
                .andExpect(jsonPath("$.data.activity_level").value("MODERATE"));

        mockMvc.perform(post("/api/recommendation/me/daily-nutrition/parse-meal").header("Authorization", auth)
                        .contentType("application/json").content("{\"text\":\"x\",\"meal_time\":\"BRUNCH\"}"))
                .andExpect(jsonPath("$.message_code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/recommendation/me/daily-nutrition/summary").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.date").exists());
        mockMvc.perform(get("/api/recommendation/me/daily-nutrition/meals").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray());
        mockMvc.perform(delete("/api/recommendation/me/daily-nutrition/meals/" + UUID.randomUUID()).header("Authorization", auth))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message_code").value("CONTACT_ADMIN_FOR_SUPPORT"));
    }

    @Test
    void betterForIssue_noIssueEmptyItems_withIssueScoredAlternatives_missingDish404() throws Exception {
        var chef = chef("bfchef");
        var customer = register("bfcust", UserRole.CUSTOMER);
        Dish source = dish(chef, unique("Lẩu thái chua cay"), 3.0);
        line(source, null, 300, 20, 10, 30, 1800, 3, 1.0);
        Dish alt = dish(chef, unique("Lẩu nấm chay"), 4.7);
        line(alt, null, 300, 18, 9, 32, 1500, 4, 1.0);
        String auth = customer.bearer();

        mockMvc.perform(get("/api/recommendation/me/dishes/" + UUID.randomUUID() + "/better-for-issue").header("Authorization", auth))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message_code").value("DISH_NOT_FOUND"));

        mockMvc.perform(get("/api/recommendation/me/dishes/" + source.getUid() + "/better-for-issue").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.issue").isEmpty())
                .andExpect(jsonPath("$.data.source_dish_uid").value(source.getUid().toString()))
                .andExpect(jsonPath("$.data.items").isEmpty());

        Order order = order(customer, source, 1, OrderStatus.COMPLETED, LocalTime.NOON);
        reviewRepository.save(Review.builder().rating(2).comment("mặn quá").issue("mặn").weight(0.9)
                .dish(source).order(order).owner(customer.user()).build());

        JsonNode body = data(mockMvc.perform(get("/api/recommendation/me/dishes/" + source.getUid()
                        + "/better-for-issue?limit=50").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.issue").value("mặn")));
        JsonNode items = body.get("items");
        assertThat(items.size()).isBetween(1, 20); // limit clamped to [1, 20]
        for (JsonNode item : items) {
            assertThat(item.get("dish_uid").asString()).isNotEqualTo(source.getUid().toString());
            assertThat(item.get("explain").asString()).isEqualTo("Lower mặn incidence + better rating");
        }
    }
}
