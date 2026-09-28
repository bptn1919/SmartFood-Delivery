import 'package:flutter/material.dart';
import 'package:testing/features/recommend/models/daily_meal_model.dart';

class DailyNutritionSection extends StatelessWidget {
  // 👇 TECH LEAD FIX: Nhận trực tiếp data đã resolve và trạng thái loading từ cha
  final DailyMealResponse? data;
  final bool isLoading;
  final Function(MealItem)? onMealTap;

  const DailyNutritionSection({
    super.key,
    required this.data,
    required this.isLoading,
    this.onMealTap,
  });

  final Color _primaryRed = const Color(0xFFE84D67);
  final Color _textDark = const Color(0xFF2D3142);

  @override
  Widget build(BuildContext context) {
    // 1. Xử lý trạng thái Loading
    if (isLoading) {
      return const Padding(
        padding: const EdgeInsets.symmetric(vertical: 40),
        child:
            Center(child: CircularProgressIndicator(color: Color(0xFFE84D67))),
      );
    }

    // 2. Xử lý trạng thái Trống/Lỗi
    if (data == null || data!.summary == null) {
      return Container(
        width: double.infinity,
        padding: const EdgeInsets.all(20),
        decoration: BoxDecoration(
            color: Colors.white, borderRadius: BorderRadius.circular(16)),
        child: const Center(
            child: Text("No nutrition data for today",
                style: TextStyle(color: Colors.grey))),
      );
    }

    final items = data!.items;

    // 3. Render Data
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 24),

        // MEALS LOG
        Text(
          "Today's Meals",
          style: TextStyle(
              fontSize: 18, fontWeight: FontWeight.bold, color: _textDark),
        ),
        const SizedBox(height: 12),

        if (items.isEmpty)
          Container(
            padding: const EdgeInsets.all(20),
            decoration: BoxDecoration(
                color: Colors.white, borderRadius: BorderRadius.circular(16)),
            child: const Center(
                child: Text("You haven't logged any meals today.",
                    style: TextStyle(color: Colors.grey))),
          )
        else
          ListView.separated(
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            itemCount: items.length,
            separatorBuilder: (_, __) => const SizedBox(height: 12),
            itemBuilder: (context, index) => _buildMealItem(items[index]),
          ),
      ],
    );
  }

  // --- Widget Item ---
  Widget _buildMealItem(MealItem item) {
    return GestureDetector(
        onTap: () {
          if (onMealTap != null) onMealTap!(item);
        },
        child: Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(16),
              boxShadow: [
                BoxShadow(
                    color: Colors.black.withOpacity(0.03),
                    blurRadius: 8,
                    offset: const Offset(0, 2))
              ]),
          child: Row(
            children: [
              ClipRRect(
                borderRadius: BorderRadius.circular(12),
                child: item.imageUrl != null && item.imageUrl!.isNotEmpty
                    ? Image.network(item.imageUrl!,
                        width: 60,
                        height: 60,
                        cacheWidth: 120,
                        //cacheHeight: 120,
                        fit: BoxFit.cover)
                    : Container(
                        width: 60,
                        height: 60,
                        color: Colors.grey.shade100,
                        child: const Icon(Icons.fastfood, color: Colors.grey)),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      item.dishName,
                      style: TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.bold,
                          color: _textDark),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    const SizedBox(height: 4),
                    Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 8, vertical: 2),
                      decoration: BoxDecoration(
                          color: Colors.grey.shade100,
                          borderRadius: BorderRadius.circular(6)),
                      child: Text(
                        item.mealTime,
                        style: TextStyle(
                            fontSize: 10,
                            fontWeight: FontWeight.w600,
                            color: Colors.grey.shade600),
                      ),
                    ),
                  ],
                ),
              ),
              Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Text(
                    "${item.calories}",
                    style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.bold,
                        color: _primaryRed),
                  ),
                  const Text("kcal",
                      style: TextStyle(fontSize: 12, color: Colors.grey)),
                ],
              )
            ],
          ),
        ));
  }
}
