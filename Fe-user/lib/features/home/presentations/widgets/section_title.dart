import 'package:flutter/material.dart';

class SectionTitle extends StatelessWidget {
  final String title;
  final VoidCallback? onSeeAllTap; // 👈 TECH LEAD: Thêm callback này

  const SectionTitle({
    super.key,
    required this.title,
    this.onSeeAllTap, // Nếu không truyền, nó sẽ chỉ hiện tiêu đề bình thường
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        // Tiêu đề chính
        Text(
          title,
          style: const TextStyle(
            fontSize: 18,
            fontWeight: FontWeight.bold,
            color: Color(0xFF4A3225), // Hoặc màu text brown bạn đang dùng
          ),
        ),
        
        // 👈 Nếu có truyền hàm onSeeAllTap thì mới render nút này
        if (onSeeAllTap != null)
          GestureDetector(
            onTap: onSeeAllTap,
            child: const Text(
              "See all",
              style: TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w600,
                color: Color(0xFFFFBB94), // Màu cam đồng bộ với app
              ),
            ),
          ),
      ],
    );
  }
}