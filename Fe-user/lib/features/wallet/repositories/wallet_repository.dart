import 'package:flutter/material.dart';
import 'package:testing/core/network/api_client.dart';
import 'package:testing/features/wallet/models/chef_balance_model.dart';
import 'package:testing/features/wallet/models/chef_payment_model.dart';
import 'package:testing/features/wallet/models/customer_bank.dart';
import 'package:testing/features/wallet/models/settle_cod_response.dart';
import 'package:testing/features/wallet/models/upsert_bank_response_model.dart';
import 'package:testing/features/wallet/models/wallet_model.dart';
import 'package:testing/features/wallet/models/withdraw_request_response.dart';
import 'package:testing/features/wallet/models/withdraw_response.dart';

class WalletRepository {
  final ApiClient _api;

  WalletRepository({ApiClient? api}) : _api = api ?? ApiClient();
  
  Future<WalletModel?> getMyWallet() async {
    try {
      final response = await _api.get('/api/payment/wallet/me');
      
      debugPrint("GetMyWallet Response: $response");
      if (response != null) {
        return WalletModel.fromJson(response['data']);
      }
      return null;
    } catch (e) {
      // Bắn lỗi ra ngoài để UI tự hứng và hiển thị SnackBar
      throw Exception('Failed to fetch wallet data: $e'); 
    }
  }

  Future<CustomerBank?> getCustomerBanks() async {
    try {
      final response = await _api.get('/api/payment/customer/bank-info');
      
      debugPrint("GetCustomerBanks Response: $response");
      if (response != null) {
        return CustomerBank.fromJson(response['data']);
      }
      return null;
    } catch (e) {
      // Bắn lỗi ra ngoài để UI tự hứng và hiển thị SnackBar
      throw Exception('Failed to fetch customer bank data: $e'); 
    }
  }


  Future<UpsertBankResponseModel> upsertCustomerBankInfo(CustomerBank bankInfo) async {
    try {
      // 1. Chuyển đổi model thành Map JSON theo đúng cấu trúc Request Sample trên tài liệu
      final Map<String, dynamic> requestBody = bankInfo.toUpsertJson();
      
      debugPrint("🚀 [API REQUEST] POST /api/payment/customer/bank-info");
      debugPrint("📦 Payload: $requestBody");

      // 2. Gọi API POST lên Backend
      final response = await _api.post(
        '/api/payment/customer/bank-info', // Endpoint từ tài liệu
        requestBody,
      );

      debugPrint("📥 [API RESPONSE] Success: $response");

      // 3. Đọc dữ liệu data trả về (Tùy thuộc vào cấu trúc bọc Base Response của bạn, 
      // nếu response['data'] chứa kết quả thì map response['data'], còn không thì map thẳng response)
      Map<String, dynamic> responseData;
      if (response['data'] != null && response['data'] is Map) {
        responseData = response['data'];
      } else {
        responseData = response;
      }

      // 4. Parse thành Model Response chứa session token để luồng sau dùng verify OTP
      return UpsertBankResponseModel.fromJson(responseData);

    } catch (e) {
      debugPrint("❌ [API ERROR] Upsert Customer Bank Info Fail: $e");
      rethrow;
    }
  }


  Future<CustomerBank> verifyBankInfoOtp({
    required String resetSessionToken,
    required String otp,
  }) async {
    try {
      // 1. Chuẩn bị request body đúng như tài liệu Swagger của bạn
      final Map<String, dynamic> requestBody = {
        'reset_session_token': resetSessionToken,
        'otp': otp,
      };

      debugPrint("🚀 [API REQUEST] POST /api/payment/customer/bank-info/verify-otp");
      debugPrint("📦 Payload: $requestBody");

      // 2. Gọi API POST lên Backend
      final response = await _api.post(
        '/api/payment/customer/bank-info/verify-otp',
        requestBody,
      );

      debugPrint("📥 [API RESPONSE] Success: $response");

      // 3. Xử lý bọc dữ liệu (Tùy theo cấu trúc BaseResponse dự án Amomeal của bạn)
      Map<String, dynamic> responseData;
      if (response['data'] != null && response['data'] is Map) {
        responseData = response['data'];
      } else {
        responseData = response;
      }

      // 4. Parse thẳng dữ liệu trả về thành Model CustomerBank 
      // Vì lúc này Backend đã trả về đầy đủ các trường hệ thống hệ (is_verify, created_at,...)
      return CustomerBank.fromJson(responseData);

    } catch (e) {
      debugPrint("❌ [API ERROR] Verify Bank Info Otp Fail: $e");
      rethrow;
    }
  }



