import 'package:flutter/material.dart';

class ChefCertificationCard extends StatelessWidget {
  final bool isCertified;

  const ChefCertificationCard({super.key, required this.isCertified});

  @override
  Widget build(BuildContext context) {
    return Container(
      // Đảm bảo 3 thẻ có kích thước đồng đều
      width: 110, 
      padding: const EdgeInsets.symmetric(vertical: 16, horizontal: 8),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: Colors.grey.shade200),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // 1. Icon thay đổi theo trạng thái
          Icon(
            isCertified ? Icons.verified : Icons.gpp_bad_outlined, 
            color: isCertified ? const Color(0xFFE55866) : Colors.grey.shade400,
            size: 28,
          ),
          const SizedBox(height: 12),
          
          // 2. Tiêu đề (Certified / Unverified)
          Text(
            isCertified ? "Certified" : "Unverified", // Hoặc "Chưa xác thực"
            style: TextStyle(
              fontWeight: FontWeight.bold,
              fontSize: 14,
              color: isCertified ? Colors.black87 : Colors.grey.shade500,
            ),
          ),
          const SizedBox(height: 4),
          
          // 3. Sub-text cố định
          Text(
            "Food safety",
            style: TextStyle(
              fontSize: 11,
              color: Colors.grey.shade400,
            ),
          ),
        ],
      ),
    );
  }
}