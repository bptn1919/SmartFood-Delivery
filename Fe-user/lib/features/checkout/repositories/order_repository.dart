import 'package:flutter/foundation.dart';
import '../../../core/network/api_client.dart';
import '../models/customer_order.dart';
import '../models/order_delivery_time_update_result.dart';
import '../models/order_detail.dart';
import '../models/voucher_validation_result.dart';

import '../../chef_manager/models/voucher_model.dart';

class OrderRepository {
  final ApiClient _apiClient;

  OrderRepository({ApiClient? api}) : _apiClient = api ?? ApiClient();

  /// Helper chuẩn hóa dữ liệu:
  /// Nếu API trả về { "data": [...] } -> trả về [...]
  /// Nếu API trả về [...] -> trả về nguyên bản
  dynamic _normalizeData(dynamic data) {
    if (data is Map<String, dynamic>) {
      if (data.containsKey('data')) {
        return data['data'];
      }
      return data;
    }
    return data;
  }

  Future<List<CustomerOrder>> getMyOrders({
    String? search,
    String? orderBy,
    String? sortType,
  }) async {
    try {
      // 1. Prepare Params
      final Map<String, dynamic> params = {};
      if (search != null && search.isNotEmpty) {
        params['search'] = search;
      }
      if (orderBy != null && orderBy.isNotEmpty) {
        params['order_by'] = orderBy;
      }
      if (sortType != null && sortType.isNotEmpty) {
        params['sort_type'] = sortType;
      }

      // 2. Call API
      final response = await _apiClient.get(
        '/api/orders/customer', // API trả về List Group
        queryParameters: params,
      );

      // Nếu response.data là Map (JSON thuần):
      //debugPrint('📡 [getMyOrders] Raw response: ${response.data.orders.length}');// Log thô để kiểm tra

      final rawData = _normalizeData(response);
      final List<dynamic> listRaw = (rawData is List) ? rawData : [];

      // 👇 3. LOGIC MỚI: FLATTEN DATA (Duỗi phẳng danh sách)
      List<CustomerOrder> allOrders = [];

      for (var item in listRaw) {
        // Kiểm tra xem item này có phải là Group (chứa key 'orders') không?
        if (item is Map<String, dynamic> && item.containsKey('orders')) {
          // CASE: Backend trả về Group
          final ordersInGroup = item['orders'];
          if (ordersInGroup is List) {
            final parsedOrders = ordersInGroup
                .map(
                    (o) => CustomerOrder.fromJson(Map<String, dynamic>.from(o)))
                .toList();

            allOrders.addAll(parsedOrders); // Gộp vào danh sách tổng
          }
        } else {
          // CASE: Fallback (Nếu backend trả về Order thường - phòng hờ)
          try {
            allOrders
                .add(CustomerOrder.fromJson(Map<String, dynamic>.from(item)));
          } catch (_) {}
        }
      }

      debugPrint("✅ Repository flattened: ${allOrders.length} orders found.");
      return allOrders;
    } catch (e) {
      debugPrint('❌ [getMyOrders] Error: $e');
      return [];
    }
  }

  /// GET /api/orders/{uid} -> OrderDetail
  Future<OrderDetail?> getOrderDetail(String uid) async {
    try {
      final response = await _apiClient.get('/api/orders/$uid');

      debugPrint(
          '📡 [getOrderDetail] Raw response: $response'); // Log thô để kiểm tra

      // 1. Normalize
      final rawData = _normalizeData(response);

      // 2. Validate & Parse
      if (rawData is Map<String, dynamic> && rawData.isNotEmpty) {
        return OrderDetail.fromJson(rawData);
      }

      return null;
    } catch (e) {
      debugPrint('❌ [getOrderDetail] Error: $e');
      return null;
    }
  }

