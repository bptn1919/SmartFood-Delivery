import 'package:flutter/material.dart';

import 'checkout_styles.dart';

class PersonalInfoSection extends StatelessWidget {
  final String fullName;
  final String phoneNumber;
  final VoidCallback onEdit;

  const PersonalInfoSection({
    super.key,
    required this.fullName,
    required this.phoneNumber,
    required this.onEdit,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        CheckoutSectionHeader(title: "Personal Info", onEdit: onEdit),
        const SizedBox(height: 12),
        CheckoutInfoLine(label: "Full Name", value: fullName, isBold: true),
        CheckoutInfoLine(
            label: "Phone Number", value: phoneNumber, isBold: true),
        const CheckoutDivider(),
      ],
    );
  }
}
