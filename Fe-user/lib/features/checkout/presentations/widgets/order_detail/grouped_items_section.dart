import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../models/customer_order.dart';
import 'order_detail_styles.dart';

class GroupedItemsSection extends StatelessWidget {
  final Map<String, List<OrderLineItem>> groupedItems;
  final String orderStatus;
  final Color accentColor;
  final NumberFormat currencyFormatter;
  final void Function(OrderLineItem item) onReviewPressed;

  const GroupedItemsSection({
    super.key,
    required this.groupedItems,
    required this.orderStatus,
    required this.accentColor,
    required this.currencyFormatter,
    required this.onReviewPressed,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const OrderDetailSectionHeader(title: 'Items'),
        const SizedBox(height: 12),
        if (groupedItems.isEmpty)
          const Center(
            child: Text("No items", style: TextStyle(color: Colors.grey)),
          )
        else
          ...groupedItems.entries.map((entry) {
            final chefName = entry.key;
            final chefItems = entry.value;

            return ChefItemsCard(
              chefName: chefName,
              chefItems: chefItems,
              orderStatus: orderStatus,
              accentColor: accentColor,
              currencyFormatter: currencyFormatter,
              onReviewPressed: onReviewPressed,
            );
          }),
      ],
    );
  }
}

class ChefItemsCard extends StatelessWidget {
  final String chefName;
  final List<OrderLineItem> chefItems;
  final String orderStatus;
  final Color accentColor;
  final NumberFormat currencyFormatter;
  final void Function(OrderLineItem item) onReviewPressed;

  const ChefItemsCard({
    super.key,
    required this.chefName,
    required this.chefItems,
    required this.orderStatus,
    required this.accentColor,
    required this.currencyFormatter,
    required this.onReviewPressed,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 20),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Colors.grey.shade200),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.02),
            blurRadius: 8,
            offset: const Offset(0, 2),
          )
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.storefront_outlined,
                  size: 20, color: kOrderDetailPrimaryRed),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  chefName.isNotEmpty ? chefName : 'Unknown Chef',
                  style: const TextStyle(
                    fontWeight: FontWeight.bold,
                    fontSize: 16,
                    color: kOrderDetailTextBrown,
                  ),
                ),
              ),
            ],
          ),
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 12),
            child: Divider(
              height: 1,
              thickness: 1,
              color: kOrderDetailDivider,
            ),
          ),
          ...chefItems.asMap().entries.map((itemEntry) {
            final index = itemEntry.key;
            final item = itemEntry.value;
            return Column(
              children: [
                OrderDetailItemRow(
                  item: item,
                  accentColor: accentColor,
                  orderStatus: orderStatus,
                  currencyFormatter: currencyFormatter,
                  onReviewPressed: () => onReviewPressed(item),
                ),
                if (index != chefItems.length - 1)
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 12),
                    child: Divider(height: 1, color: kOrderDetailDivider),
                  ),
              ],
            );
          }),
        ],
      ),
    );
  }
}

class OrderDetailItemRow extends StatelessWidget {
  final OrderLineItem item;
  final Color accentColor;
  final String orderStatus;
  final NumberFormat currencyFormatter;
  final VoidCallback onReviewPressed;

  const OrderDetailItemRow({
    super.key,
    required this.item,
    required this.accentColor,
    required this.orderStatus,
    required this.currencyFormatter,
    required this.onReviewPressed,
  });

  @override
  Widget build(BuildContext context) {
    final bool isReviewed = false;
    final bool isCompleted = orderStatus == 'COMPLETED';

    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(10),
          child: SizedBox(
            width: 60,
            height: 60,
            child: (item.imageUrl != null && item.imageUrl!.isNotEmpty)
                ? Image.network(
                    item.imageUrl!,
                    cacheWidth: 120,
                    fit: BoxFit.cover,
                  )
                : Image.asset(
                    'assets/images/placeholder_food.png',
                    fit: BoxFit.cover,
                  ),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                item.dishName,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontWeight: FontWeight.w700,
                  fontSize: 15,
                ),
              ),
              const SizedBox(height: 4),
              Text(
                '${currencyFormatter.format(item.price)}  x  ${item.quantity}',
                style: TextStyle(color: Colors.grey.shade600, fontSize: 13),
              ),
            ],
          ),
        ),
        Text(
          currencyFormatter.format(item.price * item.quantity),
          style: TextStyle(
            color: accentColor,
            fontWeight: FontWeight.bold,
            fontSize: 15,
          ),
        ),
        if (isCompleted && !isReviewed) ...[
          const SizedBox(width: 12),
          SizedBox(
            height: 30,
            child: OutlinedButton(
              style: OutlinedButton.styleFrom(
                foregroundColor: accentColor,
                side: BorderSide(color: accentColor),
                padding: const EdgeInsets.symmetric(horizontal: 16),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(15),
                ),
              ),
              onPressed: onReviewPressed,
              child: const Text(
                'Review',
                style: TextStyle(fontSize: 12, fontWeight: FontWeight.bold),
              ),
            ),
          ),
        ]
      ],
    );
  }
}
