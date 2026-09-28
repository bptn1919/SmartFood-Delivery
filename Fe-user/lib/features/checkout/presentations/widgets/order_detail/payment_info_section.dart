import 'package:flutter/material.dart';

import '../../../models/order_detail.dart';
import 'order_detail_styles.dart';

class PaymentInfoSection extends StatelessWidget {
  final OrderDetail order;

  const PaymentInfoSection({
    super.key,
    required this.order,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const OrderDetailSectionHeader(title: 'Payment'),
        OrderDetailInfoTile(
          icon: Icons.payments_outlined,
          title: order.paymentMethod.name,
          subtitle: 'Status: ${order.status.name}',
        ),
      ],
    );
  }
}
