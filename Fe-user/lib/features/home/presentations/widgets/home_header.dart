import 'package:flutter/material.dart';
import 'package:testing/features/common/cart_icon.dart';
import 'package:testing/features/home/presentations/widgets/notification_icon.dart';
import 'package:testing/features/profile/models/customer_profile_model.dart';

class HomeHeader extends StatelessWidget {
  final ValueChanged<String> onSearchChanged;
  final Future<List<AddressModel>> futureAddress;

  const HomeHeader({
    super.key,
    required this.onSearchChanged,
    required this.futureAddress,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(top: 12, left: 16, right: 16),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 1. Greeting Row
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    "Hello, there!",
                    style: TextStyle(
                      fontSize: 20,
                      fontWeight: FontWeight.bold,
                      color: Colors.white,
                      shadows: [
                        Shadow(
                          blurRadius: 10,
                          color: Colors.black.withValues(alpha: 0.2),
                          offset: const Offset(2, 2),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    "What would you like to eat today?",
                    style: TextStyle(
                      fontSize: 14,
                      color: Colors.white.withValues(alpha: 0.9),
                    ),
                  ),
                ],
              ),
              // Cart & Noti buttons
              Row(
                children: const [
                  NotificationIconButton(), // Icon Noti
                  SizedBox(width: 12), // Khoảng cách
                  CartIconButton(), // Icon Cart
                ],
              ),
            ],
          ),

          // 👇 TECH LEAD: Giảm khoảng cách cũ từ 20 xuống 12 để gom cụm Header lại
          const SizedBox(height: 8),

          // 👇 TECH LEAD THÊM: Delivery Address (Chuẩn UX nằm trên thanh Search)
          // 👇 TECH LEAD FIX: Đổi kiểu dữ liệu thành List<AddressModel>
          FutureBuilder<List<AddressModel>>(
            future: futureAddress,
            builder: (context, snapshot) {
              String displayAddress = "Đang lấy vị trí...";

              if (snapshot.connectionState == ConnectionState.done) {
                // Nếu có data và mảng không bị rỗng
                if (snapshot.hasData && snapshot.data!.isNotEmpty) {
                  final List<AddressModel> addresses = snapshot.data!;

                  // 👇 LOGIC TÌM ĐỊA CHỈ: Tìm cái có selected == true.
                  // Nếu không có cái nào selected, lấy mặc định cái đầu tiên trong list (orElse)
                  final AddressModel targetAddress = addresses.firstWhere(
                    (addr) => addr.selected == true,
                    orElse: () => addresses.first,
                  );

                  // Gán tên địa chỉ để hiển thị (Ưu tiên fullAddress)
                  displayAddress =
                      targetAddress.fullAddress ?? "Địa chỉ không xác định";
                } else {
                  // Nếu API trả về mảng rỗng []
                  displayAddress = "Vui lòng thêm địa chỉ giao hàng";
                }
              }

              return GestureDetector(
                onTap: () {
                  // TODO: Xử lý mở trang/popup chọn địa chỉ
                  debugPrint("Bấm chọn địa chỉ giao hàng");
                },
                child: Row(
                  children: [
                    const Icon(Icons.location_on,
                        color: Colors.white, size: 14),
                    const SizedBox(width: 4),
                    const Text(
                      "Delivery to: ",
                      style: TextStyle(color: Colors.white70, fontSize: 13),
                    ),
                    Expanded(
                      child: Text(
                        displayAddress,
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 13,
                          fontWeight: FontWeight.bold,
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    const Icon(Icons.keyboard_arrow_down,
                        color: Colors.white, size: 18),
                  ],
                ),
              );
            },
          ),

          // Khoảng cách trước khi vào ô Search
          const SizedBox(height: 12),

          // 3. Enhanced Search Bar
          Container(
            height: 44,
            decoration: BoxDecoration(
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.1),
                  blurRadius: 15,
                  offset: const Offset(0, 5),
                ),
              ],
            ),
            child: TextField(
              onChanged: onSearchChanged,
              style: const TextStyle(fontSize: 14),
              decoration: InputDecoration(
                hintText: "Search chefs, dishes...",
                hintStyle: TextStyle(color: Colors.grey[400]),
                filled: true,
                fillColor: Colors.white,
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(22),
                  borderSide: BorderSide.none,
                ),
                enabledBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(22),
                  borderSide: BorderSide.none,
                ),
                focusedBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(22),
                  borderSide: const BorderSide(color: Colors.white, width: 2),
                ),
                contentPadding:
                    const EdgeInsets.symmetric(horizontal: 20, vertical: 15),
                prefixIcon: Icon(Icons.search_rounded,
                    color: Colors.grey[600], size: 20),
              ),
            ),
          ),

          const SizedBox(height: 8),
          // 👇 Phần Comment "Enhanced Address Card" đã được xóa vì mình đã đưa nó lên trên đúng chuẩn.
        ],
      ),
    );
  }
}
