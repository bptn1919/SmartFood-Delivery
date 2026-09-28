import 'package:flutter/material.dart';
import '../models/personal_info_schema.dart';
import '../models/order_draft.dart';
import '../../common/app_components.dart';
import '../repositories/edit_order_repository.dart';

class EditPersonalPage extends StatefulWidget {
  const EditPersonalPage({
    super.key,
    this.initialName = "John Doe",
    this.initialPhone = "0552789521",
    required this.draft,
  });

  final String initialName;
  final String initialPhone;
  final OrderDraft draft;

  @override
  State<EditPersonalPage> createState() => _EditPersonalPageState();
}

class _EditPersonalPageState extends State<EditPersonalPage> {
  // Styling colors matching the pattern
  final Color _primaryOrange = const Color(0xFFFFB68C);
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _textBrown = const Color(0xFF4A3225);
  final Color _inputYellow = const Color(0xFFF9F0C2);

  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _nameCtrl;
  late final TextEditingController _phoneCtrl;

  final EditOrderRepository _repository = EditOrderRepository();
  bool _isLoading = false;

  @override
  void initState() {
    super.initState();
    _nameCtrl = TextEditingController(text: widget.initialName);
    _phoneCtrl = TextEditingController(text: widget.initialPhone);
  }

  @override
  void dispose() {
    _nameCtrl.dispose();
    _phoneCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() => _isLoading = true);

    try {
      final orderUid = widget.draft.uid;
      final payload = PersonalInfoSchema(
        fullName: _nameCtrl.text.trim(),
        phoneNumber: _phoneCtrl.text.trim(),
      );

      final updatedOrder = await _repository.editProfileOfOrder(
        uid: orderUid,
        payload: payload,
      );

      if (!mounted) return;

      showAppSnackBar(
        context,
        'Profile updated successfully!',
        type: SnackBarType.success, // Tự động có icon check, màu xanh, bo góc và nổi lên
      );

      Navigator.pop<Map<String, dynamic>>(context, {
        'name': updatedOrder.fullName,
        'phone': updatedOrder.phoneNumber,
        'updated_draft': updatedOrder,
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _isLoading = false);
      showAppSnackBar(
        context,
        'Failed to update personal information: $e', 
        type: SnackBarType.error, 
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final disabled = _isLoading;

    return Scaffold(
      backgroundColor: _primaryOrange,
      body: Column(
        children: [
          // ===== HEADER WITH PATTERN =====
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 60, 20, 20),
            child: Row(
              children: [
                // Back button
                IconButton(
                  icon: const Icon(Icons.chevron_left, color: Colors.black, size: 30),
                  onPressed: disabled ? null : () => Navigator.of(context).pop(),
                ),
                
                // Title in center
                const Expanded(
                  child: Text(
                    "Personal Information",
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: Colors.white,
                      fontSize: 28,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
                
                // Placeholder for balance
                const SizedBox(width: 48),
              ],
            ),
          ),

          // ===== BODY WITH WHITE CARD =====
          Expanded(
            child: AbsorbPointer(
              absorbing: disabled,
              child: Container(
                width: double.infinity,
                decoration: const BoxDecoration(
                  color: Colors.white,
                  borderRadius: BorderRadius.only(
                    topLeft: Radius.circular(30),
                    topRight: Radius.circular(30),
                  ),
                ),
                child: SingleChildScrollView(
                  padding: const EdgeInsets.all(24),
                  child: Form(
                    key: _formKey,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        // Full Name Field
                        _buildLabel("Full Name*"),
                        const SizedBox(height: 8),
                        TextFormField(
                          controller: _nameCtrl,
                          textInputAction: TextInputAction.next,
                          decoration: _inputDecor(),
                          validator: (v) =>
                              (v == null || v.trim().isEmpty) ? "Please enter your full name" : null,
                        ),
                        const SizedBox(height: 24),

                        // Phone Number Field
                        _buildLabel("Phone Number*"),
                        const SizedBox(height: 8),
                        TextFormField(
                          controller: _phoneCtrl,
                          keyboardType: TextInputType.phone,
                          decoration: _inputDecor(),
                          validator: (v) {
                            final s = v?.trim() ?? "";
                            if (s.isEmpty) return "Please enter your phone number";
                            final onlyDigits = RegExp(r'^\+?\d{8,15}$');
                            if (!onlyDigits.hasMatch(s)) return "Invalid phone number";
                            return null;
                          },
                        ),

                        const SizedBox(height: 40),

                        // Confirm Button
                        Center(
                          child: SizedBox(
                            width: double.infinity,
                            height: 50,
                            child: ElevatedButton(
                              onPressed: _isLoading ? null : _submit,
                              style: ElevatedButton.styleFrom(
                                backgroundColor: _primaryRed,
                                shape: RoundedRectangleBorder(
                                  borderRadius: BorderRadius.circular(25),
                                ),
                                elevation: 2,
                              ),
                              child: _isLoading
                                  ? const SizedBox(
                                      width: 20,
                                      height: 20,
                                      child: CircularProgressIndicator(
                                        strokeWidth: 2,
                                        color: Colors.white,
                                      ),
                                    )
                                  : const Text(
                                      "Confirm",
                                      style: TextStyle(
                                        color: Colors.white,
                                        fontSize: 16,
                                        fontWeight: FontWeight.bold,
                                      ),
                                    ),
                            ),
                          ),
                        ),
                        
                        const SizedBox(height: 20),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildLabel(String text) {
    return Text(
      text,
      style: TextStyle(
        fontSize: 14,
        fontWeight: FontWeight.bold,
        color: _textBrown,
      ),
    );
  }

  InputDecoration _inputDecor() => InputDecoration(
        filled: true,
        fillColor: _inputYellow,
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide: BorderSide.none,
        ),
        hintStyle: const TextStyle(color: Colors.black38, fontSize: 13),
      );
}