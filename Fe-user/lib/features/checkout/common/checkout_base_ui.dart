import 'package:flutter/material.dart';

class AppColors {
  static const orange = Color(0xFFFFBB94);
  static const accent = Color(0xFFE84D67);
  static const bgWhite = Color(0xFFF7F7F7);
}

/// Widget khung sườn chung cho: Cart, Checkout, Edit Pages, Add Address
class CheckoutBasePage extends StatelessWidget {
  final String title;
  final VoidCallback? onBack;
  final Widget child;
  final Widget? bottomAction; // Nút bấm ở dưới cùng (nếu có)
  final bool isLoading;
  final String? error;

  const CheckoutBasePage({
    super.key,
    required this.title,
    required this.child,
    this.onBack,
    this.bottomAction,
    this.isLoading = false,
    this.error,
  });

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.orange,
      body: Column(
        children: [
          // 1. Header Cam
          SafeArea(
            bottom: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(8, 8, 12, 12),
              child: Row(
                children: [
                  IconButton(
                    onPressed: onBack ?? () => Navigator.pop(context),
                    icon: const Icon(Icons.arrow_back, color: Colors.black),
                  ),
                  Expanded(
                    child: Center(
                      child: Text(
                        title,
                        style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w800),
                      ),
                    ),
                  ),
                  const SizedBox(width: 48), // Balance spacing
                ],
              ),
            ),
          ),

          // 2. White Sheet Content
          Expanded(
            child: Container(
              width: double.infinity,
              decoration: const BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.vertical(top: Radius.circular(26)),
              ),
              child: ClipRRect(
                borderRadius: const BorderRadius.vertical(top: Radius.circular(26)),
                child: Column(
                  children: [
                    Expanded(
                      child: isLoading
                          ? const Center(child: CircularProgressIndicator(color: AppColors.accent))
                          : error != null
                              ? Center(child: Text(error!, style: const TextStyle(color: Colors.red)))
                              : child,
                    ),
                    if (bottomAction != null)
                      Container(
                        padding: const EdgeInsets.all(16),
                        decoration: BoxDecoration(
                          color: Colors.white,
                          boxShadow: [BoxShadow(color: Colors.black.withOpacity(0.05), blurRadius: 10, offset: const Offset(0, -5))],
                        ),
                        child: bottomAction,
                      )
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// Nút bấm chính (Primary Button)
class PrimaryButton extends StatelessWidget {
  final String text;
  final VoidCallback? onPressed;
  final bool isLoading;

  const PrimaryButton({super.key, required this.text, this.onPressed, this.isLoading = false});

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: double.infinity,
      height: 48,
      child: ElevatedButton(
        onPressed: isLoading ? null : onPressed,
        style: ElevatedButton.styleFrom(
          backgroundColor: AppColors.accent,
          disabledBackgroundColor: AppColors.accent.withOpacity(0.5),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
          elevation: 0,
        ),
        child: isLoading
            ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2))
            : Text(text, style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 16)),
      ),
    );
  }
}