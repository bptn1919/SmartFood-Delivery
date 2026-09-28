import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http; // Dùng http riêng cho upload S3
import 'package:testing/features/chef_manager/models/menu_dish_model.dart';
import 'package:testing/features/home/models/dish_location_model.dart';
import 'package:testing/features/home/models/menu_model.dart';
import '../../../core/network/api_client.dart';
import '../../home/models/dish_model.dart'; // Đảm bảo đúng đường dẫn

class ChefManagementRepository {
  final ApiClient _api;

  ChefManagementRepository({ApiClient? api}) : _api = api ?? ApiClient();

  // ===========================================================================
  // 0. UPGRADE TO CHEF (Theo PDF Page 2)
  // ===========================================================================
  // Future<bool> upgradeToChef() async {
  //   try {
  //     // Endpoint dự đoán dựa trên tài liệu (POST Upgrade To Chef)
  //     await _api.post('/api/auth/upgrade-to-chef', {});
  //     return true;
  //   } catch (e) {
  //     debugPrint("Upgrade Chef Error: $e");
  //     return false;
  //   }
  // }

  // lib/data/repositories/chef_management_repository.dart

  Future<bool> upgradeToChef({
  required String bio,
  required String specialty,
  required String bankName,
  required String bankCode, // 👇 Bổ sung thêm biến này
  required String bankAccount,
  required String bankAccountName,
  required String avatarId,

  required String kitchenAddress,
  required String kitchenStreet,
  required String kitchenWard,
  required String kitchenDistrict,
  required String kitchenCity,
  required double? kitchenLat,
  required double? kitchenLng,
}) async {
  try {
    // Body chuẩn khớp 100% với Backend Schema (như trong ảnh)
    final body = {
      "chef_profile": {
        "bio": bio.isNotEmpty ? bio : null, 
        "specialty": specialty.isNotEmpty ? specialty : null,
        "avatar_id": avatarId,

        "kitchen_address": kitchenAddress.isNotEmpty ? kitchenAddress : null,
        "kitchen_street": kitchenStreet.isNotEmpty ? kitchenStreet : null,
        "kitchen_ward": kitchenWard.isNotEmpty ? kitchenWard : null,
        "kitchen_district": kitchenDistrict.isNotEmpty ? kitchenDistrict : null,
        "kitchen_city": kitchenCity.isNotEmpty ? kitchenCity : null,
        "kitchen_latitude": kitchenLat,
        "kitchen_longitude": kitchenLng,
      },
      "chef_payment": {
        "bank_name": bankName,
        "bank_code": bankCode,
        "bank_account_number": bankAccount,
        "bank_account_name": bankAccountName
      }
    };

    final response = await _api.post(
      '/api/auth/upgrade-to-chef',
      body, // Đảm bảo HTTP client của bạn tự động encode JSON nhé (ví dụ: jsonEncode(body))
    );

    return true;
  } catch (e) {
    debugPrint("❌ Upgrade Chef Error: $e");
    return false;
  }
}

  // ===========================================================================
  // 1. CREATE DISH FULL FLOW (4 Bước - Theo PDF Page 3 & 4)
  // ===========================================================================
  Future<bool> createDishFullFlow({
    required String name,
    required String description,
    required double price,
    required String category, 
    required String status,     // MỚI: Thêm status
    required int locationId,    // MỚI: Thêm location_id
    required File imageFile,
  }) async {
    try {
      // BƯỚC 1: Get Presigned URL
      final fileName = imageFile.path.split('/').last;
      final fileSize = await imageFile.length();
      
      final presignedData = await _getPresignedUrl(fileName, fileSize);
      if (presignedData == null) {
        debugPrint("❌ Step 1 Failed: Cannot get presigned URL");
        return false;
      }

      final String uploadUrl = presignedData['url'];
      final String attachmentUid = presignedData['uid'];

      // BƯỚC 2: Upload Binary to S3
      final uploadSuccess = await _uploadToS3(uploadUrl, imageFile);
      if (!uploadSuccess) {
         debugPrint("❌ Step 2 Failed: S3 Upload failed");
         return false;
      }

      // BƯỚC 3: Complete Upload
      final completeSuccess = await _completeUpload(attachmentUid);
      if (!completeSuccess) {
        debugPrint("❌ Step 3 Failed: Complete signal failed");
        return false;
      }

      // BƯỚC 4: Create Dish API
      return await _createDishApi(
        name: name,
        description: description,
        price: price,
        category: category,
        status: status,               // Truyền thêm
        locationId: locationId,       // Truyền thêm
        attachmentUid: attachmentUid,
      );

    } catch (e) {
      debugPrint("❌ Create Dish Flow Error: $e");
      return false;
    }
  }

