import 'package:flutter/foundation.dart';
import '../../../core/network/api_client.dart'; 
import '../models/voucher_model.dart';

class VoucherRepository {
  final ApiClient _api;

  VoucherRepository({ApiClient? api}) : _api = api ?? ApiClient();

  Future<bool> createVoucher({
    required String code,
    required String name,
    required String description,
    required String discountType, // "PERCENTAGE" hoặc "FIXED_AMOUNT"
    required double discountValue,
    required double minOrderAmount,
    required DateTime startDate,
    required DateTime endDate,
  }) async {
    try {
      final Map<String, dynamic> body = {
        "code": code,
        "name": name,
        "description": description,
        "discount_type": discountType, 
        "discount_value": discountValue,
        "min_order_amount": minOrderAmount,
        "start_date": startDate.toUtc().toIso8601String(), 
        "end_date": endDate.toUtc().toIso8601String(),
        "is_active": true, 
      };

      await _api.post('/api/vouchers', body);
      return true; 
    } catch (e) {
      debugPrint("❌ Create Voucher Error: $e");
      return false; 
    }
  }

  Future<List<VoucherModel>> getAllVouchers() async {
    try {
      final response = await _api.get('/api/vouchers');
      
      List<dynamic> listData = [];
      
      // Xử lý linh hoạt lỡ backend có bọc thêm class "data" hoặc trả về List luôn
      if (response is List) {
        listData = response;
      } else if (response is Map<String, dynamic> && response['data'] != null) {
        listData = response['data'];
      }

      return listData.map((e) => VoucherModel.fromJson(e)).toList();
    } catch (e) {
      debugPrint("❌ Get Vouchers Error: $e");
      return [];
    }
  }

  Future<VoucherModel?> getVoucherDetail(String uid) async {
    try {
      final response = await _api.get('/api/vouchers/$uid');
      
      // Xử lý json trả về (bọc trong data hoặc trả trực tiếp)
      final data = response is Map<String, dynamic> && response.containsKey('data') 
          ? response['data'] 
          : response;

      return VoucherModel.fromJson(data);
    } catch (e) {
      debugPrint("❌ Get Voucher Detail Error: $e");
      return null;
    }
  }

  Future<bool> updateVoucher(
    String uid, {
    required String name,
    required String description,
    required double discountValue,
    required double minOrderAmount,
    required DateTime startDate,
    required DateTime endDate,
    bool? isActive, // Thêm tùy chọn active/deactive nếu UI có xài
  }) async {
    try {
      final Map<String, dynamic> body = {
        "name": name,
        "description": description,
        "discount_value": discountValue,
        "min_order_amount": minOrderAmount,
        "start_date": startDate.toUtc().toIso8601String(),
        "end_date": endDate.toUtc().toIso8601String(),
      };

      // Nếu có truyền trạng thái active thì mới gán vào body
      if (isActive != null) {
        body["is_active"] = isActive;
      }

      // 👇 Gọi hàm patch của bạn với named parameter `data:`
      await _api.patch(
        '/api/vouchers/$uid', 
        data: body, 
      );
      
      return true;
    } catch (e) {
      debugPrint("❌ Update Voucher Error: $e");
      return false;
    }
  }

  Future<bool> deleteVoucher(String uid) async {
    try {
      // Chỉ cần truyền endpoint, các tham số body/headers optional sẽ tự null
      await _api.delete('/api/vouchers/$uid');
      return true;
    } catch (e) {
      debugPrint("❌ Delete Voucher Error: $e");
      return false;
    }
  }

  
}