import 'package:flutter/material.dart';
import 'package:testing/core/network/api_client.dart';
import 'package:testing/features/recommend/models/ingredient_model.dart';

class IngredientRepository {
  final ApiClient _api;

  IngredientRepository({ApiClient? api}) : _api = api ?? ApiClient();

  /// Gọi API lấy danh sách nguyên liệu và Parse sang Model
  Future<List<IngredientModel>> getIngredients({
    String? search,
    String? categories, 
    double? minEnergy,
    double? maxEnergy,
    String? orderBy, 
    String? sortType, 
    int? pageSize = 50, 
    int? page = 1, 
  }) async {
    try {
      final Map<String, dynamic> queryParams = {};
      
      if (search != null && search.trim().isNotEmpty) {
        queryParams['search'] = search.trim();
      }
      if (categories != null && categories.trim().isNotEmpty) {
        queryParams['categories'] = categories.trim();
      }
      if (minEnergy != null) {
        queryParams['min_energy'] = minEnergy.toString();
      }
      if (maxEnergy != null) {
        queryParams['max_energy'] = maxEnergy.toString();
      }
      if (orderBy != null) {
        queryParams['order_by'] = orderBy;
      }
      if (sortType != null) {
        queryParams['sort_type'] = sortType;
      }
      if (pageSize != null) {
        queryParams['page_size'] = pageSize.toString();
      }
      if (page != null) {
        queryParams['page'] = page.toString();
      }

      // 1. Gọi API lấy raw data
      final response = await _api.get(
        '/api/ingredients/',
        queryParameters: queryParams,
      );

      debugPrint("GetIngredients Response: $response");

      // 2. TECH LEAD FIX: Parse JSON array sang List<IngredientModel>
      // Kiểm tra xem response có chứa key 'content' và nó là một List hay không
      if (response != null && response['data'] != null && response['data']['content'] is List) {
        final List<dynamic> contentData = response['data']['content'];
        
        // Map từng phần tử JSON thành IngredientModel
        return contentData.map((json) => IngredientModel.fromJson(json)).toList();
      }

      // Trả về list rỗng nếu không có data hoặc API trả cấu trúc khác
      return []; 
      
    } catch (e) {
      debugPrint("🚨 Error in getIngredients: $e");
      rethrow; 
    }
  }



  /// Lấy danh sách nguyên liệu dị ứng của user hiện tại
  Future<List<IngredientModel>> getMyAllergies() async {
    try {
      final response = await _api.get('/api/ingredients/me/allergies');
      debugPrint("GetMyAllergies Response: $response");
      // TECH LEAD FIX: Vì API này trả về thẳng một List (mảng JSON []) chứ không bọc trong 'content'
      if (response != null && response['data'] is List) {
        final List<dynamic> allergyData = response['data'];
        return allergyData.map((json) {
          return IngredientModel(
            uid: json['ingredient_uid'] as String?,
            name: json['ingredient_name'] as String?,
            category: json['category'] as String?,
          );
        }).toList();
      }

      return [];
      
    } catch (e) {
      debugPrint("🚨 Error in getMyAllergies: $e");
      rethrow; 
    }
  }

  /// Thêm một nguyên liệu vào danh sách dị ứng của user
  Future<bool> addAllergyIngredient(String ingredientUid) async {
    try {
      // Gọi API POST, truyền uid vào body (data) theo đúng chuẩn JSON Swagger yêu cầu
      await _api.post(
        '/api/ingredients/me/allergies',
        {
          "ingredient_uid": ingredientUid,
        },
      );
      
      return true; // Trả về true nếu API chạy lọt qua không bị crash (status 20x)
    } catch (e) {
      debugPrint("🚨 Error in addAllergyIngredient: $e");
      return false; // Trả về false nếu BE báo lỗi (ví dụ: duplicate, lỗi server...)
    }
  }

  /// Xóa một nguyên liệu khỏi danh sách dị ứng của user
  Future<bool> removeAllergyIngredient(String ingredientUid) async {
    try {
      await _api.delete('/api/ingredients/me/allergies/$ingredientUid');
      
      return true; // Thành công
    } catch (e) {
      debugPrint("🚨 Error in removeAllergyIngredient: $e");
      return false; // Thất bại
    }
  }



  /// Lấy danh sách nguyên liệu yêu thích của user hiện tại
  Future<List<IngredientModel>> getMyFavorites() async {
    try {
      final response = await _api.get('/api/ingredients/me/favourites');
      debugPrint("GetMyFavorites Response: $response");
      // TECH LEAD FIX: Vì API này trả về thẳng một List (mảng JSON []) chứ không bọc trong 'content'
      if (response != null && response['data'] is List) {
        final List<dynamic> favoriteData = response['data'];
        return favoriteData.map((json) {
          return IngredientModel(
            uid: json['ingredient_uid'] as String?,
            name: json['ingredient_name'] as String?,
            category: json['category'] as String?,
          );
        }).toList();
      }

      return [];
      
    } catch (e) {
      debugPrint("🚨 Error in getMyFavourites: $e");
      rethrow; 
    }
  }

  /// Thêm một nguyên liệu vào danh sách yêu thích của user
  Future<bool> addFavoriteIngredient(String ingredientUid) async {
    try {
      // Gọi API POST, truyền uid vào body (data) theo đúng chuẩn JSON Swagger yêu cầu
      await _api.post(
        '/api/ingredients/me/favourites',
        {
          "ingredient_uid": ingredientUid,
        },
      );
      
      return true; // Trả về true nếu API chạy lọt qua không bị crash (status 20x)
    } catch (e) {
      debugPrint("🚨 Error in addFavoriteIngredient: $e");
      return false; // Trả về false nếu BE báo lỗi (ví dụ: duplicate, lỗi server...)
    }
  }

  /// Xóa một nguyên liệu khỏi danh sách yêu thích của user
  Future<bool> removeFavoriteIngredient(String ingredientUid) async {
    try {
      await _api.delete('/api/ingredients/me/favourites/$ingredientUid');
      
      return true; // Thành công
    } catch (e) {
      debugPrint("🚨 Error in removeFavoriteIngredient: $e");
      return false; // Thất bại
    }
  }
}