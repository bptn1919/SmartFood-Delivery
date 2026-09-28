import 'package:flutter/material.dart'; // Để dùng debugPrint
import 'package:testing/features/checkout/models/payos_payment_model.dart';
import 'package:testing/features/checkout/models/place_order_response_model.dart';
// Import ApiClient từ Core Module (như đã quy ước các bước trước)
import '../../../core/network/api_client.dart';
import '../../../core/network/api_constants.dart';
import '../models/cart_model.dart';
import '../../checkout/models/order_draft.dart';

class CartRepository {
  final ApiClient _api;

  CartRepository({ApiClient? api}) : _api = api ?? ApiClient();

  //String _yyyyMmDd(DateTime d) => '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
  //String _fmt(DateTime d) => _yyyyMmDd(d);

  // Thay đổi kiểu trả về từ String? thành Map<String, dynamic>?
  Future<PlaceOrderResponse?> placeOrder(
    String checkoutUid, {
    List<Map<String, dynamic>>? subOrderSchedules,
  }) async {
    try {
      final payload = <String, dynamic>{};
      if (subOrderSchedules != null && subOrderSchedules.isNotEmpty) {
        payload['sub_order_schedules'] = subOrderSchedules;
      }

      debugPrint('🚀 [placeOrder] Payload: $payload'); // Log payload trước khi gửi

      final response = await _api.post(
        '/api/checkouts/$checkoutUid/place-order',
        payload,
      );

      debugPrint(
          '📡 [placeOrder] Raw response: $response'); // Log thô để kiểm tra

      if (response != null) {
        // Trích xuất phần data nếu API bọc trong key 'data'
        final data =
            response is Map<String, dynamic> && response.containsKey('data')
                ? response['data']
                : response;

        // Ép kiểu data thành Map để xử lý và trả về
        if (data is Map<String, dynamic>) {
          // Giữ nguyên đoạn debug mảng orders của bạn cho dễ theo dõi
          if (data.containsKey('orders')) {
            final List<dynamic> ordersList = data['orders'];
            if (ordersList.isNotEmpty) {
              debugPrint("============================================");
              debugPrint("📦 KIỂM TRA MÃ ORDER TỪ BACKEND TRẢ VỀ:");
              for (var i = 0; i < ordersList.length; i++) {
                final o = ordersList[i];
                debugPrint(
                    " - Đơn $i | Order UID: ${o['uid']} | Chef ID: ${o['chef_id']}");
              }
              debugPrint("============================================");
            }
          }

          // QUAN TRỌNG NHẤT LÀ Ở ĐÂY: Trả về toàn bộ Object thay vì 1 String
          return PlaceOrderResponse.fromJson(data);
        }
      }

      debugPrint(
          "❌ Place Order Failed: Dữ liệu response không hợp lệ hoặc rỗng.");
      return null;
    } catch (e) {
      debugPrint("❌ Place Order Error: $e");
      return null;
    }
  }

  Future<PayOSPaymentModel?> getPayOSQrDetails(String orderCode) async {
    try {
      final response = await _api.get('/api/payment/payos/$orderCode/qr');
      if (response.statusCode == 200) {
        // Mapping dữ liệu từ API trả về vào Model của bạn
        return PayOSPaymentModel.fromJson(response.data);
      }
      return null;
    } catch (e) {
      return null;
    }
  }

  Future<String?> getPaymentStatus(String paymentUid) async {
    try {
      debugPrint("🚀 [API REQUEST] GET /api/payment/$paymentUid/status");
      final response = await _api.get('/api/payment/$paymentUid/status');

      // Giả sử response trả về dạng: {"payment_uid": "...", "status": "SUCCESS", ...}
      return response['data']['status']?.toString();
    } catch (e) {
      debugPrint("❌ [API ERROR] getPaymentStatus: $e");
      return null;
    }
  }

  String _yyyyMmDd_2(String dateStr) {
    if (dateStr.isEmpty) return '';

    final d = DateTime.tryParse(dateStr);
    if (d == null) return dateStr; // Fallback: trả về chuỗi gốc nếu lỗi parse

    return '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
  }

