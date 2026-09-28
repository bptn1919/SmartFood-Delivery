import 'package:flutter/material.dart';

import 'app_theme.dart';

class RecommendedChefCard extends StatelessWidget {
  final String chefName;
  final String? avatarUrl;
  final double distanceKm;
  final double avgRating;
  final bool isCertified;
  final VoidCallback? onTap;

  const RecommendedChefCard({
    super.key,
    required this.chefName,
    this.avatarUrl,
    required this.distanceKm,
    required this.avgRating,
    required this.isCertified,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 160, // Kích thước cố định để cuộn ngang cho đẹp
        margin: AppInsets.chefCardMargin,
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(AppRadii.card),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.05),
              blurRadius: 10,
              offset: const Offset(0, 4),
            ),
          ],
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // --- PHẦN 1: ẢNH AVATAR & BADGE AN TOÀN ---
            Stack(
              children: [
                ClipRRect(
                  borderRadius: const BorderRadius.vertical(
                      top: Radius.circular(AppRadii.card)),
                  child: AspectRatio(
                    aspectRatio: 1, // Ảnh hình vuông
                    child: avatarUrl != null && avatarUrl!.isNotEmpty
                        ? Image.network(
                            avatarUrl!,
                            cacheWidth: 320,
                            fit: BoxFit.cover,
                            errorBuilder: (context, error, stackTrace) =>
                                _buildFallbackImage(),
                          )
                        : _buildFallbackImage(),
                  ),
                ),

                // 👇 Badge Chứng nhận an toàn thực phẩm (Đè lên ảnh)
                if (isCertified)
                  Positioned(
                    top: 8,
                    right: 8,
                    child: Container(
                      padding: const EdgeInsets.all(4),
                      decoration: const BoxDecoration(
                        color: AppColors.surface,
                        shape: BoxShape.circle,
                        boxShadow: [
                          BoxShadow(
                              color: Colors.black12,
                              blurRadius: 4,
                              offset: Offset(0, 2))
                        ],
                      ),
                      child: const Icon(
                        Icons.verified_rounded,
                        color: Colors.green,
                        size: 20,
                      ),
                    ),
                  ),
              ],
            ),

            // --- PHẦN 2: THÔNG TIN CHI TIẾT ---
            Padding(
              padding: AppInsets.allXl,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // Tên Chef
                  Text(
                    chefName,
                    style: AppTextStyles.chefName,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  const SizedBox(height: 6),

                  // Row chứa Rating & Khoảng cách
                  Row(
                    children: [
                      // RATING
                      const Icon(Icons.star_rounded,
                          color: Colors.amber, size: 16),
                      const SizedBox(width: 4),
                      Text(
                        avgRating > 0 ? avgRating.toStringAsFixed(1) : "New",
                        style: AppTextStyles.chefRating,
                      ),

                      const Spacer(),

                      // KHOẢNG CÁCH
                      const Icon(Icons.location_on_rounded,
                          color: AppColors.accentPink, size: 14),
                      const SizedBox(width: 2),
                      Text(
                        "${distanceKm.toStringAsFixed(1)} km",
                        style: TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.w500,
                          color: Colors.grey.shade600,
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  // Widget hiển thị khi Chef chưa có avatar hoặc load lỗi
  Widget _buildFallbackImage() {
    return Container(
      color: Colors.grey.shade100,
      child: Icon(Icons.person, color: Colors.grey.shade400, size: 40),
    );
  }
}
