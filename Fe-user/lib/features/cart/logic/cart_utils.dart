import 'package:flutter/material.dart';
import '../cart_state.dart';
import '../../home/repositories/cart_repository.dart';
import '../../home/repositories/dish_repository.dart';
import '../../home/models/dish_model.dart'; // ✅ Model Mới
import '../../home/models/dish_availability_model.dart'; // ✅ Model Mới
import '../../common/app_components.dart';

class CartUtils {
  static final DishRepository _dishRepo = DishRepository();
  static final CartRepository _cartRepo = CartRepository();

  static String _fmtDate(DateTime d) =>
      '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  // ✅ Tham số đầu vào sửa thành DishModel
  static Future<void> showAddToCartModal(
    BuildContext context, 
    DishModel dish,{
    int initialQuantity = 1, // 👇 TECH LEAD FIX: Thêm tham số hứng số lượng
  }) async {
    final dishUid = dish.uid;
    if (dishUid.isEmpty) {
      showAppSnackBar(context, 'This dish is missing an ID', type: SnackBarType.error,);
      return;
    }

    // 1. Lấy lịch bán (Trả về List<DishAvailabilityModel>)
    final List<DishAvailabilityModel> avails = await _dishRepo.getDishAvailabilities(dishUid);

    // 2. Map date -> data
    final Map<DateTime, DishAvailabilityModel> availMap = {
      for (final a in avails)
        DateTime(a.date.year, a.date.month, a.date.day): a,
    };

    // ... (Phần logic chọn ngày giữ nguyên như cũ) ...
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final last = DateTime(now.year, now.month + 6, now.day);

    final sortedAvailDates = availMap.keys.toList()..sort();
    final DateTime selectedInit = sortedAvailDates.firstWhere(
      (d) => !d.isBefore(today),
      orElse: () => today,
    );

    DateTime selected = selectedInit;
    int selectedQty = availMap[selected]?.quantity ?? 0; // ✅ Dùng .quantity của Model mới

    debugPrint("this is quantity: $selectedQty" );

    if (!context.mounted) return;

    int buyQty = initialQuantity;

    await showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (ctx) {
        return StatefulBuilder(
          builder: (ctx, setModalState) {
            return Padding(
              padding: EdgeInsets.only(
                left: 16, right: 16, top: 16,
                bottom: 16 + MediaQuery.of(ctx).viewInsets.bottom,
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  // ... (UI Modal giữ nguyên) ...
                  CalendarDatePicker(
                    initialDate: selected,
                    firstDate: today,
                    lastDate: last,
                    currentDate: today,
                    selectableDayPredicate: (d) => !d.isBefore(today),
                    onDateChanged: (d) {
                      final key = DateTime(d.year, d.month, d.day);
                      setModalState(() {
                        selected = key;
                        selectedQty = availMap[key]?.quantity ?? 0; // ✅
                      });
                    },
                  ),
                  // ... (Phần hiển thị số lượng & Nút bấm giữ nguyên) ...
                  SizedBox(
                    width: double.infinity,
                    child: ElevatedButton(
                      style: ElevatedButton.styleFrom(
                        backgroundColor: const Color(0xFFE84D67),
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
                      ),
                      onPressed: selectedQty <= 0
                          ? null
                          : () async {
                              final dateStr = _fmtDate(selected);
                              // ✅ Gọi Repo mới (đã refactor ở bước CartRepo)
                              final ok = await _cartRepo.addToCart(
                                dishUid: dishUid,
                                deliveryDate: dateStr,
                                quantity: buyQty,
                              );
                              
                              if (!ctx.mounted) return;
                              Navigator.pop(ctx); // Đóng modal

                              if (!context.mounted) return;
                              showAppSnackBar(
                                context,
                                ok ? "Added to cart" : "Failed to add",
                                type: SnackBarType.success,
                              );

                              if (ok) {
                                // Update count (nếu cần gọi lại API getCart)
                                final cart = await _cartRepo.getCart();
                                CartGlobalState.updateCount(cart.items.length);
                              }
                            },
                      child: const Text('Confirm', style: TextStyle(color: Colors.white)),
                    ),
                  ),
                  const SizedBox(height: 8),
                ],
              ),
            );
          },
        );
      },
    );
  }
}