import 'package:flutter/foundation.dart';
import '../models/payos_payment_model.dart';
import '../../../core/network/api_client.dart';

class PaymentRepository {
  final ApiClient _api;

  PaymentRepository({ApiClient? api}) : _api = api ?? ApiClient();

  Future<PayOSPaymentModel?> createPayOSPayment({
    required String checkoutUid,
    required double amount,
    required String fullName,
    required String phone,
  }) async {
    try {
      final Map<String, dynamic> body = {
        "checkout_uid": checkoutUid,
        "payment_method": "PAYOS",
        "buyer_name": fullName,
        "buyer_phone": phone,
        // Ép số tiền về int theo yêu cầu của PayOS
        "amount": amount.toInt(), 
      };

      final response = await _api.post('/api/payment/create', body);

      debugPrint("🚀 RAW RESPONSE TỪ API: $response");

      if (response != null) {
        // Nếu API Wrapper của bạn tự bọc response trong 'data', hãy trích xuất nó ra.
        // Ở đây tôi parse thẳng vì Python function return thẳng object
        return PayOSPaymentModel.fromJson(response);
      }
      return null;
    } catch (e) {
      debugPrint("❌ Create Payment Error: $e");
      return PayOSPaymentModel(checkoutID: '', success: false, error: e.toString());
    }
  }
}