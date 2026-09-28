import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../models/order_line.dart';
import 'checkout_styles.dart';

const String kDefaultCheckoutDeliveryType = 'SELF_PICKUP';

typedef VoucherControllerForChef = TextEditingController Function(int chefId);
typedef ChangeDeliveryType = Future<void> Function(int chefId, String type);
typedef ApplyShopVoucher = Future<void> Function(int chefId, String code);
typedef EditChefDeliveryTime = Future<void> Function(int chefId);

class ChefOrderGroupsSection extends StatelessWidget {
  final List<OrderDraftLine> lines;
  final Map<int, String> deliveryTypes;
  final Map<int, DateTime> selectedDeliveryDates;
  final Map<int, String> selectedDeliveryTimes;
  final String fallbackDeliveryDate;
  final String fallbackDeliveryTime;
  final bool isRecalculating;
  final Map<int, String> appliedShopVouchers;
  final int? validatingChefId;
  final VoucherControllerForChef voucherControllerForChef;
  final ChangeDeliveryType onChangeDeliveryType;
  final EditChefDeliveryTime onEditDeliveryTime;
  final void Function(int chefId) onShowVoucherList;
  final ApplyShopVoucher onApplyShopVoucher;
  final void Function(int chefId) onRemoveShopVoucher;

  const ChefOrderGroupsSection({
    super.key,
    required this.lines,
    required this.deliveryTypes,
    required this.selectedDeliveryDates,
    required this.selectedDeliveryTimes,
    required this.fallbackDeliveryDate,
    required this.fallbackDeliveryTime,
    required this.isRecalculating,
    required this.appliedShopVouchers,
    required this.validatingChefId,
    required this.voucherControllerForChef,
    required this.onChangeDeliveryType,
    required this.onEditDeliveryTime,
    required this.onShowVoucherList,
    required this.onApplyShopVoucher,
    required this.onRemoveShopVoucher,
  });

  @override
  Widget build(BuildContext context) {
    final groupedLines = <int, List<OrderDraftLine>>{};
    for (var line in lines) {
      groupedLines.putIfAbsent(line.chefId, () => []).add(line);
    }

    final sortedChefIds = groupedLines.keys.toList()..sort();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text("Order Summary",
                style: TextStyle(
                    color: kCheckoutDarkBrown,
                    fontWeight: FontWeight.bold,
                    fontSize: 16)),
          ],
        ),
        const CheckoutDivider(),
        Column(
          children: sortedChefIds.map((chefId) {
            return ChefOrderGroupCard(
              chefId: chefId,
              items: groupedLines[chefId]!,
              selectedDeliveryType:
                  deliveryTypes[chefId] ?? kDefaultCheckoutDeliveryType,
              selectedDeliveryDate: selectedDeliveryDates[chefId],
              selectedDeliveryTime: selectedDeliveryTimes[chefId],
              fallbackDeliveryDate: fallbackDeliveryDate,
              fallbackDeliveryTime: fallbackDeliveryTime,
              isRecalculating: isRecalculating,
              appliedCode: appliedShopVouchers[chefId] ?? "",
              isValidatingVoucher: validatingChefId == chefId,
              voucherController: voucherControllerForChef(chefId),
              onChangeDeliveryType: onChangeDeliveryType,
              onEditDeliveryTime: onEditDeliveryTime,
              onShowVoucherList: onShowVoucherList,
              onApplyShopVoucher: onApplyShopVoucher,
              onRemoveShopVoucher: onRemoveShopVoucher,
            );
          }).toList(),
        ),
      ],
    );
  }
}

class ChefOrderGroupCard extends StatelessWidget {
  final int chefId;
  final List<OrderDraftLine> items;
  final String selectedDeliveryType;
  final DateTime? selectedDeliveryDate;
  final String? selectedDeliveryTime;
  final String fallbackDeliveryDate;
  final String fallbackDeliveryTime;
  final bool isRecalculating;
  final String appliedCode;
  final bool isValidatingVoucher;
  final TextEditingController voucherController;
  final ChangeDeliveryType onChangeDeliveryType;
  final EditChefDeliveryTime onEditDeliveryTime;
  final void Function(int chefId) onShowVoucherList;
  final ApplyShopVoucher onApplyShopVoucher;
  final void Function(int chefId) onRemoveShopVoucher;

