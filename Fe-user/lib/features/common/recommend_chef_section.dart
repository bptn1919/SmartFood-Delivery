import 'package:flutter/material.dart';
import 'package:testing/features/common/recommended_chef_card.dart';
import 'package:testing/features/home/models/nearby_chef_model.dart';

import 'app_theme.dart';

class RecommendChefSection extends StatelessWidget {
  final Future<List<NearbyChefModel>> futureNearbyChefs;

  const RecommendChefSection({
    super.key,
    required this.futureNearbyChefs,
  });

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<List<NearbyChefModel>>(
      future: futureNearbyChefs,
      builder: (context, snapshot) {
        // 1. TRẠNG THÁI LOADING: Hiển thị Shimmer mờ mờ
        if (snapshot.connectionState == ConnectionState.waiting) {
          return _buildLoadingShimmer();
        }

        final chefs = snapshot.data ?? [];

        // 2. TRẠNG THÁI TRỐNG HOẶC LỖI: Ẩn luôn toàn bộ khu vực này cho sạch giao diện
        if (chefs.isEmpty || snapshot.hasError) {
          return const SizedBox.shrink();
        }

        // 3. TRẠNG THÁI CÓ DATA: Vẽ danh sách cuộn ngang
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Tiêu đề Section
            const Padding(
              padding: AppInsets.horizontalXxxl,
              child: Text(
                "Chefs Near You",
                style: AppTextStyles.sectionTitle,
              ),
            ),
            const SizedBox(height: 12),

            // Danh sách cuộn ngang
            SizedBox(
              height: 250, // Chiều cao cố định để chứa RecommendedChefCard
              child: ListView.builder(
                scrollDirection: Axis.horizontal,
                physics: const BouncingScrollPhysics(),
                padding: AppInsets.horizontalXxxl,
                itemCount: chefs.length,
                itemBuilder: (context, index) {
                  final chef = chefs[index];
                  return RecommendedChefCard(
                    chefName: chef.chefName,
                    avatarUrl: chef.avatar,
                    distanceKm: chef.distanceKm,
                    avgRating: chef.avgRating,
                    isCertified: chef.isFoodSafetyCertified,
                    onTap: () {
                      Navigator.pushNamed(
                        context,
                        '/detail-chef',
                        arguments: chef.chefId.toString(),
                      );
                      debugPrint("Clicked on nearby chef: ${chef.chefName}");
                    },
                  );
                },
              ),
            ),
          ],
        );
      },
    );
  }

  // Khung xương (Shimmer) khi đang chờ tải dữ liệu
  Widget _buildLoadingShimmer() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: AppInsets.horizontalXxxl,
          child: Container(
            height: 20,
            width: 150,
            decoration: BoxDecoration(
              color: Colors.grey[300],
              borderRadius: BorderRadius.circular(4),
            ),
          ),
        ),
        const SizedBox(height: 12),
        SizedBox(
          height: 220,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            physics: const NeverScrollableScrollPhysics(),
            padding: AppInsets.horizontalXxxl,
            itemCount: 3, // Vẽ 3 thẻ loading giả
            separatorBuilder: (_, __) => const SizedBox(width: 16),
            itemBuilder: (context, index) {
              return Container(
                width: 160,
                decoration: BoxDecoration(
                  color: AppColors.surface,
                  borderRadius: BorderRadius.circular(AppRadii.card),
                ),
                child: Column(
                  children: [
                    Container(
                      height: 140,
                      decoration: BoxDecoration(
                        color: Colors.grey[300],
                        borderRadius: const BorderRadius.vertical(
                          top: Radius.circular(AppRadii.card),
                        ),
                      ),
                    ),
                    Padding(
                      padding: AppInsets.allXl,
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Container(
                            height: 14,
                            width: 100,
                            color: Colors.grey[300],
                          ),
                          const SizedBox(height: 10),
                          Container(
                            height: 12,
                            width: 60,
                            color: Colors.grey[300],
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}
