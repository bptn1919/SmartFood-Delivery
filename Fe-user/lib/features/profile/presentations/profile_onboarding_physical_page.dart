// import 'package:flutter/material.dart';

// import '../controllers/profile_onboarding_scope.dart';

// class ProfileOnboardingPhysicalPage extends StatefulWidget {
//   const ProfileOnboardingPhysicalPage({super.key});

//   @override
//   State<ProfileOnboardingPhysicalPage> createState() =>
//       _ProfileOnboardingPhysicalPageState();
// }

// class _ProfileOnboardingPhysicalPageState
//     extends State<ProfileOnboardingPhysicalPage> {
//   final _formKey = GlobalKey<FormState>();
//   final _heightController = TextEditingController();
//   final _weightController = TextEditingController();

//   @override
//   void dispose() {
//     _heightController.dispose();
//     _weightController.dispose();
//     super.dispose();
//   }

//   String? _positiveDoubleValidator(String? value) {
//     final parsed = double.tryParse((value ?? '').trim());
//     if (parsed == null) return 'Please enter a valid number.';
//     if (parsed <= 0) return 'Value must be greater than 0.';
//     return null;
//   }

//   void _handleNext() {
//     if (!_formKey.currentState!.validate()) return;

//     final controller = ProfileOnboardingScope.of(context);
//     controller.setPhysicalAttributes(
//       heightCm: double.parse(_heightController.text.trim()),
//       weightKg: double.parse(_weightController.text.trim()),
//     );

//     Navigator.of(context).pushNamed('/preferences');
//   }

//   @override
//   Widget build(BuildContext context) {
//     return Scaffold(
//       backgroundColor: const Color(0xFFFFBB94),
//       body: SafeArea(
//         child: Center(
//           child: SingleChildScrollView(
//             padding: const EdgeInsets.all(24),
//             child: Card(
//               elevation: 0,
//               shape: RoundedRectangleBorder(
//                 borderRadius: BorderRadius.circular(24),
//               ),
//               child: Padding(
//                 padding: const EdgeInsets.all(24),
//                 child: Form(
//                   key: _formKey,
//                   child: Column(
//                     crossAxisAlignment: CrossAxisAlignment.stretch,
//                     mainAxisSize: MainAxisSize.min,
//                     children: [
//                       const Text(
//                         'Tell us about you',
//                         style: TextStyle(
//                           fontSize: 24,
//                           fontWeight: FontWeight.bold,
//                           color: Color(0xFF4A3225),
//                         ),
//                       ),
//                       const SizedBox(height: 8),
//                       const Text(
//                         'We use this to personalize meal recommendations.',
//                         style: TextStyle(color: Colors.grey, height: 1.4),
//                       ),
//                       const SizedBox(height: 24),
//                       TextFormField(
//                         controller: _heightController,
//                         keyboardType: const TextInputType.numberWithOptions(
//                           decimal: true,
//                         ),
//                         decoration: const InputDecoration(
//                           labelText: 'Height (cm)',
//                           border: OutlineInputBorder(),
//                         ),
//                         validator: _positiveDoubleValidator,
//                       ),
//                       const SizedBox(height: 16),
//                       TextFormField(
//                         controller: _weightController,
//                         keyboardType: const TextInputType.numberWithOptions(
//                           decimal: true,
//                         ),
//                         decoration: const InputDecoration(
//                           labelText: 'Weight (kg)',
//                           border: OutlineInputBorder(),
//                         ),
//                         validator: _positiveDoubleValidator,
//                       ),
//                       const SizedBox(height: 24),
//                       ElevatedButton(
//                         onPressed: _handleNext,
//                         style: ElevatedButton.styleFrom(
//                           backgroundColor: const Color(0xFFE55866),
//                           foregroundColor: Colors.white,
//                           padding: const EdgeInsets.symmetric(vertical: 14),
//                           shape: RoundedRectangleBorder(
//                             borderRadius: BorderRadius.circular(14),
//                           ),
//                         ),
//                         child: const Text(
//                           'Next',
//                           style: TextStyle(fontWeight: FontWeight.bold),
//                         ),
//                       ),
//                     ],
//                   ),
//                 ),
//               ),
//             ),
//           ),
//         ),
//       ),
//     );
//   }
// }
import 'package:flutter/material.dart';

import '../controllers/profile_onboarding_scope.dart';

class ProfileOnboardingPhysicalPage extends StatefulWidget {
  const ProfileOnboardingPhysicalPage({super.key});

  @override
  State<ProfileOnboardingPhysicalPage> createState() =>
      _ProfileOnboardingPhysicalPageState();
}

