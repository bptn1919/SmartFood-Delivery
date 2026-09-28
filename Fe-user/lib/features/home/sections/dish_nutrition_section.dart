import 'package:flutter/material.dart';
import 'package:testing/features/home/models/dish_ingredients_model.dart';

// ==========================================
// 3. WIDGET GIAO DIỆN (Đã tối ưu hiển thị ALL fields)
// ==========================================
class DishNutritionSection extends StatelessWidget {
  final NutritionTotal nutritionTotal;
  final List<IngredientNutrition> ingredients;

  const DishNutritionSection({super.key, required this.nutritionTotal, required this.ingredients});

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text("Nutrition Facts", style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: Colors.black87)),
        const SizedBox(height: 16),

        // --- 1. TOTAL: 4 Chỉ số Macro chính ---
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            _buildMacroCard(title: "Calories", value: nutritionTotal.energy.toStringAsFixed(0), unit: "kcal", icon: Icons.local_fire_department_rounded, color: Colors.orange),
            _buildMacroCard(title: "Protein", value: nutritionTotal.protein.toStringAsFixed(1), unit: "g", icon: Icons.fitness_center_rounded, color: Colors.blue),
            _buildMacroCard(title: "Carbs", value: nutritionTotal.carbohydrate.toStringAsFixed(1), unit: "g", icon: Icons.grain_rounded, color: Colors.green),
            _buildMacroCard(title: "Fat", value: nutritionTotal.lipid.toStringAsFixed(1), unit: "g", icon: Icons.water_drop_rounded, color: Colors.redAccent),
          ],
        ),
        const SizedBox(height: 12),

        // --- 2. TOTAL: 3 Chỉ số Micro phụ (Đưa ra ngoài cho minh bạch) ---
        Container(
          padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 16),
          decoration: BoxDecoration(color: Colors.grey.shade100, borderRadius: BorderRadius.circular(12)),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceAround,
            children: [
              _buildMicroStat("Fiber", "${nutritionTotal.fiber}g"),
              _buildMicroStat("Sodium (Na)", "${nutritionTotal.natri}mg"), // Dùng Sodium thay vì Natri cho chuẩn quốc tế
              _buildMicroStat("Cholesterol", "${nutritionTotal.cholesterol}mg"),
            ],
          ),
        ),
        const SizedBox(height: 20),

        // --- 3. INGREDIENTS: Danh sách chi tiết hiển thị đủ 8 trường ---
        if (ingredients.isNotEmpty)
          Container(
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: Colors.grey.shade200),
            ),
            child: Theme(
              data: Theme.of(context).copyWith(dividerColor: Colors.transparent),
              child: ExpansionTile(
                iconColor: const Color(0xFFE55866),
                collapsedIconColor: Colors.grey,
                title: const Text("Ingredient Breakdown", style: TextStyle(fontWeight: FontWeight.w700, fontSize: 15, color: Colors.black87)),
                subtitle: Text("Detail macros for ${ingredients.length} items", style: TextStyle(fontSize: 13, color: Colors.grey.shade500)),
                children: [
                  Divider(height: 1, color: Colors.grey.shade100, indent: 16, endIndent: 16),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                    child: Column(
                      children: ingredients.map((item) => _buildFullIngredientRow(item)).toList(),
                    ),
                  ),
                ],
              ),
            ),
          ),
      ],
    );
  }

  // Widget con: Thẻ Macro lớn
  Widget _buildMacroCard({required String title, required String value, required String unit, required IconData icon, required Color color}) {
    return Expanded(
      child: Container(
        margin: const EdgeInsets.symmetric(horizontal: 4),
        padding: const EdgeInsets.symmetric(vertical: 12),
        decoration: BoxDecoration(color: color.withOpacity(0.08), borderRadius: BorderRadius.circular(12), border: Border.all(color: color.withOpacity(0.15))),
        child: Column(
          children: [
            Icon(icon, color: color, size: 20),
            const SizedBox(height: 6),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Text(value, style: TextStyle(fontSize: 15, fontWeight: FontWeight.w900, color: color)),
                const SizedBox(width: 2),
                Text(unit, style: TextStyle(fontSize: 10, fontWeight: FontWeight.w600, color: color.withOpacity(0.7))),
              ],
            ),
            const SizedBox(height: 2),
            Text(title, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: Colors.grey.shade700)),
          ],
        ),
      ),
    );
  }

  // Widget con: Cụm Micro ngang
  Widget _buildMicroStat(String label, String value) {
    return Column(
      children: [
        Text(value, style: const TextStyle(fontWeight: FontWeight.bold, color: Colors.black87, fontSize: 13)),
        const SizedBox(height: 2),
        Text(label, style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
      ],
    );
  }

  // 👇 TECH LEAD FIX: Widget vẽ nguyên liệu full 8 trường
  Widget _buildFullIngredientRow(IngredientNutrition ingredient) {
    return Container(
      margin: const EdgeInsets.only(bottom: 16),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.grey.shade50,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Colors.grey.shade200)
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Dòng 1: Tên + Trọng lượng + Calo tổng
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Expanded(
                child: RichText(
                  text: TextSpan(
                    text: "${ingredient.name}  ",
                    style: const TextStyle(fontWeight: FontWeight.bold, color: Colors.black87, fontSize: 14),
                    children: [
                      TextSpan(text: "(${ingredient.weight}g)", style: TextStyle(color: Colors.grey.shade500, fontWeight: FontWeight.normal, fontSize: 13)),
                    ]
                  ),
                ),
              ),
              Text("${ingredient.energy.toStringAsFixed(0)} kcal", style: const TextStyle(fontWeight: FontWeight.w900, color: Color(0xFFE55866))),
            ],
          ),
          const SizedBox(height: 10),
          
          // Dòng 2: Hiển thị 6 chỉ số còn lại bằng Wrap Chips (Tự động rớt dòng nếu thiếu chỗ)
          Wrap(
            spacing: 8, // Khoảng cách ngang
            runSpacing: 8, // Khoảng cách dọc (nếu rớt dòng)
            children: [
              _buildInfoChip("Pro", "${ingredient.protein}g", Colors.blue),
              _buildInfoChip("Carb", "${ingredient.carbohydrate}g", Colors.green),
              _buildInfoChip("Fat", "${ingredient.lipid}g", Colors.redAccent),
              _buildInfoChip("Fib", "${ingredient.fiber}g", Colors.purple),
              _buildInfoChip("Na", "${ingredient.natri}mg", Colors.teal),
              _buildInfoChip("Chol", "${ingredient.cholesterol}mg", Colors.orange),
            ],
          )
        ],
      ),
    );
  }

  // Widget con: Chip thông số nhỏ xíu cho gọn
  Widget _buildInfoChip(String label, String value, Color color) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: color.withOpacity(0.3)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text("$label: ", style: TextStyle(fontSize: 11, color: Colors.grey.shade600, fontWeight: FontWeight.w500)),
          Text(value, style: TextStyle(fontSize: 11, color: color, fontWeight: FontWeight.w700)),
        ],
      ),
    );
  }
}