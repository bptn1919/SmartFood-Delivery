import 'package:flutter/foundation.dart';

// Biến toàn cục đơn giản để lưu số lượng
class CartGlobalState {
  static final ValueNotifier<int> countNotifier = ValueNotifier<int>(0);

  // Gọi hàm này bất cứ khi nào bạn thêm/xóa món thành công
  static void updateCount(int newCount) {
    countNotifier.value = newCount;
  }
}