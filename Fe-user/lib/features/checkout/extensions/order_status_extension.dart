// extensions/order_status_extension.dart
import 'package:flutter/material.dart';
import '../models/customer_order.dart';

extension OrderStatusUI on OrderStatus {
  Color get color {
    switch (this) {
      case OrderStatus.PENDING:
      case OrderStatus.CONFIRMED:
      case OrderStatus.CONFIRMED_SYSTEM:
      case OrderStatus.CONFIRMED_SHOP:
      case OrderStatus.PROCESSING:
      case OrderStatus.DELIVERING:
        return Colors.orange;
      case OrderStatus.COMPLETED:
        return Colors.green;
      case OrderStatus.CANCELLED:
        return Colors.red;
      case OrderStatus.DRAFT:
      default:
        return Colors.grey;
    }
  }

  String get label {
    switch (this) {
      case OrderStatus.DRAFT:
        return 'Nháp';
      case OrderStatus.PENDING:
        return 'Chờ xác nhận';
      case OrderStatus.CONFIRMED:
        return 'Đã xác nhận';
      case OrderStatus.CONFIRMED_SYSTEM:
        return 'Hệ thống đã xác nhận';
      case OrderStatus.CONFIRMED_SHOP:
        return 'Chef đã xác nhận';
      case OrderStatus.PROCESSING:
        return 'Đang nấu';
      case OrderStatus.DELIVERING:
        return 'Đang giao';
      case OrderStatus.COMPLETED:
        return 'Hoàn thành';
      case OrderStatus.CANCELLED:
        return 'Đã hủy';
      default:
        return 'Không rõ';
    }
  }
}
