import 'package:flutter/material.dart';
import '../repositories/auth_repository.dart';
import '../../common/app_components.dart'; // Đảm bảo import đúng các component UI của bạn

class VerifyOtpForSuPage extends StatefulWidget {
  final String email; // 1. Thêm biến email để nhận từ màn hình trước

  const VerifyOtpForSuPage({
    super.key, 
    required this.email
  });

  @override
  State<VerifyOtpForSuPage> createState() => _VerifyOtpForSuPageState();
}

class _VerifyOtpForSuPageState extends State<VerifyOtpForSuPage> {
  final AuthRepository _authRepo = AuthRepository();
  String _otpCode = ""; // Biến lưu OTP nhập vào
  bool _loading = false;

  Future<void> _handleVerify() async {
    // Validate cơ bản
    if (_otpCode.length < 4) {
      _showDialog("Error", "Please enter a valid 6-digit OTP.");
      return;
    }

    setState(() => _loading = true);

    try {
      // 2. Gọi hàm verifyOtp với tham số chuẩn
      await _authRepo.verifyOtp(
        email: widget.email,      // Lấy email từ widget
        otp: _otpCode,            // Dùng biến _otpCode thay vì controller
        isSignup: true            // Trang này dành cho Sign Up nên luôn là true
      );

      if (!mounted) return;

      // Flow Sign Up: Thành công -> Thông báo & Về Login
      _showDialog("Success", "Account verified successfully!", onOk: () {
        Navigator.pushNamedAndRemoveUntil(context, '/login', (r) => false);
      });

    } catch (e) {
      if (!mounted) return;
      // Lấy message lỗi gọn gàng
      String msg = e.toString().replaceAll("Exception:", "").trim();
      _showDialog("Verification Failed", msg);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  // 3. Bổ sung hàm hiển thị Dialog thiếu
  void _showDialog(String title, String message, {VoidCallback? onOk}) {
    showDialog(
      context: context,
      builder: (_) => AlertDialog(
        title: Text(title),
        content: Text(message),
        actions: [
          TextButton(
            onPressed: () {
              Navigator.pop(context); // Đóng dialog
              if (onOk != null) onOk();
            },
            child: const Text("OK"),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return AuthTemplate(
      title: "Verification",
      onBack: () => Navigator.pop(context),
      child: Column(
        children: [
          const SizedBox(height: 20),
          Text(
            "An OTP code has been sent to\n${widget.email}", // Hiển thị email cho user biết
            style: const TextStyle(fontSize: 15, color: Colors.black87),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 30),

          // Component nhập OTP
          AppOtpInput(
            onChanged: (code) {
              _otpCode = code; // Cập nhật biến _otpCode khi nhập
            },
          ),
          
          const SizedBox(height: 40),

          // Nút Verify
          AppButton(
            text: "Verify",
            isLoading: _loading,
            onPressed: _handleVerify,
          ),

          const SizedBox(height: 20),
          
          // Resend Link
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Text("Didn’t receive the code? ", style: TextStyle(color: Colors.black54)),
              GestureDetector(
                onTap: () {
                  showAppSnackBar(
                    context,
                    "Resend feature coming soon!",
                    type: SnackBarType.warning,
                  );
                },
                child: const Text(
                  "Resend",
                  style: TextStyle(color: Color(0xFFE84D67), fontWeight: FontWeight.bold), // AppColors.primary
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}