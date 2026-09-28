import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import 'checkout_styles.dart';

class PriceSummarySection extends StatelessWidget {
  final double subtotal;
  final double taxAndFees;
  final double deliveryFee;
  final double totalDiscount;
  final double finalTotal;
  final bool placing;
  final VoidCallback onPlaceOrder;

  const PriceSummarySection({
    super.key,
    required this.subtotal,
    required this.taxAndFees,
    required this.deliveryFee,
    required this.totalDiscount,
    required this.finalTotal,
    required this.placing,
    required this.onPlaceOrder,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        PriceRowFigma(label: "Subtotal", value: subtotal),
        PriceRowFigma(label: "Tax and Fees", value: taxAndFees),
        PriceRowFigma(label: "Delivery", value: deliveryFee),
        if (totalDiscount > 0)
          PriceRowFigma(label: "Discount", value: -totalDiscount),
        const SizedBox(height: 12),
        const CheckoutDashedLine(),
        const SizedBox(height: 12),
        PriceRowFigma(label: "Total", value: finalTotal, isTotal: true),
        const SizedBox(height: 24),
        SizedBox(
          width: double.infinity,
          height: 50,
          child: ElevatedButton(
            style: ElevatedButton.styleFrom(
              backgroundColor: kCheckoutPrimaryPink,
              shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(25)),
              elevation: 0,
            ),
            onPressed: placing ? null : onPlaceOrder,
            child: placing
                ? const SizedBox(
                    width: 24,
                    height: 24,
                    child: CircularProgressIndicator(
                        color: Colors.white, strokeWidth: 2))
                : const Text("Place Order",
                    style: TextStyle(
                        color: Colors.white,
                        fontSize: 16,
                        fontWeight: FontWeight.bold)),
          ),
        ),
        const SizedBox(height: 10),
      ],
    );
  }
}

class PriceRowFigma extends StatelessWidget {
  final String label;
  final double value;
  final bool isTotal;

  const PriceRowFigma({
    super.key,
    required this.label,
    required this.value,
    this.isTotal = false,
  });

  @override
  Widget build(BuildContext context) {
    final currencyFormatter = NumberFormat.currency(
      locale: 'vi_VN',
      symbol: 'đ',
      decimalDigits: 0,
    );

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label,
              style: TextStyle(
                  fontSize: 14,
                  fontWeight: isTotal ? FontWeight.bold : FontWeight.normal,
                  color: kCheckoutDarkBrown)),
          Text(currencyFormatter.format(value),
              style: TextStyle(
                  fontSize: 14,
                  fontWeight: isTotal ? FontWeight.bold : FontWeight.w500,
                  color: kCheckoutDarkBrown)),
        ],
      ),
    );
  }
}
