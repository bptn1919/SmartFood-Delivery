import 'package:flutter/foundation.dart';
import 'package:testing/features/home/models/dish_ingredients_model.dart';
import 'package:testing/features/home/models/dish_location_model.dart';
import 'package:testing/features/home/models/nearby_chef_model.dart';
import '../../../core/network/api_client.dart'; 
import '../models/dish_model.dart';
import '../models/dish_availability_model.dart';

class DishRepository {
  final ApiClient _api;

  DishRepository({ApiClient? api}) : _api = api ?? ApiClient();

  

  Future<List<DishModel>> getAllDishes({
    String? search, 
    String? category,
    String? sortBy,
    int page = 1,
    int pageSize = 20
  }) async {
    try {
      String query = '?page=$page&page_size=$pageSize';
      if (search != null && search.isNotEmpty) query += '&search=$search';
      if (category != null) query += '&categories=$category'; 

      if (sortBy != null && sortBy.isNotEmpty) {
        query += '&sort_by=$sortBy';
      }
      debugPrint("🚀 [Sort Check] Gọi API: /api/dishes/$query");
      final response = await _api.get('/api/dishes/$query');

      debugPrint("📥 API Dishes Response: $response");

      List<dynamic> list = [];

      // ✅ LOGIC PARSING MỚI (Khớp với Log)
      if (response is Map) {
        // Trường hợp 1: data -> content (Cấu trúc hiện tại của bạn)
        if (response['data'] != null && response['data'] is Map) {
           final innerData = response['data'];
           if (innerData['content'] is List) {
             list = innerData['content'];
           } else if (innerData['items'] is List) {
             list = innerData['items'];
           }
        }
        // Trường hợp 2: content nằm ngay bên ngoài (Dự phòng)
        else if (response['content'] is List) {
          list = response['content'];
        }
      }

      if (list.isEmpty && response is Map) {
         debugPrint("⚠️ Vẫn không tìm thấy List. Check lại logic parsing!");
      }

      return list.map((e) => DishModel.fromJson(e)).toList();
    } catch (e) {
      debugPrint("❌ Error Parsing Dishes: $e");
      return [];
    }
  }


  Future<List<DishModel>> getTopDishes() async {
    try {
      // Giả định prefix của controller này là /api/dishes
      // Truyền thêm param limit=10 nếu cần, hoặc bỏ qua để dùng default của BE
      final response = await _api.get('/api/dishes/top?limit=10');

       debugPrint("🚀 [Load Data] Top Dishes: ${response}");

      List<dynamic> list = _extractList(response);

      return list.map((e) => DishModel.fromJson(e)).toList();
    } catch (e) {
      debugPrint("GetTopDishes Error: $e");
      return [];
    }
  }

  // ===========================================================================
  // 2. GET DISHES IN MENU
  // Endpoint: GET /api/menus/{uid}/dishes
  // Swagger Page 45: Trả về List trực tiếp [{}, {}]
  // ===========================================================================
  Future<List<DishModel>> getDishesInMenu(String menuUid) async {
    try {
      final response = await _api.get('/api/menus/$menuUid/dishes');
      final list = _extractList(response);
      return list.map((e) => DishModel.fromJson(e)).toList();
    } catch (e) {
      debugPrint("GetDishesInMenu Error: $e");
      return [];
    }
  }

  // ===========================================================================
  // 3. GET DISH AVAILABILITY
  // Endpoint: GET /api/dishes/{uid}/availabilities
  // Swagger Page 24: Trả về { "availabilities": [...] }
  // ===========================================================================
  Future<List<DishAvailabilityModel>> getDishAvailabilities(String dishUid) async {
    try {
      final response = await _api.get('/api/dishes/$dishUid/availabilities');

      List<dynamic> list = [];
      if (response['availabilities'] is List) {
        list = response['availabilities'];
      } else if (response['data'] is Map) {
        // Đôi khi backend bọc thêm 1 lớp data
        list = response['data']['availabilities'] ?? [];
      }

      return list.map((e) => DishAvailabilityModel.fromJson(e)).toList();
    } catch (e) {
      debugPrint("GetDishAvailabilities Error: $e");
      return [];
    }
  }

  // Helper function để xử lý JSON không nhất quán
  List<dynamic> _extractList(dynamic data) {
    if (data is List) return data;
    if (data is Map) {
      if (data['content'] is List) return data['content'];
      if (data['data'] is List) return data['data'];
      if (data['results'] is List) return data['results'];
    }
    return [];
  }

