import 'package:flutter/material.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:testing/features/common/app_components.dart';
import 'package:testing/features/home/repositories/cart_repository.dart';
import '../models/payos_payment_model.dart';

class PayOSQrDialog extends StatelessWidget {
  final PayOSPaymentModel paymentData;
  final VoidCallback onCheckStatus;

  const PayOSQrDialog({
    super.key, 
    required this.paymentData, 
    required this.onCheckStatus
  });

  @override
  Widget build(BuildContext context) {
    final double screenWidth = MediaQuery.of(context).size.width;
    
    // 💡 TECH LEAD FIX: Ưu tiên dùng qrCode, nếu null thì dùng checkoutUrl để vẽ
    final String qrData = paymentData.qrCode ?? paymentData.checkoutUrl ?? "";

    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      contentPadding: const EdgeInsets.all(24),
      content: SizedBox(
        width: screenWidth * 0.9,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text("Quét mã để thanh toán", 
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 16),
            
            // 💡 TECH LEAD FIX: Vẽ QR bằng qr_flutter
            if (qrData.isNotEmpty)
              Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: Colors.white,
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: Colors.grey.shade300),
                ),
                child: QrImageView(
                  data: qrData, // Truyền chuỗi data (URL hoặc chuỗi mã hóa)
                  version: QrVersions.auto,
                  size: 200.0,
                  backgroundColor: Colors.white,
                ),
              )
            else
              const Padding(
                padding: const EdgeInsets.all(20.0),
                child: Text("Không thể tạo mã QR", style: TextStyle(color: Colors.red)),
              ),
            
            const SizedBox(height: 16),
            
            Text(
              "Số tiền: ${paymentData.amount != null ? paymentData.amount.toString() : '0'} VNĐ", 
              style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold, color: Colors.green),
            ),
        
            const SizedBox(height: 16),
            
            // Thông tin chuyển khoản tay (đề phòng QR lỗi)
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(color: Colors.grey.shade100, borderRadius: BorderRadius.circular(8)),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _buildInfoRow("Ngân hàng:", paymentData.bin ?? "Ngân hàng TMCP Phương Đông"),
                  _buildInfoRow("Chủ TK:", paymentData.accountName ?? "AmoMeal"),
                  _buildInfoRow("Số TK:", paymentData.accountNumber ?? "1022536755"),
                  const Divider(),
                  _buildInfoRow("Nội dung:", paymentData.orderCode?.toString() ?? "CAS004100046216007", isHighlight: true),
                ],
              ),
            ),
            
            const SizedBox(height: 20),
            
            // Nút Kiểm tra thanh toán
            SizedBox(
              width: double.infinity,
              child: ElevatedButton(
                onPressed: () async {
                  // 💡 TECH LEAD FIX: Gắn API Check Status thực tế
                  final status = await CartRepository().getPaymentStatus(paymentData.checkoutID);

                  if (status == "HOLDING") {
                    onCheckStatus(); // Gọi callback để đóng dialog và báo thành công
                  } else if (status == "PENDING") {
                    showAppSnackBar(context, "Processing payment, please wait...", type: SnackBarType.warning);
                  } else {
                    showAppSnackBar(context, "Payment failed or could not be verified.", type: SnackBarType.error);
                  }
                },
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.orange, 
                  padding: const EdgeInsets.symmetric(vertical: 12)
                ),
                child: const Text("Payment completed", style: TextStyle(color: Colors.white, fontSize: 16)),
              ),
            )
          ],
        ),
      ),
    );
  }
  Widget _buildInfoRow(String label, String value, {bool isHighlight = false}) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(width: 80, child: Text(label, style: const TextStyle(color: Colors.grey, fontSize: 13))),
          Expanded(
            child: Text(
              value, 
              style: TextStyle(
                fontWeight: isHighlight ? FontWeight.bold : FontWeight.normal,
                color: isHighlight ? Colors.red : Colors.black87,
                fontSize: isHighlight ? 15 : 13,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
