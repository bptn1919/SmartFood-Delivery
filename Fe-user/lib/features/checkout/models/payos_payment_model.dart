class PayOSPaymentModel {
  final String checkoutID;
  final bool success;
  final String? checkoutUrl;
  final String? qrCode;
  final int? orderCode; // Ép kiểu int để khớp Backend
  final int? amount;
  final String? accountNumber;
  final String? accountName;
  final String? bin;
  final String? status;
  final String? description;
  final String? error;

  PayOSPaymentModel({
    required this.checkoutID,
    required this.success,
    this.checkoutUrl,
    this.qrCode,
    this.orderCode,
    this.amount,
    this.accountNumber,
    this.accountName,
    this.bin,
    this.status,
    this.description,
    this.error,
  });

  factory PayOSPaymentModel.fromJson(Map<String, dynamic> json) {
    // 1. Phá lớp bọc thứ nhất (API Wrapper)
    final Map<String, dynamic> outerData = 
        (json['data'] != null && json['data'] is Map) ? json['data'] : json;

    // 2. Phá lớp bọc thứ hai (Python Controller)
    final Map<String, dynamic> innerData = 
        (outerData['data'] != null && outerData['data'] is Map) ? outerData['data'] : outerData;

    return PayOSPaymentModel(
      // Lấy success từ lớp thứ nhất
      success: outerData['success'] ?? false,
      error: outerData['message'] ?? json['message'],

      // Lấy dữ liệu thực từ lớp thứ hai (innerData)
      checkoutUrl: innerData['payment_url'] ?? innerData['checkout_url'], 
      qrCode: innerData['qr_code'],
      checkoutID: innerData['checkout_id'] ?? '',
      
      // Xử lý an toàn orderCode
      orderCode: innerData['order_code'] != null 
          ? int.tryParse(innerData['order_code'].toString()) 
          : null,
          
      // ⚠️ CẠM BẪY ĐÃ ĐƯỢC FIX: Dùng (num?).toInt() để ép kiểu 31320.00 thành 31320
      amount: innerData['amount'] != null 
          ? (num.tryParse(innerData['amount'].toString())?.toInt()) 
          : null,
          
      accountNumber: innerData['account_number'],
      accountName: innerData['account_name'],
      bin: innerData['bin'],
      status: innerData['status'],
      description: innerData['description'] ?? innerData['transaction_id'], 
    );
  }

  // Thêm hàm này để chuyển Object thành Map
  Map<String, dynamic> toJson() {
    return {
      'success': success,
      'checkoutUrl': checkoutUrl,
      'qrCode': qrCode,
      'orderCode': orderCode,
      'amount': amount,
      'accountNumber': accountNumber,
      'accountName': accountName,
      'bin': bin,
      'status': status,
      'description': description,
      'error': error,
      'checkoutID': checkoutID,
    };
  }
}