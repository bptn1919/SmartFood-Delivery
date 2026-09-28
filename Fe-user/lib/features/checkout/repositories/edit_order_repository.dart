import 'package:flutter/foundation.dart';
import '../../../core/network/api_client.dart';
import '../models/order_draft.dart';
import '../models/payment_method_enum.dart';
import '../models/personal_info_schema.dart';
import '../models/address_item.dart';

class EditOrderRepository {
  final ApiClient _apiClient;

  EditOrderRepository({ApiClient? api}) : _apiClient = api ?? ApiClient();

  // --- HELPER: Normalize Response Data ---
  /// Xử lý việc BE trả về bọc trong { "data": ... } hoặc trả về trực tiếp
  Map<String, dynamic> _normalizeData(dynamic data) {
    if (data is Map<String, dynamic>) {
      if (data.containsKey('data') && data['data'] is Map) {
        return Map<String, dynamic>.from(data['data']);
      }
      return data;
    }
    return {};
  }

  OrderDraft _parseOrderDraft(dynamic data) {
    final body = _normalizeData(data);
    return OrderDraft.fromJson(body);
  }

  // ================= PROFILE =================
  // PATCH /api/checkouts/{uid}/profile
  Future<OrderDraft> editProfileOfOrder({
    required String uid,
    required PersonalInfoSchema payload,
  }) async {
    try {
      final response = await _apiClient.patch(
        '/api/checkouts/$uid/profile',
        data: payload.toJson(),
      );
      return _parseOrderDraft(response);
    } catch (e) {
      debugPrint('❌ [editProfileOfOrder] Error: $e');
      rethrow; // Để tầng trên (Provider/Bloc) xử lý UI báo lỗi
    }
  }

  // ================= PAYMENT METHOD =================
  // PATCH /api/checkouts/{uid}/payment-method?payload=COD|MOMO
  Future<OrderDraft> editPaymentMethodOfOrder({
    required String uid,
    required PaymentMethodEnum paymentMethod,
  }) async {
    try {
      final response = await _apiClient.patch(
        '/api/checkouts/$uid/payment-method',
        queryParameters: {'payload': paymentMethod.apiValue},
      );
      return _parseOrderDraft(response);
    } catch (e) {
      debugPrint('❌ [editPaymentMethodOfOrder] Error: $e');
      rethrow;
    }
  }

  // ================= DELIVERY TIME =================
  // PATCH /api/checkouts/{uid}/delivery-time?payload=HH:mm:ss
  Future<OrderDraft> editDeliveryTimeOfOrder({
    required String uid,
    required String hms, // "HH:mm:ss"
  }) async {
    try {
      final response = await _apiClient.patch(
        '/api/checkouts/$uid/delivery-time',
        queryParameters: {'payload': hms},
      );
      return _parseOrderDraft(response);
    } catch (e) {
      debugPrint('❌ [editDeliveryTimeOfOrder] Error: $e');
      rethrow;
    }
  }

  Future<OrderDraft?> updateDeliveryType({
    required String checkoutUid,
    required int chefId, // 👈 Đổi orderUid thành chefId (kiểu int)
    required String deliveryType, // 'SELF_PICKUP' hoặc 'THIRD_PARTY'
  }) async {
    try {
      // Build payload giống hệt schema UpdateDeliveryTypesPayload ở Backend đã cập nhật
      final payload = {
        "sub_orders": [
          {
            "chef_id": chefId, // 👈 Sửa key từ "order_uid" thành "chef_id"
            "delivery_type": deliveryType
          }
        ]
      };

      debugPrint("🚀 Đang gửi request đổi Delivery Type: $payload");

      // Gọi API PATCH bằng ApiClient
      final res = await _apiClient.patch(
        '/api/checkouts/$checkoutUid/delivery-types', 
        data: payload,
      );

      // Parse JSON trả về để cập nhật bill
      if (res['data'] != null) {
        return OrderDraft.fromJson(res['data']);
      }
      return null;
    } catch (e) {
      debugPrint("❌ Lỗi Update Delivery Type: $e");
      rethrow;
    }
  }

  // ================= ADDRESS (Shared Logic) =================
  // Lưu ý: Logic này có thể trùng với CustomerRepository. 
  // Nếu kiến trúc cho phép, nên tái sử dụng CustomerRepository thay vì viết lại ở đây.
  
  Future<List<AddressItem>> getAllAddresses() async {
  try {
    final response = await _apiClient.get('/api/customer-profiles/addresses');
    
    // Ở đây 'response' đã là data được parse (có thể là List hoặc Map)
    // KHÔNG gọi response.data nữa.

    List<dynamic> listRaw = [];

    if (response is List) {
      // Trường hợp BE trả về thẳng mảng như JSON bạn đưa
      listRaw = response;
    } else if (response is Map) {
      // Trường hợp BE bọc trong một object (vd: có key 'data') 
      // Hoặc lọt vào object báo lỗi của Backend
      if (response.containsKey('data') && response['data'] is List) {
        listRaw = response['data'];
      } else {
        // In ra để bạn bắt mạch xem 5 keys đó chứa gì (rất có thể là lỗi hệ thống)
        debugPrint('⚠️ [getAllAddresses] Backend trả về Map không chứa List. Dữ liệu: $response');
      }
    }

    return listRaw
        .map((e) => AddressItem.fromJson(Map<String, dynamic>.from(e)))
        .toList();
  } catch (e) {
    debugPrint('❌ [getAllAddresses] Error: $e');
    return [];
  }
}

  Future<AddressItem> createCustomerAddress({
    required String address,
    required String street,
    required String ward,
    required String district,
    required String city,
    double? latitude,
    double? longitude,
  }) async {
    try {
      final response = await _apiClient.post(
        '/api/customer-profiles/',
        {
          'address': address,
          'street': street,
          'ward': ward,
          'district': district,
          'city': city,
          'selected': false,
          'latitude': latitude,
          'longitude': longitude,
        },
      );

      final body = _normalizeData(response.data);
      return AddressItem.fromJson(body);
    } catch (e) {
      debugPrint('❌ [createCustomerAddress] Error: $e');
      rethrow;
    }
  }

  // PATCH /api/checkouts/{uid}/delivery-address/{address_id}
  Future<OrderDraft> setOrderDeliveryAddress({
    required String uid,
    required int addressId,
  }) async {
    try {
      final response = await _apiClient.patch(
        '/api/checkouts/$uid/delivery-address/$addressId',
      );
      return _parseOrderDraft(response);
    } catch (e) {
      debugPrint('❌ [setOrderDeliveryAddress] Error: $e');
      rethrow;
    }
  }
}