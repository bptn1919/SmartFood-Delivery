import 'package:flutter/material.dart';
import 'package:testing/features/auth/repositories/auth_repository.dart';
import 'package:testing/features/common/app_components.dart';


class ChangePasswordPage extends StatefulWidget {
  const ChangePasswordPage({super.key});

  @override
  State<ChangePasswordPage> createState() => _ChangePasswordPageState();
}

class _ChangePasswordPageState extends State<ChangePasswordPage> {
  // --- TONE MÀU HỆ THỐNG AMOMEAL ---
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _textBrown = const Color(0xFF4A3225);
  final Color _inputFillColor = const Color(0xFFEEEEEE);

  // --- CONTROLLERS ---
  final TextEditingController _oldPassController = TextEditingController();
  final TextEditingController _newPassController = TextEditingController();
  final TextEditingController _confirmPassController = TextEditingController();

  // --- STATE ẨN/HIỆN MẬT KHẨU ---
  bool _obscureOld = true;
  bool _obscureNew = true;
  bool _obscureConfirm = true;

  // --- REPO & LOADING STATE ---
  final AuthRepository _authRepo = AuthRepository(); // Mở comment khi dùng thật
  bool _isLoading = false;

  @override
  void dispose() {
    _oldPassController.dispose();
    _newPassController.dispose();
    _confirmPassController.dispose();
    super.dispose();
  }

  // --- LOGIC XỬ LÝ ĐỔI MẬT KHẨU ---
  Future<void> _handleChangePassword() async {
    // 1. Lấy dữ liệu và Xóa khoảng trắng thừa
    final oldPass = _oldPassController.text.trim();
    final newPass = _newPassController.text.trim();
    final confirmPass = _confirmPassController.text.trim();

    // 2. Validate phía Client (Frontend)
    if (oldPass.isEmpty || newPass.isEmpty || confirmPass.isEmpty) {
      showAppSnackBar(context, "Please fill in all fields!", type: SnackBarType.warning);
      return;
    }

    if (newPass.length < 8) {
      showAppSnackBar(context, "New password must be at least 8 characters!", type: SnackBarType.warning);
      return;
    }

    if (newPass != confirmPass) {
      showAppSnackBar(context, "Confirm password does not match!", type: SnackBarType.warning);
      return;
    }

    if (oldPass == newPass) {
      showAppSnackBar(context, "New password must be different from the current password!", type: SnackBarType.warning);
      return;
    }

    // 3. Gọi API
    setState(() => _isLoading = true);
    try {
      // Đợi gọi API (Mở comment khi chạy thật)
      final success = await _authRepo.changePassword(oldPass, newPass);

      if (!mounted) return;

      if (success) {
        showAppSnackBar(context, "Change password successful!", type: SnackBarType.success);
        
        // TECH LEAD TÍP: Tùy vào yêu cầu bảo mật, bạn có thể gọi hàm _authRepo.logout() ở đây 
        // để ép user đăng nhập lại bằng pass mới. Tạm thời tôi chỉ Pop về trang trước.
        Navigator.pop(context);
      } else {
        showAppSnackBar(context, "Change password failed, please try again!", type: SnackBarType.error);
      }
    } catch (e) {
      if (mounted) {
        // Lỗi từ catch (e) { rethrow; } bên Repo sẽ bay thẳng ra đây
        showAppSnackBar(context, "Error: $e!", type: SnackBarType.error);
      }
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        backgroundColor: Colors.white,
        elevation: 0,
        leading: IconButton(
          icon: Container(
            padding: const EdgeInsets.all(4),
            decoration: BoxDecoration(
              color: _primaryRed.withOpacity(0.1),
              borderRadius: BorderRadius.circular(8),
            ),
            child: Icon(Icons.arrow_back_ios_new, color: _primaryRed, size: 18),
          ),
          onPressed: () => Navigator.pop(context),
        ),
        centerTitle: true,
        title: Text(
          "Change Password",
          style: TextStyle(color: _textBrown, fontSize: 20, fontWeight: FontWeight.bold),
        ),
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Header Text
              Text(
                "Create a new password",
                style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold, color: _textBrown),
              ),
              const SizedBox(height: 8),
              Text(
                "Your new password must be different from previous used passwords.",
                style: TextStyle(fontSize: 14, color: Colors.grey.shade600),
              ),
              const SizedBox(height: 32),

              // --- FORM ---
              _buildLabel("Current Password"),
              _buildPasswordField(
                controller: _oldPassController,
                hint: "Enter your current password",
                isObscure: _obscureOld,
                onToggle: () => setState(() => _obscureOld = !_obscureOld),
              ),
              const SizedBox(height: 24),

              _buildLabel("New Password"),
              _buildPasswordField(
                controller: _newPassController,
                hint: "Enter your new password",
                isObscure: _obscureNew,
                onToggle: () => setState(() => _obscureNew = !_obscureNew),
              ),
              const SizedBox(height: 8),
              Text("Must be at least 6 characters.", style: TextStyle(color: Colors.grey.shade500, fontSize: 12)),
              const SizedBox(height: 24),

              _buildLabel("Confirm New Password"),
              _buildPasswordField(
                controller: _confirmPassController,
                hint: "Confirm your new password",
                isObscure: _obscureConfirm,
                onToggle: () => setState(() => _obscureConfirm = !_obscureConfirm),
              ),
              const SizedBox(height: 48),

              // --- NÚT SUBMIT ---
              SizedBox(
                width: double.infinity,
                height: 50,
                child: ElevatedButton(
                  style: ElevatedButton.styleFrom(
                    backgroundColor: _primaryRed,
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                    elevation: 0,
                  ),
                  onPressed: _isLoading ? null : _handleChangePassword,
                  child: _isLoading
                      ? const SizedBox(
                          width: 20, height: 20,
                          child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2),
                        )
                      : const Text(
                          "Update Password",
                          style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Colors.white),
                        ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  // --- HELPER WIDGETS ---
  Widget _buildLabel(String text) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8.0),
      child: Text(text, style: TextStyle(fontSize: 14, fontWeight: FontWeight.bold, color: _textBrown)),
    );
  }

  Widget _buildPasswordField({
    required TextEditingController controller,
    required String hint,
    required bool isObscure,
    required VoidCallback onToggle,
  }) {
    return Container(
      decoration: BoxDecoration(color: _inputFillColor, borderRadius: BorderRadius.circular(8)),
      child: TextField(
        controller: controller,
        obscureText: isObscure, // Che mật khẩu
        style: const TextStyle(color: Colors.black87, fontSize: 15),
        decoration: InputDecoration(
          hintText: hint,
          hintStyle: TextStyle(color: Colors.grey.shade500, fontSize: 14),
          border: InputBorder.none,
          contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
          // Nút Mắt để toggle
          suffixIcon: IconButton(
            icon: Icon(
              isObscure ? Icons.visibility_off_outlined : Icons.visibility_outlined,
              color: Colors.grey.shade600,
              size: 20,
            ),
            onPressed: onToggle,
          ),
        ),
      ),
    );
  }
}