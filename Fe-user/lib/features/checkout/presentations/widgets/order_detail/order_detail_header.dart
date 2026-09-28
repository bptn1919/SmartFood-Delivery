import 'package:flutter/material.dart';

class OrderDetailHeader extends StatelessWidget {
  final String orderUid;
  final VoidCallback onBack;
  final VoidCallback? onReportPressed;

  const OrderDetailHeader({
    super.key,
    required this.orderUid,
    required this.onBack,
    this.onReportPressed,
  });

  String get _shortOrderId {
    return orderUid.length >= 6
        ? orderUid.substring(0, 6).toUpperCase()
        : orderUid.toUpperCase();
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 60, 20, 20),
      child: Row(
        children: [
          IconButton(
            icon: const Icon(Icons.chevron_left, color: Colors.black, size: 30),
            onPressed: onBack,
          ),
          Expanded(
            child: Text(
              'Order #$_shortOrderId',
              textAlign: TextAlign.center,
              style: const TextStyle(
                color: Colors.white,
                fontSize: 28,
                fontWeight: FontWeight.bold,
              ),
              overflow: TextOverflow.ellipsis,
            ),
          ),
          if (onReportPressed != null)
            PopupMenuButton<String>(
              icon: const Icon(Icons.more_vert, color: Colors.black),
              onSelected: (value) {
                if (value == 'report') {
                  onReportPressed?.call();
                }
              },
              itemBuilder: (context) => const [
                PopupMenuItem(
                  value: 'report',
                  child: Row(
                    children: [
                      Icon(Icons.flag_outlined, size: 18),
                      SizedBox(width: 10),
                      Text('Report order'),
                    ],
                  ),
                ),
              ],
            )
          else
            const SizedBox(width: 48),
        ],
      ),
    );
  }
}
