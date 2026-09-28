import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:testing/core/network/api_client.dart';
import 'package:testing/features/recommend/models/better_dish_response.dart';
import 'package:testing/features/recommend/models/daily_meal_model.dart';
import 'package:testing/features/recommend/models/daily_nutrition_summary_model.dart';
import 'package:testing/features/recommend/models/food_preference_features_model.dart';
import 'package:testing/features/recommend/models/issue_sensitivity_model.dart';
import 'package:testing/features/recommend/models/nutrition_profile_model.dart';
import 'package:testing/features/recommend/models/parse_meal_response_model.dart';
import 'package:testing/features/recommend/models/recommendation_feed_model.dart';
import 'package:testing/features/recommend/models/recommendation_model.dart';

import 'dart:developer';

class RecommendRepository {
  final ApiClient _api;

  RecommendRepository({ApiClient? api}) : _api = api ?? ApiClient();

  Future<IssueSensitivityModel?> getUserIssueSensitivityProfile(int user_uid) async {
    try {
      final response = await _api.get('/api/recommendation/users/$user_uid/profile');
      debugPrint("GetUserIssueSensitivityProfile Response: $response");
      if (response != null) {
        final dynamic rawData = response['data'] ?? response;

        if (rawData is Map<String, dynamic>) {
          return IssueSensitivityModel.fromJson(rawData);
        }
      }
      return null; 
    } catch (e) {
      debugPrint("🚨 Error in getUserIssueSensitivityProfile: $e");
      return null; 
    }
  }


  Future<DailyNutritionSummaryModel?> getDailyNutritionSummary() async {
    try {
      // Gọi API GET
      final response = await _api.get('/api/recommendation/me/daily-nutrition/summary');
      debugPrint("GetDailyNutritionSummary Response: $response");
      if (response != null) {
        // TECH LEAD HACK: Xử lý lớp vỏ 'data' phòng trường hợp BE bọc thêm
        final dynamic rawData = response['data'] ?? response;

        if (rawData is Map<String, dynamic>) {
          return DailyNutritionSummaryModel.fromJson(rawData);
        }
      }
      
      return null; 
      
    } catch (e) {
      debugPrint("🚨 Error in getDailyNutritionSummary: $e");
      // Trả về null khi dính lỗi 404 (chưa có data ngày hôm nay) hoặc lỗi server
      return null; 
    }
  }



  Future<bool> initDailyNutrition({
    required int age,
    required num heightCm,
    required num weightKg,
    String? gender,
    String? activityLevel,
    String? goal,
  }) async {
    try {
      final Map<String, dynamic> data = {};
      
      data['age'] = age;
      if (gender != null) data['gender'] = gender;
      data['height_cm'] = heightCm;
      data['weight_kg'] = weightKg;
      if (activityLevel != null) data['activity_level'] = activityLevel;
      if (goal != null) data['goal'] = goal;

      await _api.post(
        '/api/recommendation/me/daily-nutrition/init',
        data,
      );
      
      return true; // Trả về true nếu API chạy lọt qua không bị crash (status 20x)
    } catch (e) {
      debugPrint("🚨 Error in initDailyNutrition: $e");
      return false; // Trả về false nếu BE báo lỗi (ví dụ: duplicate, lỗi server...)
    }
  }


  Future<FoodPreferenceFeaturesModel?> getMyFoodPreferenceFeatures() async {
    try {
      final response = await _api.get('/api/recommendation/me/features');
      debugPrint("GetMyFoodPreferenceFeatures Response: $response");
      if (response != null) {
        // TECH LEAD HACK: Bọc lớp data phòng hờ
        final dynamic rawData = response['data'] ?? response;

        if (rawData is Map<String, dynamic>) {
          return FoodPreferenceFeaturesModel.fromJson(rawData);
        }
      }
      return null;
    } catch (e) {
      debugPrint("🚨 Error in getMyFoodPreferenceFeatures: $e");
      return null; // Trả về null nếu dính 404 (chưa có data)
    }
  }

  Future<RecommendationFeedResponse?> getMyRecommendationFeed({
    int limit = 20,
    int offset = 0,
    bool includeExplain = true, // Khuyên dùng true để lấy list 'reasons' hiển thị UI
  }) async {
    try {
      debugPrint("Fetching recommendation feed from API...");

      // Truyền params lên URL
      final url = '/api/recommendation/me/dishes?limit=$limit&offset=$offset&include_explain=$includeExplain';
      final response = await _api.get(url);

      if (response != null) {
        final dynamic rawData = response['data'] ?? response;
        if (rawData is Map<String, dynamic>) {
          return RecommendationFeedResponse.fromJson(rawData);
        }
      }
      return null;
    } catch (e) {
      debugPrint("🚨 Error in getMyRecommendationFeed: $e");
      return null; 
    }
  }


