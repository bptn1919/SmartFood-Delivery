import 'package:flutter/material.dart';

import 'checkout_styles.dart';

class CheckoutAddressSection extends StatelessWidget {
  final String deliveryAddress;
  final VoidCallback onEdit;

  const CheckoutAddressSection({
    super.key,
    required this.deliveryAddress,
    required this.onEdit,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        CheckoutSectionHeader(title: "Shipping Address", onEdit: onEdit),
        const SizedBox(height: 12),
        const Text("Home",
            style: TextStyle(
                color: kCheckoutDarkBrown,
                fontWeight: FontWeight.bold,
                fontSize: 14)),
        const SizedBox(height: 4),
        Text(deliveryAddress.isEmpty ? "Select Address" : deliveryAddress,
            style: const TextStyle(color: kCheckoutGreyText, fontSize: 13)),
        const CheckoutDivider(),
      ],
    );
  }
}
