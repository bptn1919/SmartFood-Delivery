import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../models/customer_order.dart';
import '../../../models/order_detail.dart';
import 'cancel_order_action.dart';
import 'customer_info_section.dart';
import 'grouped_items_section.dart';
import 'order_price_summary_section.dart';
import 'payment_info_section.dart';
import 'tracking_map_section.dart';

class OrderDetailContent extends StatelessWidget {
  final OrderDetail order;
  final Color accentColor;
  final NumberFormat currencyFormatter;
  final RefreshCallback onRefresh;
  final void Function(OrderLineItem item) onReviewPressed;
  final bool isCancelling;
  final void Function(String orderUid) onCancelPressed;
  final VoidCallback? onEditDeliveryTime;

  const OrderDetailContent({
    super.key,
    required this.order,
    required this.accentColor,
    required this.currencyFormatter,
    required this.onRefresh,
    required this.onReviewPressed,
    required this.isCancelling,
    required this.onCancelPressed,
    this.onEditDeliveryTime,
  });

  Map<String, List<OrderLineItem>> _groupItemsByChef() {
    final groupedItems = <String, List<OrderLineItem>>{};
    for (var item in order.items) {
      groupedItems.putIfAbsent(item.chefName, () => []).add(item);
    }
    return groupedItems;
  }

  @override
  Widget build(BuildContext context) {
    final groupedItems = _groupItemsByChef();

    return RefreshIndicator(
      onRefresh: onRefresh,
      color: accentColor,
      child: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          TrackingMapSection(
            orderUid: order.uid,
            deliveryType: order.deliveryType,
            status: order.status,
            deliveryLatitude: order.deliveryLatitude,
            deliveryLongitude: order.deliveryLongitude,
            trackingLink: order.trackingLink,
          ),
          CustomerInfoSection(
            order: order,
            accentColor: accentColor,
            onEditDeliveryTime: onEditDeliveryTime,
          ),
          const SizedBox(height: 24),
          PaymentInfoSection(order: order),
          const SizedBox(height: 24),
          GroupedItemsSection(
            groupedItems: groupedItems,
            orderStatus: order.status.name.toUpperCase(),
            accentColor: accentColor,
            currencyFormatter: currencyFormatter,
            onReviewPressed: onReviewPressed,
          ),
          const Divider(height: 32, thickness: 1),
          OrderPriceSummarySection(
            order: order,
            currencyFormatter: currencyFormatter,
            totalColor: accentColor,
          ),
          const SizedBox(height: 20),
          CancelOrderAction(
            order: order,
            isCancelling: isCancelling,
            onCancelPressed: onCancelPressed,
          ),
          const SizedBox(height: 20),
        ],
      ),
    );
  }
}