class _ProfileOnboardingPhysicalPageState
    extends State<ProfileOnboardingPhysicalPage> {
  final _formKey = GlobalKey<FormState>();
  final _heightController = TextEditingController();
  final _weightController = TextEditingController();

  @override
  void dispose() {
    _heightController.dispose();
    _weightController.dispose();
    super.dispose();
  }

  String? _positiveDoubleValidator(String? value) {
    final parsed = double.tryParse((value ?? '').trim());
    if (parsed == null) return 'Invalid number';
    if (parsed <= 0) return 'Must be > 0';
    return null;
  }

  void _handleNext() {
    if (!_formKey.currentState!.validate()) return;

    final controller = ProfileOnboardingScope.of(context);
    controller.setPhysicalAttributes(
      heightCm: double.parse(_heightController.text.trim()),
      weightKg: double.parse(_weightController.text.trim()),
    );

    Navigator.of(context).pushNamed('/preferences');
  }

  // Thiết kế Input Field kiểu mới: Nhãn nằm ngoài, Box nền xám nhạt tinh tế
  Widget _buildInputField({
    required String label,
    required String suffix,
    required TextEditingController controller,
    required TextInputAction textInputAction,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: const TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w600,
            color: Color(0xFF666666), // Xám thanh lịch
            letterSpacing: 0.5,
          ),
        ),
        const SizedBox(height: 8),
        TextFormField(
          controller: controller,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          textInputAction: textInputAction,
          cursorColor: const Color(0xFFE55866),
          style: const TextStyle(
            fontSize: 24, // Chữ to rõ ràng khi nhập số
            fontWeight: FontWeight.w700,
            color: Color(0xFF1A1A1A),
          ),
          decoration: InputDecoration(
            suffixText: suffix,
            suffixStyle: const TextStyle(
              fontSize: 18,
              color: Colors.black38,
              fontWeight: FontWeight.w500,
            ),
            filled: true,
            fillColor: const Color(0xFFF8F9FA), // Màu nền siêu nhạt
            contentPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 20),
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(16),
              borderSide: BorderSide.none,
            ),
            focusedBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(16),
              borderSide: const BorderSide(color: Color(0xFFE55866), width: 1.5),
            ),
            errorBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(16),
              borderSide: const BorderSide(color: Colors.redAccent, width: 1.5),
            ),
            focusedErrorBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(16),
              borderSide: const BorderSide(color: Colors.redAccent, width: 1.5),
            ),
          ),
          validator: _positiveDoubleValidator,
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      body: SafeArea(
        child: Form(
          key: _formKey,
          child: CustomScrollView(
            // Cấu trúc này giúp nút bám đáy màn hình và tự cuộn khi có bàn phím
            slivers: [
              SliverFillRemaining(
                hasScrollBody: false,
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 24),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      // --- HEADER SECTION ---
                      const Text(
                        'STEP 1 OF 2',
                        style: TextStyle(
                          color: Color(0xFFE55866),
                          fontSize: 12,
                          fontWeight: FontWeight.w800,
                          letterSpacing: 1.5,
                        ),
                      ),
                      const SizedBox(height: 16),
                      const Text(
                        'Let\'s get to\nknow you.',
                        style: TextStyle(
                          fontSize: 40,
                          fontWeight: FontWeight.w900,
                          color: Color(0xFF1A1A1A),
                          letterSpacing: -1.0,
                          height: 1.1,
                        ),
                      ),
                      const SizedBox(height: 16),
                      const Text(
                        'This helps us calculate your daily nutritional needs and tailor meal recommendations.',
                        style: TextStyle(
                          color: Color(0xFF666666),
                          height: 1.6,
                          fontSize: 16,
                        ),
                      ),
                      const SizedBox(height: 48),

                      // --- FORM SECTION ---
                      _buildInputField(
                        label: 'HEIGHT',
                        suffix: 'cm',
                        controller: _heightController,
                        textInputAction: TextInputAction.next,
                      ),
                      const SizedBox(height: 24),
                      _buildInputField(
                        label: 'WEIGHT',
                        suffix: 'kg',
                        controller: _weightController,
                        textInputAction: TextInputAction.done,
                      ),

                      // Khoảng trống đẩy nút xuống cuối màn hình
                      const Spacer(),
                      const SizedBox(height: 40),

                      // --- BOTTOM ACTION ---
                      ElevatedButton(
                        onPressed: _handleNext,
                        style: ElevatedButton.styleFrom(
                          backgroundColor: const Color(0xFFE55866), // Trả lại màu thương hiệu
                          foregroundColor: Colors.white,
                          elevation: 0,
                          padding: const EdgeInsets.symmetric(vertical: 20),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(16),
                          ),
                        ),
                        child: const Text(
                          'CONTINUE',
                          style: TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.w800,
                            letterSpacing: 1.2,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}