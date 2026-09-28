import 'package:flutter/material.dart';

import 'checkout_styles.dart';

class CheckoutPaymentSection extends StatelessWidget {
  final String paymentMethod;
  final String phoneNumber;
  final bool isMomoPayment;
  final VoidCallback onEdit;

  const CheckoutPaymentSection({
    super.key,
    required this.paymentMethod,
    required this.phoneNumber,
    required this.isMomoPayment,
    required this.onEdit,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        CheckoutSectionHeader(title: "Payment Method", onEdit: onEdit),
        const SizedBox(height: 12),
        Row(
          children: [
            if (isMomoPayment || paymentMethod.toUpperCase() == 'MOMO')
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
                decoration: BoxDecoration(
                    color: const Color(0xFFA50064),
                    borderRadius: BorderRadius.circular(4)),
                child: const Text("mo\nmo",
                    style: TextStyle(
                        color: Colors.white,
                        fontSize: 9,
                        fontWeight: FontWeight.bold,
                        height: 1.1)),
              )
            else
              const Icon(Icons.payment, color: kCheckoutPrimaryPink, size: 24),
            const Spacer(),
            Text(
                paymentMethod.isEmpty
                    ? "Select Payment"
                    : (isMomoPayment ? phoneNumber : paymentMethod),
                style: const TextStyle(
                    color: kCheckoutDarkBrown,
                    fontWeight: FontWeight.bold,
                    fontSize: 13)),
          ],
        ),
        const CheckoutDivider(),
      ],
    );
  }
}
