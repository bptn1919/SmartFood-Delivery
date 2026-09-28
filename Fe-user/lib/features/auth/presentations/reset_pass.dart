import 'package:flutter/material.dart';
import '../repositories/auth_repository.dart';
import '../../common/app_components.dart'; // Import bộ component chung

class ResetPassPage extends StatefulWidget {
  const ResetPassPage({super.key});

  @override
  State<ResetPassPage> createState() => _ResetPassPageState();
}

class _ResetPassPageState extends State<ResetPassPage> {
  final _passwordController = TextEditingController();
  final _confirmController = TextEditingController();

  bool _showPassword = false;
  bool _showConfirm = false;
  bool _loading = false;
  
  final AuthRepository _authRepo = AuthRepository();

  @override
  void dispose() {
    _passwordController.dispose();
    _confirmController.dispose();
    super.dispose();
  }

  Future<void> _handleReset() async {
    final newPass = _passwordController.text.trim();
    final confirmPass = _confirmController.text.trim();

    // Validate cơ bản ở UI
    if (newPass.isEmpty || confirmPass.isEmpty) {
      showAppDialog(context, "Error", "Please enter password and confirm it.", isError: true);
      return;
    }
    if (newPass != confirmPass) {
      showAppDialog(context, "Error", "Passwords do not match.", isError: true);
      return;
    }

    setState(() => _loading = true);

    try {
      // Gọi Repo: Không cần truyền token vì Repo tự lấy từ Local Storage
      await _authRepo.resetPassword(newPass, confirmPass);

      if (!mounted) return;
      
      // Thành công -> Hiện thông báo và về trang Login
      showAppDialog(context, "Success", "Password reset successfully! Please login.", onOk: () {
        // Xóa hết stack để về màn hình Login sạch sẽ
        Navigator.pushNamedAndRemoveUntil(context, "/login", (route) => false);
      });
      
    } catch (e) {
      if (!mounted) return;
      showAppDialog(context, "Reset Failed", e.toString(), isError: true);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AuthTemplate(
      title: "Reset\nPassword", // Xuống dòng như thiết kế gốc
      onBack: () => Navigator.pop(context),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SizedBox(height: 10),
          
          // Password Input
          AppInput(
            label: "Password",
            hint: "********",
            controller: _passwordController,
            isPassword: true,
            isVisible: _showPassword,
            onToggleVisibility: () => setState(() => _showPassword = !_showPassword),
          ),

          // Confirm Password Input
          AppInput(
            label: "Confirm Password",
            hint: "********",
            controller: _confirmController,
            isPassword: true,
            isVisible: _showConfirm,
            onToggleVisibility: () => setState(() => _showConfirm = !_showConfirm),
          ),

          const SizedBox(height: 30),

          // Button Submit
          AppButton(
            text: "Create New Password",
            isLoading: _loading,
            onPressed: _handleReset,
            width: 300,
          ),
        ],
      ),
    );
  }
}