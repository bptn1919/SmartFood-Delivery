import 'package:flutter/foundation.dart';
import '../../../core/network/api_client.dart'; // Import ApiClient của bạn
import '../models/chef_order_model.dart';
import '../models/chef_order_detail_model.dart';

class ChefOrderRepository {
  final ApiClient _api;

   ChefOrderRepository({ApiClient? api}) : _api = api ?? ApiClient();

  Future<List<ChefOrderModel>> getChefOrders({
    String? search,
    String? status, // PENDING, COMPLETED, CANCELLED...
  }) async {
    try {
      Map<String, dynamic> params = {
        'page_size': 50,
        'sort_type': 'desc', // Mới nhất lên đầu
        'order_by': 'created_at',
      };

      if (search != null && search.isNotEmpty) {
        params['search'] = search;
      }

      // Filter theo status nếu có
      if (status != null && status != "All") {
        params['status'] = status;
      }

      final response = await _api.get(
        '/api/chef/orders/',
        queryParameters: params,
      );

      // Parse Data (Dùng logic an toàn như bài trước)
      List<dynamic> list = [];
      if (response is Map<String, dynamic>) {
        if (response['data'] is Map && response['data']['content'] is List) {
           list = response['data']['content'];
        } else if (response['results'] is List) {
           list = response['results'];
        } else if (response['data'] is List) {
           list = response['data'];
        }
      } else if (response is List) {
        list = response;
      }

      return list.map((e) => ChefOrderModel.fromJson(e)).toList();

    } catch (e) {
      debugPrint("❌ GetOrders Error: $e");
      return [];
    }
  }

  Future<ChefOrderDetailModel?> getOrderDetail(String uid) async {
    try {
      final response = await _api.get('/api/orders/$uid');
      debugPrint("Order Detail Response for UID $uid: $response");
      
      if (response is Map<String, dynamic>) {
         // Xử lý trường hợp bọc trong data hoặc trả về trực tiếp
         final data = response['data'] ?? response; 
         return ChefOrderDetailModel.fromJson(data);
      }
      return null;
    } catch (e) {
      debugPrint("❌ GetOrderDetail Error: $e");
      return null;
    }
  }

  Future<bool> updateOrderStatus(String uid, String newStatus) async {
    try {
      // Giả định API update status (Bạn cần check lại API spec chỗ này)
      // Thường là PUT /api/chef/orders/{uid}/status
      await _api.put(
        '/api/chef/orders/$uid/status', 
        {'status': newStatus}
      );
      return true;
    } catch (e) {
      debugPrint("❌ UpdateStatus Error: $e");
      return false;
    }
  }

  Future<bool> confirmOrder(String uid) async {
    try {
      // API Spec: POST /api/chef/orders/{uid}/confirm
      // Lưu ý: POST thường cần body, nếu không có data thì truyền {} rỗng
      await _api.post(
        '/api/chef/orders/$uid/confirm', 
        {}, 
      );
      
      return true;
    } catch (e) {
      debugPrint("❌ ConfirmOrder Error: $e");
      return false;
    }
  }

  Future<bool> rejectOrder(String uid, String reason) async {
    try {
      await _api.post(
        '/api/chef/orders/$uid/reject', // Endpoint
        {}, // Body (Gửi rỗng vì API không yêu cầu body)
        queryParameters: {'reason': reason}, // 👈 Lý do sẽ tự động được gắn vào URL thành ?reason=...
      );
      return true;
    } catch (e) {
      return false;
    }
  }


  Future<bool> startProcessing(String uid) async {
    try {
      await _api.post(
        '/api/chef/orders/$uid/start-processing', // Endpoint
        {}, // Body (Gửi rỗng vì API không yêu cầu body)
      );
      return true;
    } catch (e) {
      return false;
    }
  }


  Future<bool> startDelivery(String uid) async {
    try {
      await _api.post(
        '/api/chef/orders/$uid/start-delivery', // Endpoint
        {}, // Body (Gửi rỗng vì API không yêu cầu body)
   
      );
      return true;
    } catch (e) {
      return false;
    }
  }


  Future<bool> completeOrder(String uid) async {
    try {
      await _api.post(
        '/api/chef/orders/$uid/complete', // Endpoint
        {}, // Body (Gửi rỗng vì API không yêu cầu body)
      );
      return true;
    } catch (e) {
      return false;
    }
  }




}