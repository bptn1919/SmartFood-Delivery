import 'package:flutter/material.dart';

const Color kCheckoutPrimaryPink = Color(0xFFD85C6B);
const Color kCheckoutDarkBrown = Color(0xFF4A2C2A);
const Color kCheckoutGreyText = Color(0xFF8B8B8B);
const Color kCheckoutLightPinkLine = Color(0xFFF3D5D8);
const Color kCheckoutPeachBg = Color(0xFFFFC6A6);
const Color kCheckoutBgWhite = Color(0xFFFBFBFB);

class CheckoutSectionHeader extends StatelessWidget {
  final String title;
  final VoidCallback onEdit;

  const CheckoutSectionHeader({
    super.key,
    required this.title,
    required this.onEdit,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(title,
            style: const TextStyle(
                color: kCheckoutDarkBrown,
                fontWeight: FontWeight.bold,
                fontSize: 16)),
        GestureDetector(
          onTap: onEdit,
          child: const Icon(Icons.edit_outlined,
              color: kCheckoutPrimaryPink, size: 18),
        ),
      ],
    );
  }
}

class CheckoutDivider extends StatelessWidget {
  const CheckoutDivider({super.key});

  @override
  Widget build(BuildContext context) {
    return const Padding(
      padding: EdgeInsets.symmetric(vertical: 16),
      child: Divider(color: kCheckoutLightPinkLine, thickness: 1, height: 1),
    );
  }
}

class CheckoutInfoLine extends StatelessWidget {
  final String label;
  final String value;
  final bool isBold;

  const CheckoutInfoLine({
    super.key,
    required this.label,
    required this.value,
    this.isBold = false,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label,
              style: const TextStyle(color: kCheckoutGreyText, fontSize: 13)),
          Text(value,
              style: TextStyle(
                color: isBold ? kCheckoutDarkBrown : Colors.black,
                fontWeight: isBold ? FontWeight.bold : FontWeight.w500,
                fontSize: 13,
              )),
        ],
      ),
    );
  }
}

class CheckoutDashedLine extends StatelessWidget {
  const CheckoutDashedLine({super.key});

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (BuildContext context, BoxConstraints constraints) {
        final boxWidth = constraints.constrainWidth();
        const dashWidth = 4.0;
        const dashHeight = 1.0;
        final dashCount = (boxWidth / (2 * dashWidth)).floor();
        return Flex(
          direction: Axis.horizontal,
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: List.generate(dashCount, (_) {
            return const SizedBox(
              width: dashWidth,
              height: dashHeight,
              child: DecoratedBox(
                decoration: BoxDecoration(color: kCheckoutLightPinkLine),
              ),
            );
          }),
        );
      },
    );
  }
}
