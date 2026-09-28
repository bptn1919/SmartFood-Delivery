import 'package:flutter/foundation.dart';
import '../../../core/network/api_client.dart';
import '../models/cart_item_model.dart';
import '../models/cart_summary.dart';
import '../models/order_draft.dart';

class CartRepository {
  final ApiClient _apiClient;

  //CartRepository(this._apiClient);
  CartRepository({ApiClient? api}) : _apiClient = api ?? ApiClient();

  String _yyyyMmDd(DateTime d) => '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
  String _fmt(DateTime d) => _yyyyMmDd(d);

  Future<bool> setItemQuantity({
    required String dishUid,
    required DateTime deliveryDate,
    required int targetQuantity,
  }) async {
    try {
      final resp = await _apiClient.put(
        '/api/carts/items/$dishUid',
        {
          'delivery_date': _fmt(deliveryDate),
          'target_quantity': targetQuantity,
        },
      );
      return resp != null;
    } catch (e) {
      debugPrint('❌ [setItemQuantity] $e');
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

  // ---------------- CART ----------------
  Future<CartSummary> getCart() async {
    try {
      final response = await _apiClient.get('/api/carts/');
      final rawData = _normalizeData(response.data);

      final List<CartItemModel> flatItems = [];
      
      // LOGIC FLATTEN: Xử lý cấu trúc Date -> Chef -> Items tại đây
      if (rawData['items'] is List) {
         final rootItems = rawData['items'] as List;
         
         // Kiểm tra xem backend trả về Flat hay Grouped
         // Hack: check phần tử đầu tiên
         if (rootItems.isNotEmpty && rootItems.first['dish_uid'] != null) {
           // Case 1: Đã Flat
           for (var e in rootItems) {
             flatItems.add(CartItemModel.fromJson(e));
           }
         } else {
           // Case 2: Grouped (Date -> Chef -> Items)
           for (final dayNode in rootItems) {
              final d = Map<String, dynamic>.from(dayNode as Map);
              final dayStr = (d['delivery_date'] ?? '').toString();
              final chefs = (d['chefs'] as List? ?? []);

              for (final chefNode in chefs) {
                final c = Map<String, dynamic>.from(chefNode as Map);
                final chefName = (c['chef_name'] ?? '').toString();
                final items = (c['items'] as List? ?? []);

                for (final it in items) {
                  final m = Map<String, dynamic>.from(it as Map);
                  // Inject thông tin cha vào con
                  m['chef_name'] = chefName;
                  m['delivery_date'] = m['delivery_date'] ?? dayStr;
                  flatItems.add(CartItemModel.fromJson(m));
                }
              }
           }
         }
      }

      // Tính lại total nếu cần hoặc lấy từ BE
      final total = (rawData['total_amount'] is num) 
          ? (rawData['total_amount'] as num).toDouble() 
          : flatItems.fold(0.0, (sum, item) => sum + (item.price * item.quantity));

      return CartSummary(
        items: flatItems,
        totalAmount: total,
        message: rawData['message']?.toString() ?? '',
      );

    } catch (e) {
      debugPrint('Error getting cart: $e');
      return const CartSummary(items: [], totalAmount: 0, message: 'Error loading cart');
    }
  }

  Future<bool> addToCart({
    required String dishUid,
    required DateTime deliveryDate,
    required int quantityToAdd,
  }) async {
    try {
      final dateStr = deliveryDate.toIso8601String().split('T').first;
      await _apiClient.post(
        '/api/carts/add',
        {
          'dish_uid': dishUid,
          'delivery_date': dateStr,
          'quantity_to_add': quantityToAdd,
        },
      );
      return true;
    } catch (e) {
      return false;
    }
  }

  // ---------------- CHECKOUT ----------------
  Future<OrderDraft?> checkoutOrder({
    required String fullName,
    required String phoneNumber,
    required String deliveryDate,
    required String deliveryTime,
    required int deliveryAddressId,
    required String paymentMethod,
    required List<CartItemModel> selectedItems,
  }) async {
    try {
      final payload = {
        'full_name': fullName,
        'phone_number': phoneNumber,
        'delivery_date': deliveryDate,
        'delivery_time': deliveryTime,
        'delivery_address_id': deliveryAddressId,
        'payment_method': paymentMethod,
      };

      final response = await _apiClient.post('/api/checkouts/', payload);
      final body = _normalizeData(response);
      
      return OrderDraft.fromJson(body);
    } catch (e) {
      debugPrint('Checkout error: $e');
      return null;
    }
  }
  
  // Các hàm update (Address, Time...) tương tự: dùng _apiClient và _normalizeData
}