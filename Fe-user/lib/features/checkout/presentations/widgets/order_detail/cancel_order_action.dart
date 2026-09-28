import 'package:flutter/material.dart';

import '../../../models/customer_order.dart';
import '../../../models/order_detail.dart';

class CancelOrderAction extends StatelessWidget {
  final OrderDetail order;
  final bool isCancelling;
  final void Function(String orderUid) onCancelPressed;

  const CancelOrderAction({
    super.key,
    required this.order,
    required this.isCancelling,
    required this.onCancelPressed,
  });

  @override
  Widget build(BuildContext context) {
    final bool canCancel = order.status == OrderStatus.PENDING ||
        order.status == OrderStatus.DRAFT;

    if (!canCancel) {
      return const SizedBox.shrink();
    }

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: Colors.white,
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.05),
            blurRadius: 10,
            offset: const Offset(0, -5),
          )
        ],
      ),
      child: isCancelling
          ? const Center(
              child: CircularProgressIndicator(color: Colors.redAccent))
          : OutlinedButton(
              style: OutlinedButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 16),
                side: const BorderSide(color: Colors.redAccent, width: 1.5),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
              ),
              onPressed: () => onCancelPressed(order.uid),
              child: const Text(
                "Cancel Order",
                style: TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.bold,
                  color: Colors.redAccent,
                ),
              ),
            ),
    );
  }
}
