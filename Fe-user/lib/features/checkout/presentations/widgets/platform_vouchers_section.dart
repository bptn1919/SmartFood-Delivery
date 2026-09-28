import 'package:flutter/material.dart';

import 'checkout_styles.dart';

typedef ApplyPlatformVoucher = Future<void> Function(String type, String code);

class PlatformVouchersSection extends StatelessWidget {
  final TextEditingController platformSubtotalCtrl;
  final TextEditingController platformShippingCtrl;
  final String? appliedPlatformSubtotal;
  final String? appliedPlatformShipping;
  final String? validatingPlatformType;
  final ApplyPlatformVoucher onApplyPlatformVoucher;
  final void Function(String type) onRemovePlatformVoucher;

  const PlatformVouchersSection({
    super.key,
    required this.platformSubtotalCtrl,
    required this.platformShippingCtrl,
    required this.appliedPlatformSubtotal,
    required this.appliedPlatformShipping,
    required this.validatingPlatformType,
    required this.onApplyPlatformVoucher,
    required this.onRemovePlatformVoucher,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 8),
        const Text("Platform Vouchers",
            style: TextStyle(
                color: kCheckoutDarkBrown,
                fontWeight: FontWeight.bold,
                fontSize: 16)),
        const SizedBox(height: 12),
        PlatformVoucherInputRow(
          hintText: "Enter platform discount code",
          icon: Icons.confirmation_number_outlined,
          ctrl: platformSubtotalCtrl,
          appliedCode: appliedPlatformSubtotal,
          isLoad: validatingPlatformType == "PLATFORM_SUBTOTAL",
          onApply: () => onApplyPlatformVoucher(
              "PLATFORM_SUBTOTAL", platformSubtotalCtrl.text),
          onRemove: () => onRemovePlatformVoucher("PLATFORM_SUBTOTAL"),
        ),
        const SizedBox(height: 12),
        PlatformVoucherInputRow(
          hintText: "Enter freeship code",
          icon: Icons.local_shipping_outlined,
          ctrl: platformShippingCtrl,
          appliedCode: appliedPlatformShipping,
          isLoad: validatingPlatformType == "PLATFORM_SHIPPING",
          onApply: () => onApplyPlatformVoucher(
              "PLATFORM_SHIPPING", platformShippingCtrl.text),
          onRemove: () => onRemovePlatformVoucher("PLATFORM_SHIPPING"),
        ),
        const SizedBox(height: 24),
      ],
    );
  }
}

class PlatformVoucherInputRow extends StatelessWidget {
  final String hintText;
  final IconData icon;
  final TextEditingController ctrl;
  final String? appliedCode;
  final bool isLoad;
  final VoidCallback onApply;
  final VoidCallback onRemove;

  const PlatformVoucherInputRow({
    super.key,
    required this.hintText,
    required this.icon,
    required this.ctrl,
    required this.appliedCode,
    required this.isLoad,
    required this.onApply,
    required this.onRemove,
  });

  @override
  Widget build(BuildContext context) {
    final hasCode = appliedCode != null && appliedCode!.isNotEmpty;

    return Row(
      children: [
        Expanded(
          child: SizedBox(
            height: 40,
            child: TextField(
              controller: ctrl,
              enabled: !hasCode,
              decoration: InputDecoration(
                hintText: hintText,
                hintStyle:
                    const TextStyle(fontSize: 13, color: kCheckoutGreyText),
                filled: true,
                fillColor: Colors.white,
                contentPadding: const EdgeInsets.symmetric(horizontal: 16),
                border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(20),
                    borderSide:
                        const BorderSide(color: kCheckoutLightPinkLine)),
                enabledBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(20),
                    borderSide:
                        const BorderSide(color: kCheckoutLightPinkLine)),
                focusedBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(20),
                    borderSide: const BorderSide(color: kCheckoutPrimaryPink)),
                prefixIcon: Icon(icon, color: kCheckoutPrimaryPink, size: 20),
                suffixIcon: hasCode
                    ? const Icon(Icons.check_circle,
                        color: Colors.green, size: 20)
                    : null,
              ),
            ),
          ),
        ),
        const SizedBox(width: 8),
        SizedBox(
          height: 40,
          child: ElevatedButton(
            onPressed: isLoad ? null : (hasCode ? onRemove : onApply),
            style: ElevatedButton.styleFrom(
              backgroundColor:
                  hasCode ? Colors.grey.shade300 : kCheckoutPrimaryPink,
              foregroundColor: hasCode ? Colors.black87 : Colors.white,
              elevation: 0,
              shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(20)),
            ),
            child: isLoad
                ? const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(
                        strokeWidth: 2, color: Colors.white))
                : Text(hasCode ? "Remove" : "Apply",
                    style: const TextStyle(
                        fontSize: 13, fontWeight: FontWeight.bold)),
          ),
        ),
      ],
    );
  }
}
