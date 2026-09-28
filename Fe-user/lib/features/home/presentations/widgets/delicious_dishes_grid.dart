import 'package:flutter/material.dart';
import 'package:testing/features/common/dish_card.dart';
import 'package:testing/features/home/models/dish_model.dart';

class DeliciousDishesGrid extends StatelessWidget {
  final Future<List<DishModel>> futureDishes;

  const DeliciousDishesGrid({super.key, required this.futureDishes});

  @override
  Widget build(BuildContext context) {
    // Không cần ép SizedBox height nữa vì GridView dọc sẽ tự giãn chiều cao
    return FutureBuilder<List<DishModel>>(
      future: futureDishes,
      builder: (context, snapshot) {
        // 1. TRẠNG THÁI ĐANG TẢI (SHIMMER EFFECT DẠNG GRID)
        if (snapshot.connectionState == ConnectionState.waiting) {
          return SliverGrid(
            gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 2, // 2 thẻ 1 hàng
              childAspectRatio: 0.75, // Tỉ lệ thẻ (Cao hơn rộng một chút)
              crossAxisSpacing: 16,
              mainAxisSpacing: 16,
            ),
            delegate: SliverChildBuilderDelegate(
              (context, index) => Container(
                decoration: BoxDecoration(
                  color: Colors.white,
                  borderRadius: BorderRadius.circular(16),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Container(
                      height: 120,
                      decoration: BoxDecoration(
                        color: Colors.grey[300],
                        borderRadius: const BorderRadius.vertical(
                            top: Radius.circular(16)),
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsets.all(12),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Container(
                              height: 14,
                              width: double.infinity,
                              color: Colors.grey[300]),
                          const SizedBox(height: 8),
                          Container(
                              height: 12, width: 80, color: Colors.grey[300]),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
              childCount: 4, // Hiện 4 cái khung mờ mờ loading
            ),
          );
        }

        final dishes = snapshot.data ?? [];

        // 2. TRẠNG THÁI TRỐNG (EMPTY STATE)
        if (dishes.isEmpty) {
          return SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 32.0),
              child: Center(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(Icons.fastfood_outlined,
                        size: 48, color: Colors.grey[400]),
                    const SizedBox(height: 12),
                    Text(
                      "No dishes found",
                      style: TextStyle(color: Colors.grey[600]),
                    ),
                  ],
                ),
              ),
            ),
          );
        }

        // 3. TRẠNG THÁI HIỂN THỊ DỮ LIỆU (GRID 2 CỘT)
        return SliverList(
          delegate: SliverChildBuilderDelegate(
            (context, index) {
              // 👇 TECH LEAD FIX: Dùng thẻ DishCardList mới để hiển thị full chiều ngang
              return DishCard(dish: dishes[index]);
            },
            childCount: dishes.length,
          ),
        );
      },
    );
  }
}