  const ChefOrderGroupCard({
    super.key,
    required this.chefId,
    required this.items,
    required this.selectedDeliveryType,
    required this.selectedDeliveryDate,
    required this.selectedDeliveryTime,
    required this.fallbackDeliveryDate,
    required this.fallbackDeliveryTime,
    required this.isRecalculating,
    required this.appliedCode,
    required this.isValidatingVoucher,
    required this.voucherController,
    required this.onChangeDeliveryType,
    required this.onEditDeliveryTime,
    required this.onShowVoucherList,
    required this.onApplyShopVoucher,
    required this.onRemoveShopVoucher,
  });

  @override
  Widget build(BuildContext context) {
    final currencyFormatter = NumberFormat.currency(
      locale: 'vi_VN',
      symbol: 'đ',
      decimalDigits: 0,
    );
    final chefName = items.first.chefName;
    final dateLabel = selectedDeliveryDate == null
        ? fallbackDeliveryDate
        : DateFormat('yyyy-MM-dd').format(selectedDeliveryDate!);
    final timeLabel = selectedDeliveryTime ?? fallbackDeliveryTime;

    return Padding(
      padding: const EdgeInsets.only(bottom: 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ...items.map((line) => CheckoutDishLine(
                line: line,
                currencyFormatter: currencyFormatter,
              )),
          const SizedBox(height: 16),
          if (chefName != null && chefName.isNotEmpty) ...[
            Text(
              chefName,
              style: const TextStyle(
                color: kCheckoutDarkBrown,
                fontWeight: FontWeight.bold,
                fontSize: 15,
              ),
            ),
            const SizedBox(height: 12),
          ],
          const Text("Delivery Method",
              style: TextStyle(
                  color: kCheckoutDarkBrown,
                  fontWeight: FontWeight.bold,
                  fontSize: 14)),
          const SizedBox(height: 4),
          RadioGroup<String>(
            groupValue: selectedDeliveryType,
            onChanged: (val) {
              if (!isRecalculating && val != null) {
                onChangeDeliveryType(chefId, val);
              }
            },
            child: Column(
              children: [
                RadioListTile<String>(
                  enabled: !isRecalculating,
                  contentPadding: EdgeInsets.zero,
                  dense: true,
                  activeColor: kCheckoutPrimaryPink,
                  title: const Text("Standard Delivery",
                      style: TextStyle(
                          fontSize: 14,
                          color: kCheckoutDarkBrown,
                          fontWeight: FontWeight.w600)),
                  subtitle: const Text("Delivered by partner drivers",
                      style: TextStyle(fontSize: 12, color: kCheckoutGreyText)),
                  value: 'THIRD_PARTY',
                ),
                RadioListTile<String>(
                  enabled: !isRecalculating,
                  contentPadding: EdgeInsets.zero,
                  dense: true,
                  activeColor: kCheckoutPrimaryPink,
                  title: const Text("Self Pick-up",
                      style: TextStyle(
                          fontSize: 14,
                          color: kCheckoutDarkBrown,
                          fontWeight: FontWeight.w600)),
                  subtitle: const Text(
                      "Pick up directly at the chef's location",
                      style: TextStyle(fontSize: 12, color: kCheckoutGreyText)),
                  value: 'SELF_PICKUP',
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          ChefDeliveryTimeRow(
            deliveryDate: dateLabel,
            deliveryTime: timeLabel,
            onEdit: () => onEditDeliveryTime(chefId),
          ),
          const SizedBox(height: 16),
          ShopVoucherInputRow(
            controller: voucherController,
            appliedCode: appliedCode,
            isLoading: isValidatingVoucher,
            onShowVoucherList: () => onShowVoucherList(chefId),
            onApply: () => onApplyShopVoucher(chefId, voucherController.text),
            onRemove: () => onRemoveShopVoucher(chefId),
          ),
          const SizedBox(height: 16),
          const CheckoutDashedLine(),
        ],
      ),
    );
  }
}

class ChefDeliveryTimeRow extends StatelessWidget {
  final String deliveryDate;
  final String deliveryTime;
  final VoidCallback onEdit;

  const ChefDeliveryTimeRow({
    super.key,
    required this.deliveryDate,
    required this.deliveryTime,
    required this.onEdit,
  });

  @override
  Widget build(BuildContext context) {
    final timeText = [
      if (deliveryDate.isNotEmpty) deliveryDate,
      if (deliveryTime.isNotEmpty) deliveryTime,
    ].join(' • ');

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: const Color(0xFFFFF9FA),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: kCheckoutLightPinkLine),
      ),
      child: Row(
        children: [
          const Icon(Icons.schedule_outlined,
              color: kCheckoutPrimaryPink, size: 20),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Delivery Time',
                  style: TextStyle(
                    color: kCheckoutDarkBrown,
                    fontWeight: FontWeight.bold,
                    fontSize: 13,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  timeText.isEmpty ? '--:--' : timeText,
                  style: const TextStyle(
                    color: kCheckoutGreyText,
                    fontSize: 12,
                  ),
                ),
              ],
            ),
          ),
          TextButton(
            onPressed: onEdit,
            style: TextButton.styleFrom(
              foregroundColor: kCheckoutPrimaryPink,
              padding: const EdgeInsets.symmetric(horizontal: 10),
              minimumSize: const Size(0, 32),
              tapTargetSize: MaterialTapTargetSize.shrinkWrap,
            ),
            child: const Text(
              'Edit Time',
              style: TextStyle(fontWeight: FontWeight.bold, fontSize: 12),
            ),
          ),
        ],
      ),
    );
  }
}