  Future<OrderDeliveryTimeUpdateResult> updateCustomerOrderDeliveryTime({
    required String orderUid,
    required String deliveryDate,
    required String deliveryTime,
  }) async {
    try {
      final response = await _apiClient.patch(
        '/api/orders/customer/$orderUid/delivery-time',
        data: {
          'delivery_date': deliveryDate,
          'delivery_time': deliveryTime,
        },
      );

      final rawData = _normalizeData(response);
      final body =
          rawData is Map<String, dynamic> ? rawData : <String, dynamic>{};

      return OrderDeliveryTimeUpdateResult.fromJson(
        body,
        fallbackDeliveryDate: deliveryDate,
        fallbackDeliveryTime: deliveryTime,
      );
    } catch (e) {
      debugPrint('❌ [updateCustomerOrderDeliveryTime] Error: $e');
      rethrow;
    }
  }

  Future<VoucherValidationResult?> validateVoucher({
    required String code,
    required int chefId,
    required double orderAmount,
  }) async {
    try {
      final Map<String, dynamic> body = {
        "code": code,
        "chef_id": chefId,
        "order_amount": orderAmount,
      };

      // Gọi API POST
      final response = await _apiClient.post('/api/vouchers/validate', body);

      if (response is Map<String, dynamic>) {
        final data = response.containsKey('data') ? response['data'] : response;
        return VoucherValidationResult.fromJson(data);
      }
      return null;
    } catch (e) {
      debugPrint("❌ Validate Voucher Error: $e");
      return null; // Lỗi mạng hoặc lỗi server 500
    }
  }

  Future<List<VoucherModel>> getChefVouchers(int chefId,
      {bool availableOnly = true}) async {
    try {
      // Gọi API GET theo Spec
      final response = await _apiClient.get(
        '/api/vouchers/chef/$chefId',
        queryParameters: {'available_only': availableOnly},
      );

      List<dynamic> listData = [];
      if (response is List) {
        listData = response;
      } else if (response is Map<String, dynamic> && response['data'] != null) {
        listData = response['data'];
      }

      return listData.map((e) => VoucherModel.fromJson(e)).toList();
    } catch (e) {
      debugPrint("❌ Get Chef Vouchers Error: $e");
      return [];
    }
  }

  Future<Map<String, dynamic>?> applyVoucherToOrder(
      String orderUid, String voucherCode) async {
    try {
      final response = await _apiClient.post(
        '/api/orders/$orderUid/apply-voucher',
        {'voucher_code': voucherCode},
      );

      final rawData = _normalizeData(response);
      return rawData is Map<String, dynamic> ? rawData : null;
    } catch (e) {
      debugPrint("❌ Apply Voucher to Order Error: $e");
      return null;
    }
  }

  // 1. Áp dụng SHOP_VOUCHER cho đơn hàng con
  Future<Map<String, dynamic>?> applyShopVoucher(
      String orderUid, String voucherCode) {
    return applyVoucherToOrder(orderUid, voucherCode);
  }

  // 2. Áp dụng PLATFORM_VOUCHER cho Checkout tổng
  Future<Map<String, dynamic>?> applyPlatformVoucher(
      String checkoutUid, String voucherCode, String voucherType) async {
    if (voucherType != 'PLATFORM_SUBTOTAL' &&
        voucherType != 'PLATFORM_SHIPPING') {
      throw ArgumentError.value(
        voucherType,
        'voucherType',
        'Must be PLATFORM_SUBTOTAL or PLATFORM_SHIPPING',
      );
    }

    try {
      final response = await _apiClient.post(
        '/api/checkouts/$checkoutUid/apply-platform-voucher',
        {
          'voucher_code': voucherCode,
          'voucher_type': voucherType,
        },
      );

      final rawData = _normalizeData(response);
      return rawData is Map<String, dynamic> ? rawData : null;
    } catch (e) {
      debugPrint("❌ Lỗi apply Platform Voucher: $e");
      return null;
    }
  }

  Future<bool> cancelOrder(
      {required String orderUid, required String reason}) async {
    try {
      String url = '/api/orders/$orderUid/cancel';

      debugPrint("🚀 Calling API Cancel Order: $url");

      final response = await _apiClient.post(url, {'reason': reason});

      if (response['data'] != null) {
        return true;
      }
      return false;
    } catch (e) {
      debugPrint("❌ Lỗi khi hủy đơn hàng: $e");
      return false;
    }
  }
}