  // 💡 HÀM GỌI API CUỐI CÙNG SẼ CÓ PAYLOAD NHƯ SAU:
  Future<bool> _createDishApi({
    required String name,
    required String description,
    required double price,
    required String category,
    required String status,
    required int locationId,
    required String attachmentUid,
  }) async {
    try {
      // Map đúng 100% theo Swagger hình ảnh bạn cung cấp
      final payload = {
        "name": name,
        "category": category,
        "description": description,
        "price": price.toInt(), // API đang nhận số nguyên (0), có thể để int hoặc double tùy backend strict đến mức nào
        "status": status,
        "attachment_uid": attachmentUid,
        "location_id": locationId,
      };

      final response = await _api.post('/api/dishes/', payload);
      return response != null; // Xử lý check success dựa trên BaseResponse của dự án
    } catch (e) {
      debugPrint("❌ _createDishApi Error: $e");
      return false;
    }
  }

  // --- CÁC HÀM CON (PRIVATE) ---

  // Future<Map<String, dynamic>?> _getPresignedUrl(String fileName, int fileSize) async {
  //   try {
  //     // PDF yêu cầu body: filename, file_size, attachment_type="DISH"
  //     final res = await _api.post('/api/attachments/presigned-url', {
  //       "filename": fileName,
  //       "file_size": fileSize,
  //       "attachment_type": "DISH"
  //     });
  //     // Response: { "data": { "uid": "...", "url": "..." } }
  //     if (res['data'] != null) {
  //       return {
  //         "uid": res['data']['uid'],
  //         "url": res['data']['url'],
  //       };
  //     }
  //     return null;
  //   } catch (e) {
  //     return null;
  //   }
  // }

  // Trong ChefManagementRepository

Future<Map<String, dynamic>?> _getPresignedUrl(String fileName, int fileSize) async {
  try {
    debugPrint("---------------- DEBUG START: GET PRESIGNED URL ----------------");
    
    final res = await _api.post('/api/attachments/presigned-url', {
      "file_name": fileName,
      "file_size": fileSize,
      "attachment_type": "DISH"
    });

    // 1. In ra Raw Response để mắt thường kiểm tra
    debugPrint("📥 Raw Response from Server: $res");

    // 2. Kiểm tra từng cấp độ (Trap Null)
    if (res == null) {
      debugPrint("❌ CRITICAL: Response trả về hoàn toàn NULL");
      return null;
    }

    if (res['data'] == null) {
      debugPrint("❌ ERROR: Field 'data' bị NULL");
      return null;
    }

    final data = res['data'];
    
    // 3. Kiểm tra chi tiết bên trong 'data'
    final uid = data['uid'];
    final url = data['url'];

    if (uid == null) debugPrint("❌ ERROR: Field 'uid' bị NULL hoặc thiếu");
    if (url == null) debugPrint("❌ ERROR: Field 'url' bị NULL hoặc thiếu");

    // 4. Nếu 1 trong 2 thiếu, return null để dừng flow
    if (uid == null || url == null) {
       debugPrint("⛔ Dừng flow vì thiếu dữ liệu quan trọng");
       return null;
    }

    debugPrint("✅ Data OK: UID=$uid | URL=${url.toString().substring(0, 20)}...");
    debugPrint("---------------- DEBUG END ----------------");

    return {
      "uid": uid.toString(),
      "url": url.toString(),
    };
  } catch (e) {
    debugPrint("🔥 EXCEPTION _getPresignedUrl: $e");
    return null;
  }
}

  Future<bool> _uploadToS3(String url, File file) async {
    try {
      final bytes = await file.readAsBytes();
      final response = await http.put(
        Uri.parse(url),
        body: bytes,
        headers: {
          "Content-Type": "image/jpeg", // Hoặc check đuôi file để set dynamic
        },
      );
      return response.statusCode == 200;
    } catch (e) {
      return false;
    }
  }

  Future<bool> _completeUpload(String uid) async {
    try {
      await _api.put('/api/attachments/$uid/completed', {});
      return true;
    } catch (e) {
      return false;
    }
  }
  Future<List<DishLocationModel>> getDishLocations() async {
    try {
      debugPrint("🚀 [API REQUEST] GET /api/dish-locations/");
      final response = await _api.get('/api/dish-locations/tree');
      
      List<dynamic> dataList = [];
      if (response is List) {
        dataList = response;
      } else if (response['data'] != null && response['data'] is List) {
        dataList = response['data'];
      }

      return dataList.map((e) => DishLocationModel.fromJson(e)).toList();
    } catch (e) {
      debugPrint("❌ [API ERROR] getDishLocations: $e");
      return [];
    }
  }

