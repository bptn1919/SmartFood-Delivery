import 'package:flutter/material.dart';

import 'checkout_styles.dart';

class CheckoutDeliveryTimeSection extends StatelessWidget {
  final String deliveryTime;
  final VoidCallback onEdit;

  const CheckoutDeliveryTimeSection({
    super.key,
    required this.deliveryTime,
    required this.onEdit,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        CheckoutSectionHeader(title: "Delivery Time", onEdit: onEdit),
        const SizedBox(height: 12),
        Text(deliveryTime.isNotEmpty ? deliveryTime : "--:--",
            style: const TextStyle(color: kCheckoutGreyText, fontSize: 13)),
      ],
    );
  }
}
