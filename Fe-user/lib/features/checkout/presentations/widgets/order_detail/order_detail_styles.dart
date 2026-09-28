import 'package:flutter/material.dart';

const Color kOrderDetailPrimaryOrange = Color(0xFFFFB68C);
const Color kOrderDetailPrimaryRed = Color(0xFFE55866);
const Color kOrderDetailTextBrown = Color(0xFF4A3225);
const Color kOrderDetailLightPinkBg = Color(0xFFFFF9FA);
const Color kOrderDetailDivider = Color(0xFFF0F0F0);

class OrderDetailSectionHeader extends StatelessWidget {
  final String title;

  const OrderDetailSectionHeader({
    super.key,
    required this.title,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Text(
        title,
        style: const TextStyle(
          fontSize: 16,
          fontWeight: FontWeight.bold,
          color: kOrderDetailTextBrown,
        ),
      ),
    );
  }
}

class OrderDetailInfoTile extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;

  const OrderDetailInfoTile({
    super.key,
    required this.icon,
    required this.title,
    required this.subtitle,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.grey[50],
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Colors.grey.shade200),
      ),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              color: Colors.white,
              shape: BoxShape.circle,
              border: Border.all(color: Colors.grey.shade100),
            ),
            child: Icon(icon, color: Colors.grey.shade700, size: 20),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: const TextStyle(
                    fontWeight: FontWeight.w600,
                    fontSize: 14,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  subtitle,
                  style: TextStyle(color: Colors.grey.shade600, fontSize: 12),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
