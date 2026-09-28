import 'package:flutter/material.dart';
import '../repositories/auth_repository.dart';
import '../../common/app_components.dart';

class SignupPage extends StatefulWidget {
  const SignupPage({super.key});

  @override
  State<SignupPage> createState() => _SignupPageState();
}

class _SignupPageState extends State<SignupPage> {
  // Controller
  final _firstnameController = TextEditingController();
  final _lastnameController = TextEditingController();
  final _emailController = TextEditingController();
  final _phoneController = TextEditingController();
  final _passwordController = TextEditingController();
  final _confirmController = TextEditingController();

  bool _showPassword = false;
  bool _showConfirm = false;
  bool _loading = false;
  final AuthRepository _authRepo = AuthRepository();

  @override
  void dispose() {
    _firstnameController.dispose();
    _lastnameController.dispose();
    _emailController.dispose();
    _phoneController.dispose();
    _passwordController.dispose();
    _confirmController.dispose();
    super.dispose();
  }

  Future<void> _handleSignUp() async {
    // ... Logic validate giữ nguyên ...
    // Giả sử validate ok
    
    setState(() => _loading = true);

    try {
      await _authRepo.signup(
        firstName: _firstnameController.text.trim(),
        lastName: _lastnameController.text.trim(),
        email: _emailController.text.trim(),
        phoneNumber: _phoneController.text.trim(),
        password: _passwordController.text,
        passwordConfirm: _confirmController.text,
      );

      if (!mounted) return;
      showAppDialog(context, "Success", "Sign up successful! Please verify OTP.", onOk: () {
        Navigator.pushNamed(context, "/verify-otp-su", arguments: _emailController.text.trim());
      });
    } catch (e) {
       if (!mounted) return;
       showAppDialog(context, "Sign Up Failed", e.toString(), isError: true);
    } finally {
       if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AuthTemplate(
      title: "Sign Up",
      onBack: () => Navigator.pop(context),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text("Enter your details here", style: TextStyle(fontSize: 24, fontWeight: FontWeight.w800)),
          const SizedBox(height: 10),

          // Tái sử dụng AppInput hàng loạt
          AppInput(label: "First Name", controller: _firstnameController, hint: "John"),
          AppInput(label: "Last Name", controller: _lastnameController, hint: "Doe"),
          AppInput(
            label: "Email", 
            controller: _emailController, 
            hint: "johndoe@example.com", 
            keyboardType: TextInputType.emailAddress
          ),
          AppInput(
            label: "Phone Number", 
            controller: _phoneController, 
            hint: "055...", 
            keyboardType: TextInputType.phone
          ),
          
          AppInput(
            label: "Password",
            controller: _passwordController,
            isPassword: true,
            isVisible: _showPassword,
            onToggleVisibility: () => setState(() => _showPassword = !_showPassword),
          ),
          
          AppInput(
            label: "Confirm Password",
            controller: _confirmController,
            isPassword: true,
            isVisible: _showConfirm,
            onToggleVisibility: () => setState(() => _showConfirm = !_showConfirm),
          ),

          const SizedBox(height: 15),
          Center( 
            child: Column(
              children: [
                const Text(
                  "By continuing, you agree to",
                  style: TextStyle(fontSize: 12, color: Colors.black54),
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 2),
                RichText(
                  textAlign: TextAlign.center,
                  text: TextSpan(
                    style: const TextStyle(fontSize: 12),
                    children: [
                      TextSpan(
                        text: "Terms of Use ",
                        style: TextStyle(color: const Color(0xFFE95322)),
                      ),
                      const TextSpan(
                        text: "and ",
                        style: TextStyle(color: Colors.black54),
                      ),
                      TextSpan(
                        text: "Privacy Policy.",
                        style: TextStyle(color: const Color(0xFFE95322)),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 15),

          AppButton(
            text: "Sign Up",
            isLoading: _loading,
            onPressed: _handleSignUp,
            width: 200,
          ),

          const SizedBox(height: 15),
          Center(
            child: GestureDetector(
              onTap: () => Navigator.pop(context),
              child: RichText( 
                text: TextSpan(
                  children: [
                    const TextSpan(
                      text: "Already have an account? ",
                      style: TextStyle(color: Colors.black54), // Màu đen
                    ),
                    TextSpan(
                      text: "Log in",
                      style: TextStyle(color: AppColors.primary, fontWeight: FontWeight.bold), // Màu cam từ AppColors
                    ),
                  ],
                ),
              ),
            ),
          )
        ],
      ),
    );
  }
}