  Future<List<DishModel>> getMyDishes({String category = "All"}) async {
    try {
      // ---------------------------------------------------------
      // BƯỚC 1: GỌI API PHỤ ĐỂ LẤY CHEF_ID
      // ---------------------------------------------------------
      int? chefId;
      try {
        // Gọi lại endpoint /is-chef mà bạn đã viết
        final authResponse = await _api.get('/api/chef-profiles/is-chef-id');
        
        // Cấu trúc response: { "data": { "is_chef": true, "chef_id": 12 } }
        if (authResponse is Map<String, dynamic> && 
            authResponse['data'] is Map<String, dynamic>) {
            
            final data = authResponse['data'];
            if (data['is_chef'] == true) {
              chefId = data['chef_id'];
            }
        }
      } catch (e) {
        debugPrint("⚠️ Lỗi khi lấy Chef ID: $e");
      }

      // Nếu sau bước 1 mà vẫn không có chefId -> Dừng luôn
      if (chefId == null) {
        debugPrint("❌ User này chưa phải Chef hoặc API lỗi, không thể lấy món ăn.");
        return [];
      }

      // ---------------------------------------------------------
      // BƯỚC 2: GỌI API CHÍNH (LẤY DANH SÁCH MÓN ĂN)
      // ---------------------------------------------------------
      
      // Chuẩn bị Params
      Map<String, dynamic> params = {
        'chef_id': chefId, // 👈 Đã có ID để điền vào đây
        'page_size': 100,  // Lấy nhiều chút
      };

      // Xử lý Category (Logic cũ)
      if (category != "All") {
        String? apiCategory;
        if (category == "Meals") apiCategory = "FOOD";
        else if (category == "Drinks") apiCategory = "BEVERAGES"; // Có 's'
        else if (category == "Desserts") apiCategory = "DESSERT";

        if (apiCategory != null) {
          params['categories'] = apiCategory;
        }
      }

      debugPrint("🔍 [FILTER DEBUG] Category Input: '$category' -> Params gửi đi: $params");

      // Gọi API Dish
      final response = await _api.get(
        '/api/dishes/', 
        queryParameters: params, 
      );

      // ---------------------------------------------------------
      // BƯỚC 3: PARSE DATA
      // ---------------------------------------------------------
      List<dynamic> list = [];
      if (response is Map<String, dynamic>) {
        // 👇 LOGIC MỚI: Bắt đúng cấu trúc "data" -> "content"
        if (response['data'] is Map<String, dynamic> && 
            response['data']['content'] is List) {
          
          list = response['data']['content']; // ✅ Bắt dính ở đây
          
        } else if (response['results'] is List) {
          // Fallback cho Django mặc định
          list = response['results'];
        } else if (response['data'] is List) {
          // Fallback cho cấu trúc data phẳng
          list = response['data'];
        }
      } else if (response is List) {
        list = response;
      }
      
      debugPrint("✅ [DEBUG] Parsed items: ${list.length}");

      return list.map((e) => DishModel.fromJson(e)).toList();

    } catch (e) {
      debugPrint("❌ GetMyDishes Error: $e");
      return [];
    }
  }

  Future<bool> toggleDishStatus(String dishId, bool isActive) async {
    try {
      await _api.post('/api/dishes/$dishId/status', {
        "status": isActive ? "AVAILABLE" : "UNAVAILABLE"
      });
      return true;
    } catch (e) {
      return false;
    }
  }

  Future<bool> checkIsChef() async {
    try {
      // 👇 Biến này CHÍNH LÀ dữ liệu (Map), không phải Response object
      final data = await _api.get('/api/auth/is-chef');

      // Debug để chắc chắn (Optional)
      debugPrint("CheckIsChef Data: $data");

      // 👇 Sửa logic check: Không gọi .statusCode hay .data nữa
      if (data is Map<String, dynamic>) {
        // Truy cập trực tiếp vào Key
        final innerData = data['data']; 
        
        // 👇 2. Kiểm tra null và lấy "is_chef"
        if (innerData is Map<String, dynamic>) {
           return innerData['is_chef'] == true;
        }
      }
      
      return false;
    } catch (e) {
      debugPrint("⚠️ [CheckIsChef] Error: $e");
      return false; // Mặc định là false nếu lỗi
    }
  }

  Future<Map<String, dynamic>> getChefStatus() async {
    try {
      final response = await _api.get('/api/auth/is-chef-id');
      
      if (response is Map<String, dynamic>) {
         final data = response['data']; // Lấy data bên trong
         if (data != null) {
           return {
             "isChef": data['is_chef'] == true,
             "chefId": data['chef_id'] // Có thể null
           };
         }
      }
      return {"isChef": false, "chefId": null};
    } catch (e) {
      return {"isChef": false, "chefId": null};
    }
  }