  Future<bool> setItemQuantity({
    required String dishUid,
    required String deliveryDate,
    required int targetQuantity,
  }) async {
    try {
      final resp = await _api.put(
        '/api/carts/items/$dishUid',
        {
          'delivery_date': _yyyyMmDd_2(deliveryDate),
          'target_quantity': targetQuantity,
        },
      );
      return resp != null;
    } catch (e) {
      debugPrint('❌ [setItemQuantity] $e');
      return false;
    }
  }

  Future<bool> toggleSelect(String cartItemUid) async {
    try {
      await _api.put('/api/carts/cart-items/$cartItemUid/toggle', {});
      return true;
    } catch (e) {
      debugPrint('❌ [toggleSelect] $e');
      return false;
    }
  }

  // --- HELPER: Normalize Response Data ---
  Map<String, dynamic> _normalizeData(dynamic data) {
    if (data is Map<String, dynamic>) {
      // Nếu bọc trong 'data', unwrap nó
      if (data.containsKey('data') && data['data'] is Map) {
        return Map<String, dynamic>.from(data['data']);
      }
      return data;
    }
    return {};
  }

  // ===========================================================================
  // 1. GET CART (Lấy giỏ hàng & Làm phẳng dữ liệu)
  // Endpoint: GET /api/carts/
  // ===========================================================================
  Future<CartSummaryModel> getCart() async {
    try {
      final response = await _api.get(ApiConstants.getCart);
      // Giả sử ApiConstants.getCart = '/api/carts/'

      final data =
          response['data'] ?? response; // Handle trường hợp wrapper khác nhau

      // 1. Lấy danh sách ngày (Root Level)
      final List<dynamic> days = data['items'] ?? [];
      final List<CartItemModel> allItems = [];

      // 2. Duyệt qua từng NGÀY
      for (var dayNode in days) {
        final String dateStr = dayNode['delivery_date'] ?? '';
        final List<dynamic> chefs = dayNode['chefs'] ?? [];

        // 3. Duyệt qua từng ĐẦU BẾP trong ngày đó
        for (var chefNode in chefs) {
          final String chefName = chefNode['chef_name'] ?? '';
          final List<dynamic> items = chefNode['items'] ?? [];

          // 4. Duyệt qua từng MÓN ĂN và map vào Model
          for (var itemNode in items) {
            allItems.add(CartItemModel.fromJson(itemNode,
                dateInfo: dateStr, // Truyền ngày từ cấp cha xuống
                chefInfo: chefName // Truyền tên chef từ cấp cha xuống
                ));
          }
        }
      }

      // 5. Tính tổng tiền (Hoặc lấy từ API nếu có)
      final double total = (data['total_amount'] as num?)?.toDouble() ?? 0.0;

      return CartSummaryModel(
        items: allItems,
        totalAmount: total,
        message: data['message'] ?? '',
      );
    } catch (e) {
      debugPrint("CartRepo Error: $e");
      // Trả về giỏ rỗng thay vì crash app
      return CartSummaryModel.empty();
    }
  }

  // ===========================================================================
  // 2. ADD TO CART
  // Endpoint: POST /api/carts/add
  // Body: { dish_uid, delivery_date, quantity_to_add }
  // ===========================================================================
  Future<bool> addToCart({
    required String dishUid,
    required String deliveryDate, // Format: YYYY-MM-DD
    required int quantity,
  }) async {
    try {
      await _api.post('/api/carts/add', {
        'dish_uid': dishUid,
        'delivery_date': deliveryDate,
        'quantity_to_add': quantity,
      });
      return true;
    } catch (e) {
      debugPrint("AddToCart Error: $e");
      return false;
    }
  }

  // ===========================================================================
  // 3. UPDATE QUANTITY
  // Endpoint: PUT /api/carts/items/{dishUid}
  // Body: { delivery_date, target_quantity }
  // ===========================================================================
  Future<bool> updateQuantity({
    required String dishUid,
    required String deliveryDate,
    required int newQuantity,
  }) async {
    try {
      await _api.put('/api/carts/items/$dishUid', {
        'delivery_date': deliveryDate,
        'target_quantity': newQuantity,
      });
      return true;
    } catch (e) {
      return false;
    }
  }

