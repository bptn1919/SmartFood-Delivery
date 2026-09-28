import 'package:flutter/material.dart';
import 'package:testing/features/checkout/presentations/cart.dart';
import '../home/repositories/cart_repository.dart';
import '../cart/cart_state.dart'; // Import file ở Bước 1

import 'app_theme.dart';

class CartIconButton extends StatefulWidget {
  final Color iconColor;

  const CartIconButton({
    super.key, 
    this.iconColor = Colors.black,
  });

  @override
  State<CartIconButton> createState() => _CartIconButtonState();
}

class _CartIconButtonState extends State<CartIconButton> {
  final CartRepository _cartRepo = CartRepository();

  @override
  void initState() {
    super.initState();
    // Lấy số lượng ngay khi widget được tạo
    _refreshCount();
  }

  // Hàm này cập nhật vào biến TOÀN CỤC (Global)
  Future<void> _refreshCount() async {
    try {
      final cart = await _cartRepo.getCart();
      // Cập nhật cho toàn bộ app biết
      CartGlobalState.updateCount(cart.items.length); 
    } catch (e) {
      debugPrint("Lỗi lấy giỏ hàng: $e");
    }
  }

  @override
  Widget build(BuildContext context) {
    // Lắng nghe biến toàn cục countNotifier
    return ValueListenableBuilder<int>(
      valueListenable: CartGlobalState.countNotifier,
      builder: (context, count, child) {
        return Stack(
          clipBehavior: Clip.none,
          children: [
            IconButton(
              icon: Icon(Icons.shopping_cart_outlined, color: widget.iconColor),
              onPressed: () async {
                // 1. Chuyển trang và CHỜ
                await Navigator.push(
                  context,
                  MaterialPageRoute(builder: (_) => const CartPage()),
                );
                
                // 2. Khi quay lại, refresh lại số lượng
                _refreshCount();
              },
            ),
            
            // Chỉ hiện badge khi số lượng > 0
            if (count > 0)
              Positioned(
                right: 6,
                top: 6,
                child: Container(
                  padding: const EdgeInsets.all(4),
                  decoration: const BoxDecoration(
                    color: Colors.red,
                    shape: BoxShape.circle,
                  ),
                  constraints: const BoxConstraints(
                    minWidth: 16,
                    minHeight: 16,
                  ),
                  child: Center(
                    child: Text(
                      '$count', // Hiển thị biến count từ ValueNotifier
                      style: const TextStyle(
                        color: AppColors.surface,
                        fontSize: 10,
                        fontWeight: FontWeight.bold,
                      ),
                      textAlign: TextAlign.center,
                    ),
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}
