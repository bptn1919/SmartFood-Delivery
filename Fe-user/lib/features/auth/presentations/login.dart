import 'package:flutter/material.dart';
import '../repositories/auth_repository.dart';
import '../state/auth_session.dart';
import '../../common/app_components.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../../chat/service/notification_service.dart';

class LoginPage extends StatefulWidget {
  const LoginPage({super.key});

  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  final _emailController = TextEditingController();
  final _passwordController = TextEditingController();

  bool _showPassword = false;
  bool _loading = false;
  final AuthRepository _authRepo = AuthRepository();

  Future<void> _handleLogin() async {
    final email = _emailController.text.trim();
    final password = _passwordController.text.trim();

    if (email.isEmpty || password.isEmpty) {
      showAppDialog(context, "Error", "Please fill in all fields",
          isError: true);
      return;
    }

    setState(() => _loading = true);
    debugPrint("🚀 [Login] Attempting login for email: $email");

    try {
      // 1. Thực hiện Login & Lưu Cache (Logic nằm trong Repo)
      final user = await _authRepo.login(email, password);

      if (!mounted) return;

      // 2. Kiểm tra JWT Token
      final prefs = await SharedPreferences.getInstance();
      final String bearerToken = prefs.getString("token") ?? '';

      if (bearerToken.isNotEmpty) {
        debugPrint('✅ [Login] Authentication successful. JWT retrieved.');
        debugPrint(
            '📲 [Login] Requesting Notification Permission & Updating FCM Token...');

        // 👇 TECH LEAD FIX: Sử dụng Singleton và hàm mới của NotificationService
        await NotificationService().registerDeviceAndToken(bearerToken);
        if (!mounted) return;
      } else {
        debugPrint(
            '🚨 [Login] Warning: JWT Token not found in Cache. FCM registration skipped.');
      }

      // 3. Chuẩn bị lời chào
      AuthSession.instance.setUser(user);
      final isChef = user.isChef ?? false;
      final welcomeMessage = isChef
          ? "Welcome back, Chef ${user.fullname ?? user.username}!"
          : "Welcome back, ${user.fullname ?? user.username}!";

      debugPrint("🎉 [Login] User info parsed successfully. isChef: $isChef");

      // 4. Hiển thị Dialog và Chuyển hướng
      if (!mounted) return;
      showAppDialog(context, "Success", welcomeMessage, onOk: () {
        final nextRoute = user.isOnboarded ? "/home" : "/profile-onboarding";
        debugPrint("🧭 [Login] Navigating to $nextRoute...");
        Navigator.pushNamedAndRemoveUntil(
          context,
          nextRoute,
          (route) => false,
        );
      });
    } catch (e) {
      if (!mounted) return;

      debugPrint("❌ [Login] Process failed with Exception: ${e.toString()}");

      String errorMessage = "Email or password is incorrect";
      final errorString = e.toString().toLowerCase();

      if (errorString.contains("403") || errorString.contains("forbidden")) {
        errorMessage = "Email or password is incorrect";
      } else if (errorString.contains("500")) {
        errorMessage = "Server error. Please try again later";
      } else if (errorString.contains("timeout") ||
          errorString.contains("network")) {
        errorMessage = "Network error. Please check your connection";
      }

      showAppDialog(context, "Login Failed", errorMessage, isError: true);
    } finally {
      if (mounted) {
        setState(() => _loading = false);
        debugPrint("🛑 [Login] Execution finished. Loading state reset.");
      }
    }
  }

  @override
  void dispose() {
    _emailController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AuthTemplate(
      title: "Log In",
      onBack: () => Navigator.pop(context),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SizedBox(height: 10),
          const Text("Welcome",
              style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold)),
          const SizedBox(height: 20),
          AppInput(
            label: "Email",
            hint: "johndoe@example.com",
            controller: _emailController,
            keyboardType: TextInputType.emailAddress,
          ),
          AppInput(
            label: "Password",
            hint: "********",
            controller: _passwordController,
            isPassword: true,
            isVisible: _showPassword,
            onToggleVisibility: () =>
                setState(() => _showPassword = !_showPassword),
          ),
          Align(
            alignment: Alignment.centerRight,
            child: TextButton(
              onPressed: () => Navigator.pushNamed(context, "/verify-mail"),
              child: const Text("Forget Password?",
                  style: TextStyle(color: AppColors.primary)),
            ),
          ),
          const SizedBox(height: 20),
          AppButton(
            text: "Log In",
            isLoading: _loading,
            onPressed: _handleLogin,
            width: 200,
          ),
          const SizedBox(height: 30),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Text("Don’t have an account? "),
              GestureDetector(
                onTap: () => Navigator.pushNamed(context, "/signup"),
                child: const Text(
                  "Sign Up",
                  style: TextStyle(
                      color: AppColors.primary, fontWeight: FontWeight.bold),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
