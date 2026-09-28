import 'package:flutter/material.dart';
import '../repositories/auth_repository.dart';
import '../../common/app_components.dart';

class VerifyOtpPage extends StatefulWidget {
  final String email; 

  const VerifyOtpPage({
    super.key,
    required this.email
  });

  @override
  State<VerifyOtpPage> createState() => _VerifyOtpPageState();
}

class _VerifyOtpPageState extends State<VerifyOtpPage> {
  final AuthRepository _authRepo = AuthRepository();
  String _otpCode = "";
  bool _loading = false;

  Future<void> _handleVerify() async {
    // Validate cơ bản
    if (_otpCode.length < 4) {
      showAppDialog(context, "Error", "Please enter complete 4-digit OTP", isError: true);
      return;
    }

    setState(() => _loading = true);

    try {
      // Gọi hàm verifyOtp với isSignup = false (vì là forgot password)
      await _authRepo.verifyOtp(
        email: widget.email,
        otp: _otpCode,
        isSignup: false  // Đổi thành false vì đây là forgot password
      );

      if (!mounted) return;

      // Chuyển sang trang reset password
      Navigator.pushReplacementNamed(
        context, 
        "/reset",
        arguments: widget.email  // Truyền email sang trang reset
      );

    } catch (e) {
      if (!mounted) return;
      String msg = e.toString().replaceAll("Exception:", "").trim();
      showAppDialog(context, "Error", msg, isError: true);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AuthTemplate(
      title: "Verification",
      onBack: () => Navigator.pop(context),
      child: Column(
        children: [
          const SizedBox(height: 20),
          const Text(
            "An OTP code has been sent to your email",
            style: TextStyle(fontSize: 15, color: Colors.black87, fontWeight: FontWeight.w800),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 30),

          // Component OTP
          AppOtpInput(
            onChanged: (code) => _otpCode = code,
          ),
          
          const SizedBox(height: 40),

          AppButton(
            text: "Verify",
            isLoading: _loading,
            onPressed: _handleVerify,
            width: 200,
          ),

          const SizedBox(height: 20),
          RichText(
            text: const TextSpan(
              style: TextStyle(color: Colors.black54),
              children: [
                TextSpan(
                  text: "Didn’t receive the code? ",
                ),
                TextSpan(
                  text: "Resend",
                  style: TextStyle(color: Color(0xFFE95322)),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}