  Future<ParseMealResponseModel?> parseDailyMeal({
    required String text,
    String mealTime = "UNKNOWN", // Giá trị mặc định theo thiết kế BE
  }) async {
    try {
      // Build request body
      final Map<String, dynamic> requestBody = {
        "text": text,
        "meal_time": mealTime,
      };

      // Gọi API POST
      final response = await _api.post(
        '/api/recommendation/me/daily-nutrition/parse-meal',
        requestBody,
      );

      if (response != null) {
        // TECH LEAD HACK: Bọc lớp data phòng trường hợp BE trả về bọc trong 'data'
        final dynamic rawData = response['data'] ?? response;

        if (rawData is Map<String, dynamic>) {
          return ParseMealResponseModel.fromJson(rawData);
        }
      }
      
      return null; 
      
    } catch (e) {
      debugPrint("🚨 Error in parseDailyMeal: $e");
      // Trả về null khi dính lỗi (ví dụ 404 hoặc lỗi xử lý LLM từ server)
      return null; 
    }
  }


  /// Lấy danh sách gợi ý món ăn cân bằng dinh dưỡng trong ngày
  Future<BalancedRecommendationResponse?> getDailyBalancedRecommendations({int limit = 10}) async {
    try {
      // Truyền limit vào query parameter
      final response = await _api.get(
        '/api/recommendation/me/daily-nutrition/balanced-recommendations',
        queryParameters: {'limit': limit},
      );

      debugPrint("GetDailyBalancedRecommendations Response: $response");
      
      if (response != null) {
        final dynamic rawData = response['data'] ?? response; 
        return BalancedRecommendationResponse.fromJson(rawData);
      }
      return null;
    } catch (e) {
      debugPrint("🚨 Error fetching balanced recommendations: $e");
      return null;
    }
  }


  // Trong class RecommendRepository của bạn:
  
  // Gọi API cập nhật Profile
  Future<bool> updateNutritionProfile(NutritionProfileModel profile) async {
    try {
      final response = await _api.patch(
        '/api/recommendation/me/daily-nutrition/profile',
        data: profile.toJson(),
      );
      return response['data'] != null;
    } catch (e) {
      debugPrint("Lỗi update Profile: $e");
      return false;
    }
  }

  // GET: Lấy thông tin Profile & Summary
  Future<NutritionProfileModel?> getNutritionProfile() async {
    try {
      final response = await _api.get('/api/recommendation/me/daily-nutrition/profile');
      debugPrint("GetNutritionProfile Response: $response");
      if (response['data'] != null) {
        return NutritionProfileModel.fromJson(response['data']);
      }
      return null;
    } catch (e) {
      debugPrint("🚨 Lỗi GET Nutrition Profile: $e");
      return null;
    }
  }
void printFullLog(String text) {
  final pattern = RegExp('.{1,800}'); // Cắt mỗi 800 ký tự
  pattern.allMatches(text).forEach((match) => debugPrint(match.group(0)));
}

  Future<DailyMealResponse?> getDailyMeals() async {
    try {
      
      final response = await _api.get('/api/recommendation/me/daily-nutrition/meals');
      final jsonString = jsonEncode(response); // Biến object thành chuỗi
      printFullLog("GetDailyMeals Response: $jsonString");
      if (response != null) {
        final prettyJson = const JsonEncoder.withIndent('  ').convert(response);
        log("GetDailyMeals Response:\n$prettyJson", name: "API_MEAL");
      }
      
      if (response != null && response['data'] != null) {
        return DailyMealResponse.fromJson(response['data']);
      }
      return null;
    } catch (e) {
      debugPrint("❌ Failed to fetch daily meals: $e");
      return null;
    }
  }


  Future<bool> updateDailyMeal({
    required String mealUid,
    String? mealName,
    String? mealTime,
    num? quantityMultiplier,
    String? dishUid,
  }) async {
    try {
      debugPrint("🚀 Calling PATCH API: /api/recommendation/me/daily-nutrition/meals/$mealUid");
      
      // Chỉ đóng gói những trường có dữ liệu (khác null)
      final Map<String, dynamic> payload = {};
      if (mealName != null) payload['meal_name'] = mealName;
      if (mealTime != null) payload['meal_time'] = mealTime;
      if (quantityMultiplier != null) payload['quantity_multiplier'] = quantityMultiplier;
      if (dishUid != null) payload['dish_uid'] = dishUid;

      final response = await _api.patch(
        '/api/recommendation/me/daily-nutrition/meals/$mealUid',
        data: payload, // Thay bằng body hoặc data tùy vào ApiClient của bạn (Dio/Http)
      );

      return response != null; // Giả định có response là thành công
    } catch (e) {
      debugPrint("❌ Failed to update meal: $e");
      return false;
    }
  }


  Future<BetterDishResponse?> getBetterDishesForIssue({
    required String dishUid,
    int limit = 5,
  }) async {
    try {
      debugPrint("🚀 Calling API: /api/recommendation/me/dishes/$dishUid/better-for-issue");
      
      final response = await _api.get(
        '/api/recommendation/me/dishes/$dishUid/better-for-issue',
        queryParameters: {'limit': limit},
      );
      debugPrint("GetBetterDishesForIssue Response: $response");
      if (response != null) {
        // Tùy theo cấu trúc API trả về có bọc trong key 'data' hay không. 
        // Dựa theo Swagger ảnh bạn chụp, nó trả thẳng Object ra ngoài luôn.
        return BetterDishResponse.fromJson(response['data']);
      }
      return null;
    } catch (e) {
      debugPrint("❌ Failed to fetch better dishes: $e");
      return null;
    }
  }
}