  // ===========================================================================
  // 4. REMOVE ITEM
  // Endpoint: DELETE /api/carts/items/{dishUid}
  // Body: { delivery_date } (Swagger trang 65 yêu cầu body này)
  // ===========================================================================
  Future<bool> removeItem({
    required String dishUid,
    required String deliveryDate,
  }) async {
    try {
      // Lưu ý: Dio delete có hỗ trợ body qua tham số `data`
      await _api.delete(
        '/api/carts/items/$dishUid',
        body: {'delivery_date': deliveryDate},
      );
      return true;
    } catch (e) {
      return false;
    }
  }

  // ===========================================================================
  // 5. TOGGLE SELECTION (Checkbox)
  // Endpoint: PUT /api/carts/cart-items/{cartItemUid}/toggle
  // Note: API này dùng `cartItemUid` (ID dòng), KHÔNG dùng `dishUid`
  // ===========================================================================
  Future<bool> toggleSelection(String cartItemUid) async {
    try {
      await _api.put('/api/carts/cart-items/$cartItemUid/toggle', {});
      return true;
    } catch (e) {
      return false;
    }
  }

  // ---------------- CHECKOUT ----------------
  // Future<OrderDraft?> checkoutOrder({
  //   required String fullName,
  //   required String phoneNumber,
  //   required String deliveryDate,
  //   required String deliveryTime,
  //   required int deliveryAddressId,
  //   required String paymentMethod,
  //   required List<CartItemModel> selectedItems,
  // }) async {
  //   try {
  //     final payload = {
  //       'full_name': fullName,
  //       'phone_number': phoneNumber,
  //       'delivery_date': deliveryDate,
  //       'delivery_time': deliveryTime,
  //       'delivery_address_id': deliveryAddressId,
  //       'payment_method': paymentMethod,
  //     };

  //     final response = await _api.post('/api/checkouts/', payload);
  //     final body = _normalizeData(response);

  //     return OrderDraft.fromJson(body);
  //   } catch (e) {
  //     debugPrint('Checkout error: $e');
  //     return null;
  //   }
  // }

  Future<OrderDraft?> checkoutOrder({
    required String fullName,
    required String phoneNumber,
    required String deliveryDate,
    required String deliveryTime,
    required int deliveryAddressId,
    required String paymentMethod,
    required List<CartItemModel>
        selectedItems, // <--- Đã truyền vào thì phải dùng
    List<Map<String, dynamic>>? subOrderSchedules,
  }) async {
    try {
      // 1. Map danh sách Item sang format JSON mà Backend cần
      // Thường backend chỉ cần dish_uid và quantity
      final List<Map<String, dynamic>> itemsPayload = selectedItems.map((item) {
        return {
          'dish_uid': item.dishUid,
          'quantity': item.quantity,
          // 'note': item.note, // Nếu có note thì thêm vào
        };
      }).toList();

      final payload = {
        'full_name': fullName,
        'phone_number': phoneNumber,
        'delivery_date': deliveryDate,
        'delivery_time': deliveryTime,
        'delivery_address_id': deliveryAddressId,
        'payment_method': paymentMethod,
        'items': itemsPayload, // <--- Bổ sung field quan trọng này
      };
      if (subOrderSchedules != null && subOrderSchedules.isNotEmpty) {
        payload['sub_order_schedules'] = subOrderSchedules;
      }

      // Debug log để kiểm tra data trước khi gửi
      debugPrint('🚀 [Checkout Payload]: $payload');

      final response = await _api.post('/api/checkouts/', payload);

      // Chuẩn hóa data trước khi parse
      final body = _normalizeData(response);

      return OrderDraft.fromJson(body);
    } catch (e) {
      debugPrint('❌ [Checkout Error]: $e');
      return null;
    }
  }
}
