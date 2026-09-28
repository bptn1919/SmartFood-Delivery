import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../models/order_detail.dart';

class OrderPriceSummarySection extends StatelessWidget {
  final OrderDetail order;
  final NumberFormat currencyFormatter;
  final Color totalColor;

  const OrderPriceSummarySection({
    super.key,
    required this.order,
    required this.currencyFormatter,
    required this.totalColor,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        OrderPriceRow(
          label: 'Subtotal',
          value: currencyFormatter.format(order.subTotal),
        ),
        OrderPriceRow(
          label: 'Tax & Fees',
          value: currencyFormatter.format(order.taxAndFees),
        ),
        OrderPriceRow(
          label: 'Delivery Fee',
          value: currencyFormatter.format(order.deliveryFee),
        ),
        const SizedBox(height: 8),
        OrderPriceRow(
          label: 'Total',
          value: currencyFormatter.format(order.totalPrice),
          isTotal: true,
          color: totalColor,
        ),
      ],
    );
  }
}

class OrderPriceRow extends StatelessWidget {
  final String label;
  final String value;
  final bool isTotal;
  final Color? color;

  const OrderPriceRow({
    super.key,
    required this.label,
    required this.value,
    this.isTotal = false,
    this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(
            label,
            style: TextStyle(
              fontSize: isTotal ? 16 : 14,
              fontWeight: isTotal ? FontWeight.bold : FontWeight.w500,
              color: isTotal ? Colors.black : Colors.grey.shade600,
            ),
          ),
          Text(
            value,
            style: TextStyle(
              fontSize: isTotal ? 18 : 14,
              fontWeight: isTotal ? FontWeight.bold : FontWeight.w600,
              color: color ?? Colors.black87,
            ),
          ),
        ],
      ),
    );
  }
}