class CheckoutDishLine extends StatelessWidget {
  final OrderDraftLine line;
  final NumberFormat currencyFormatter;

  const CheckoutDishLine({
    super.key,
    required this.line,
    required this.currencyFormatter,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          ClipRRect(
              borderRadius: BorderRadius.circular(12),
              child: Image.network(line.imageUrl ?? "",
                  width: 60,
                  height: 60,
                  cacheWidth: 120,
                  cacheHeight: 120,
                  fit: BoxFit.cover,
                  errorBuilder: (_, __, ___) => Container(
                      color: kCheckoutLightPinkLine, width: 60, height: 60))),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(line.dishName,
                    style: const TextStyle(
                        color: kCheckoutDarkBrown,
                        fontSize: 15,
                        fontWeight: FontWeight.bold)),
                const SizedBox(height: 12),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(currencyFormatter.format(line.lineTotal),
                        style: const TextStyle(
                            color: kCheckoutPrimaryPink,
                            fontWeight: FontWeight.bold,
                            fontSize: 15)),
                    Text("x${line.quantity}",
                        style: const TextStyle(
                            color: kCheckoutDarkBrown,
                            fontWeight: FontWeight.bold,
                            fontSize: 14)),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class ShopVoucherInputRow extends StatelessWidget {
  final TextEditingController controller;
  final String appliedCode;
  final bool isLoading;
  final VoidCallback onShowVoucherList;
  final VoidCallback onApply;
  final VoidCallback onRemove;

  const ShopVoucherInputRow({
    super.key,
    required this.controller,
    required this.appliedCode,
    required this.isLoading,
    required this.onShowVoucherList,
    required this.onApply,
    required this.onRemove,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          child: SizedBox(
            height: 40,
            child: TextField(
              controller: controller,
              enabled: appliedCode.isEmpty,
              decoration: InputDecoration(
                hintText: "Shop voucher code",
                hintStyle:
                    const TextStyle(fontSize: 13, color: kCheckoutGreyText),
                filled: true,
                fillColor: Colors.white,
                contentPadding: const EdgeInsets.symmetric(horizontal: 16),
                border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(20),
                    borderSide:
                        const BorderSide(color: kCheckoutLightPinkLine)),
                enabledBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(20),
                    borderSide:
                        const BorderSide(color: kCheckoutLightPinkLine)),
                focusedBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(20),
                    borderSide: const BorderSide(color: kCheckoutPrimaryPink)),
                prefixIcon: const Icon(Icons.storefront_outlined,
                    color: kCheckoutPrimaryPink, size: 20),
                suffixIcon: appliedCode.isEmpty
                    ? IconButton(
                        icon: const Icon(Icons.list_alt,
                            color: kCheckoutPrimaryPink, size: 20),
                        onPressed: onShowVoucherList,
                      )
                    : const Icon(Icons.check_circle,
                        color: Colors.green, size: 20),
              ),
            ),
          ),
        ),
        const SizedBox(width: 8),
        SizedBox(
          height: 40,
          child: ElevatedButton(
            onPressed:
                isLoading ? null : (appliedCode.isEmpty ? onApply : onRemove),
            style: ElevatedButton.styleFrom(
              backgroundColor: appliedCode.isEmpty
                  ? kCheckoutPrimaryPink
                  : Colors.grey.shade300,
              foregroundColor:
                  appliedCode.isEmpty ? Colors.white : Colors.black87,
              elevation: 0,
              shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(20)),
            ),
            child: isLoading
                ? const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(
                        strokeWidth: 2, color: Colors.white))
                : Text(appliedCode.isEmpty ? "Apply" : "Remove",
                    style: const TextStyle(
                        fontSize: 13, fontWeight: FontWeight.bold)),
          ),
        ),
      ],
    );
  }
}
