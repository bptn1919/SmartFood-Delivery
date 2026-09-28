import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import '../../../home/models/dish_model.dart';
import '../../../common/custom_image.dart';

// Card hiển thị món ăn có nút Switch
class ChefDishCard extends StatelessWidget {
  final DishModel dish;
  final ValueChanged<bool> onToggle;

  const ChefDishCard({super.key, required this.dish, required this.onToggle});

  @override
  Widget build(BuildContext context) {
    bool isActive = dish.status == "AVAILABLE";

    final currencyFormatter = NumberFormat.currency(
      locale: 'vi_VN', 
      symbol: 'đ', 
      decimalDigits: 0,
    );

    return Container(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(color: Colors.black.withOpacity(0.05), blurRadius: 4, offset: const Offset(0, 4))
        ],
      ),
      child: Column(
        children: [
          // Ảnh
          Expanded(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(12),
                child: CustomNetworkImage(
                  imageUrl: dish.imageUrl,
                  fit: BoxFit.cover,
                  width: double.infinity,
                ),
              ),
            ),
          ),
          
          // Tên & Giá
          Text(dish.name, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.bold), maxLines: 1),
          const SizedBox(height: 4),
          Text(currencyFormatter.format(dish.price), style: const TextStyle(fontSize: 12, fontWeight: FontWeight.bold)),
          
          // Switch
          Align(
            alignment: Alignment.centerRight,
            child: Transform.scale(
              scale: 0.8,
              child: Switch(
                value: isActive,
                activeColor: Colors.white,
                activeTrackColor: const Color(0xFF29B6F6), // Màu xanh như design
                inactiveThumbColor: Colors.grey,
                inactiveTrackColor: Colors.grey[300],
                onChanged: onToggle,
              ),
            ),
          )
        ],
      ),
    );
  }
}

// Card nét đứt "Add New Dish"
class AddNewDishCard extends StatelessWidget {
  final VoidCallback onTap;

  const AddNewDishCard({super.key, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      child: Container(
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: const Color(0xFFE84D67), style: BorderStyle.none), // Fallback
        ),
        child: CustomPaint(
          painter: _DashedBorderPainter(color: const Color(0xFFE84D67)),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: const [
              Icon(Icons.add_circle_outline, size: 40, color: Color(0xFFE84D67)),
              SizedBox(height: 8),
              Text(
                "Add New Dish\nTo Meals", 
                textAlign: TextAlign.center,
                style: TextStyle(fontWeight: FontWeight.bold, fontSize: 12),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// Helper vẽ nét đứt
class _DashedBorderPainter extends CustomPainter {
  final Color color;
  _DashedBorderPainter({required this.color});
  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()..color = color..strokeWidth = 1.5..style = PaintingStyle.stroke;
    final path = Path()..addRRect(RRect.fromRectAndRadius(Rect.fromLTWH(0,0,size.width,size.height), const Radius.circular(16)));
    
    // Vẽ nét đứt đơn giản
    Path dashPath = Path();
    double dashWidth = 5.0;
    double dashSpace = 5.0;
    double distance = 0.0;
    for (PathMetric pathMetric in path.computeMetrics()) {
      while (distance < pathMetric.length) {
        dashPath.addPath(pathMetric.extractPath(distance, distance + dashWidth), Offset.zero);
        distance += dashWidth + dashSpace;
      }
    }
    canvas.drawPath(dashPath, paint);
  }
  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}