  Future<DishModel?> getDishDetail(String dishUid) async {
    try {
      final response = await _api.get('/api/dishes/$dishUid');
      debugPrint("GetDishDetail Response: $response");
      if (response != null && response['data'] != null) {
        return DishModel.fromJson(response['data']);
      } else {
        debugPrint("Error in getting detailed dish" );
        return null;
      }
    } catch (e) {
      debugPrint("Exception when getDishDetail: $e");
      return null;
    }
  }

  /// Thêm món ăn vào danh sách yêu thích
  Future<bool> addFavoriteDish(String dishUid) async {
    try {
      // TECH LEAD FIX: Nối thẳng dishUid vào URL và truyền một Map rỗng cho tham số body
      final response = await _api.post(
        '/api/customer-profiles/favorite-dish/$dishUid',
        {}, // Truyền body rỗng để thỏa mãn cấu trúc ApiClient của bạn
      );

      debugPrint("AddFavoriteDish Response: $response");

      // (Tùy chọn) API này trả về cả cục JSON thông tin món ăn. 
      // Nếu ở UI chỉ cần đổi icon trái tim (đỏ/trắng) thì chỉ cần check thành công là đủ.
      if (response != null) {
        return true;
      }
      return false;
      
    } catch (e) {
      debugPrint("🚨 Error in addFavoriteDish: $e");
      return false; 
    }
  }

  Future<bool> removeFavoriteDish(String dishUid) async {
    try {
      // Dùng method PATCH theo đúng tài liệu Swagger
      final response = await _api.patch(
        '/api/customer-profiles/favorite-dish/$dishUid',
      );
      
      // Tùy thuộc vào _apiClient của bạn trả về data thẳng hay bọc trong 'data'
      // Ở đây ta chỉ cần biết gọi thành công (không throw catch) là trả về true
      if (response != null) {
         // Bạn cũng có thể log ra để check: debugPrint("Removed Fav: $response");
         return true;
      }
      return false;
    } catch (e) {
      debugPrint("❌ LỖI removeFavoriteDish: $e");
      return false;
    }
  }



  /// Lấy cấu trúc cây vị trí địa lý
  Future<List<DishLocationModel>> getDishLocationTree() async {
    try {
      final response = await _api.get('/api/dish-locations/tree');
      
      if (response != null) {
        // Handle trường hợp response được bọc trong object {"data": [...]}
        final dynamic rawData = response['data'] ?? response; 
        
        if (rawData is List) {
          return rawData.map((e) => DishLocationModel.fromJson(e as Map<String, dynamic>)).toList();
        }
      }
      return [];
    } catch (e) {
      debugPrint("🚨 Error in getDishLocationTree: $e");
      return [];
    }
  }

  Future<List<DishLocationModel>> getDishLocations() async {
    try {
      final response = await _api.get('/api/dish-locations/');
      
      if (response != null) {
        final dynamic rawData = response['data'] ?? response; 
        
        if (rawData is List) {
          return rawData.map((e) => DishLocationModel.fromJson(e as Map<String, dynamic>)).toList();
        }
      }
      return [];
    } catch (e) {
      debugPrint("🚨 Error in getDishLocations: $e");
      return [];
    }
  }


  Future<DishIngredientsResponse?> getDishIngredients(String dishUid) async {
    try {
      // Gọi API với path parameter là UID của món ăn
      final response = await _api.get('/api/dishes/$dishUid/ingredients');
      debugPrint("GetDishIngredients Response: $response");
      
      if (response != null) {
        // Handle trường hợp response bọc trong 'data'
        final dynamic rawData = response['data'] ?? response; 
        return DishIngredientsResponse.fromJson(rawData);
      }
      return null;
    } catch (e) {
      debugPrint("🚨 Error fetching dish ingredients: $e");
      return null;
    }
  }


  Future<List<NearbyChefModel>> getNearbyChefs({
    required double lat, 
    required double lng, 
    double radiusKm = 5.0
  }) async {
    try {
      // Ép kiểu query parameters
      final String query = '?lat=$lat&lng=$lng&radius_km=$radiusKm';
      debugPrint("🚀 Calling API: /api/tracking/nearby$query");
      
      final response = await _api.get('/api/tracking/chefs/nearby$query');

      debugPrint("check check check: $response");
      
      // Bóc tách lớp "data" và "results" dựa trên JSON thực tế của bạn
      if (response != null && response['data'] != null) {
        final List<dynamic> results = response['data']['results'] ?? [];
        
        // Map data thô thành Model gọn gàng
        return results.map((e) => NearbyChefModel.fromJson(e)).toList();
      }
      return [];
    } catch (e) {
      debugPrint("❌ Error fetching nearby chefs: $e");
      return [];
    }
  }
}