  Future<WithdrawRequestResponse> requestWithdrawal(double amount) async {
    try {
      debugPrint("🚀 [API REQUEST] POST /api/payment/wallet/me/withdraw");
      final response = await _api.post(
        '/api/payment/wallet/me/withdraw',
        {'amount': amount},
      );
      
      // Xử lý bóc tách dựa theo BaseResponse dự án
      final data = (response['data'] != null) ? response['data'] : response;
      return WithdrawRequestResponse.fromJson(data);
    } catch (e) {
      debugPrint("❌ [API ERROR] requestWithdrawal: $e");
      throw Exception('Failed to request withdrawal: $e');
    }
  }

  // 🔗 API BƯỚC 2: Xác nhận rút tiền với OTP
  Future<WithdrawResponse> confirmWithdrawal(String token, String otp) async {
    try {
      debugPrint("🚀 [API REQUEST] POST /api/payment/wallet/me/withdraw/confirm");
      final response = await _api.post(
        '/api/payment/wallet/me/withdraw/confirm',
        {
          'reset_session_token': token,
          'otp': otp,
        },
      );

      final data = (response['data'] != null) ? response['data'] : response;
      return WithdrawResponse.fromJson(data);
    } catch (e) {
      debugPrint("❌ [API ERROR] confirmWithdrawal: $e");
      throw Exception('Failed to confirm withdrawal: $e');
    }
  }


  Future<ChefBalanceModel?> getChefBalance(int chefId) async {
    try {
      final response = await _api.get('/api/payment/chef/$chefId/balance');
      
      if (response != null) {
        return ChefBalanceModel.fromJson(response['data']);
      }
      return null;
    } catch (e) {
      throw Exception('Failed to fetch chef balance: $e');
    }
  }


  Future<SettleCodResponse> settleCod(int chefId) async {
    try {
      // Vì là POST request nhưng không có body, ta để body trống hoặc không truyền
      final response = await _api.post('/api/payment/chef/$chefId/cod/settle', {});
      
      if (response != null) {
        return SettleCodResponse.fromJson(response['data']);
      }
      throw Exception('Received empty response from server.');
    } catch (e) {
      throw Exception('COD Settlement failed: $e');
    }
  }

  Future<ChefPaymentModel?> getChefPaymentInfo() async {
    try {
      debugPrint("🚀 [API REQUEST] GET /api/chef-payment/");
      final response = await _api.get('/api/chef-payment/');
      
      // Xử lý bọc dữ liệu (Tùy theo cấu trúc BaseResponse của bạn)
      Map<String, dynamic>? responseData;
      if (response != null && response['data'] != null) {
        responseData = response['data'];
      } else {
        responseData = response;
      }

      if (responseData == null || responseData.isEmpty) {
        return null; // Trả về null nếu Chef chưa có tài khoản ngân hàng
      }

      return ChefPaymentModel.fromJson(responseData);
    } catch (e) {
      debugPrint("❌ [API ERROR] getChefPaymentInfo: $e");
      return null;
    }
  }

  // 🔗 REAL API: Tạo hoặc Cập nhật thông tin tài khoản ngân hàng (Upsert)
  Future<bool> upsertChefPaymentInfo(Map<String, dynamic> payload) async {
    try {
      debugPrint("🚀 [API REQUEST] POST /api/chef-payment/");
      await _api.post('/api/chef-payment/', payload);
      return true;
    } catch (e) {
      debugPrint("❌ [API ERROR] upsertChefPaymentInfo: $e");
      return false;
    }
  }
}