  Future<List<MenuModel>> getMyMenus() async {
    try {
      debugPrint("🚀 [API REQUEST] GET /api/menus/mine");
      final response = await _api.get('/api/menus/mine');
      
      // Xử lý bóc tách List dựa theo Swagger
      List<dynamic> dataList = [];
      
      // Nếu API trả về mảng trực tiếp [ {...}, {...} ]
      if (response is List) {
        dataList = response;
      } 
      // Nếu API trả về bọc trong data: { "data": [ {...} ] }
      else if (response['data'] != null && response['data'] is List) {
        dataList = response['data'];
      }

      return dataList.map((e) => MenuModel.fromJson(e)).toList();
    } catch (e) {
      debugPrint("❌ [API ERROR] getMyMenus: $e");
      // Nếu lỗi (hoặc chưa có menu nào), trả về mảng rỗng để không crash UI
      return []; 
    }
  }


  Future<void> updateMenuInfo(String uid, String name, String description, String status) async {
    try {
      final Map<String, dynamic> payload = {
        "name": name,
        "description": description.isEmpty ? null : description, // Backend cho phép null
        "status": status,
      };

      debugPrint("🚀 [API REQUEST] PUT /api/menus/$uid");
      await _api.put('/api/menus/$uid', payload);
      debugPrint("✅ [API SUCCESS] Menu updated.");
    } catch (e) {
      debugPrint("❌ [API ERROR] updateMenuInfo: $e");
      rethrow;
    }
  }

  // 🔗 REAL API: Lấy danh sách món ăn trong Menu (GET /api/menus/{uid}/all-dishes)
  Future<List<MenuDishModel>> getDishesInMenu(String menuUid) async {
    try {
      debugPrint("🚀 [API REQUEST] GET /api/menus/$menuUid/all-dishes");
      final response = await _api.get('/api/menus/$menuUid/all-dishes');
      
      List<dynamic> dataList = [];
      if (response is List) {
        dataList = response;
      } else if (response['data'] != null && response['data'] is List) {
        dataList = response['data'];
      }

      return dataList.map((e) => MenuDishModel.fromJson(e)).toList();
    } catch (e) {
      debugPrint("❌ [API ERROR] getDishesInMenu: $e");
      return []; 
    }
  }


  Future<List<DishModel>> getAllMyDishesForMenu() async {
    try {
      debugPrint("🚀 [API REQUEST] GET /api/dishes/mine");
      final response = await _api.get('/api/dishes/mine');

      debugPrint("✅ [API SUCCESS] getAllMyDishesForMenu: Received response $response");
      
      // API có phân trang, mảng data nằm trong key "content"
      if (response['data'] != null && response['data']['content'] != null) {
        final List<dynamic> dataList = response['data']['content'];
        return dataList.map((e) => DishModel.fromJson(e)).toList();
      }
      return [];
    } catch (e) {
      debugPrint("❌ [API ERROR] getAllMyDishesForMenu: $e");
      return [];
    }
  }

  // 🔗 REAL API: Add Dish vào Menu
  Future<void> addDishToMenu(String menuUid, String dishUid) async {
    try {
      final payload = {
        "dish_uid": dishUid,
        "position": 0,    // Mặc định cho vị trí đầu tiên hoặc backend tự xử lý
        "active": true,   // Mặc định active
      };

      debugPrint("🚀 [API REQUEST] POST /api/menus/$menuUid/add-dish/");
      await _api.post('/api/menus/$menuUid/add-dish/', payload);
      debugPrint("✅ [API SUCCESS] Added dish $dishUid to menu $menuUid");
    } catch (e) {
      debugPrint("❌ [API ERROR] addDishToMenu: $e");
      rethrow;
    }
  }

  Future<MenuModel> createMenu(String name, String description, String status) async {
    try {
      final Map<String, dynamic> payload = {
        "name": name,
        "description": description.isEmpty ? null : description,
        "status": status,
      };

      debugPrint("🚀 [API REQUEST] POST /api/menus");
      final response = await _api.post('/api/menus', payload);
      
      // Xử lý bọc dữ liệu (Tùy theo cấu trúc BaseResponse của bạn)
      Map<String, dynamic> responseData;
      if (response['data'] != null && response['data'] is Map) {
        responseData = response['data'];
      } else {
        responseData = response;
      }

      debugPrint("✅ [API SUCCESS] Menu created: ${responseData['uid']}");
      
      // Trả về thẳng object MenuModel để UI dùng điều hướng
      return MenuModel.fromJson(responseData);
      
    } catch (e) {
      debugPrint("❌ [API ERROR] createMenu: $e");
      rethrow;
    }
  }
}