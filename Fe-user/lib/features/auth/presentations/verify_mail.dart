import 'package:flutter/material.dart';
// Import Repo từ module mới
import '../repositories/auth_repository.dart';
// Import bộ Component UI chung
import '../../common/app_components.dart';

class VerifyMailPage extends StatefulWidget {
  const VerifyMailPage({super.key});

  @override
  State<VerifyMailPage> createState() => _VerifyMailPageState();
}

class _VerifyMailPageState extends State<VerifyMailPage> {
  final _emailController = TextEditingController();
  bool _loading = false;
  
  final AuthRepository _authRepo = AuthRepository();

  @override
  void dispose() {
    _emailController.dispose();
    super.dispose();
  }

  Future<void> _handleForgetPassword() async {
    final email = _emailController.text.trim();

    if (email.isEmpty) {
      showAppDialog(context, "Error", "Please enter your email", isError: true);
      return;
    }

    setState(() => _loading = true);

    try {
      // Gọi Repo:
      // 1. Repo tự gọi API
      // 2. Repo tự lấy reset_session_token và lưu vào SharedPreferences
      // 3. Nếu lỗi, Repo tự throw Exception
      await _authRepo.forgetPassword(email);

      if (!mounted) return;

      // Thành công
      showAppDialog(context, "Success", "OTP has been sent to your email", onOk: () {
        Navigator.pushNamed(context, "/verify-otp", arguments: _emailController.text.trim());
      });
      
    } catch (e) {
      if (!mounted) return;
      showAppDialog(context, "Error", e.toString(), isError: true);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AuthTemplate(
      title: "Verification",
      onBack: () => Navigator.pop(context), // Quay lại trang Login
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SizedBox(height: 20),
          
          // Input Email tái sử dụng
          AppInput(
            label: "Email",
            hint: "johndoe@example.com",
            controller: _emailController,
            keyboardType: TextInputType.emailAddress,
          ),

          const SizedBox(height: 30),

          // Nút gửi OTP tái sử dụng
          AppButton(
            text: "Send OTP",
            isLoading: _loading,
            onPressed: _handleForgetPassword,
            width: 200,
          ),
        ],
      ),
    );
  }
}