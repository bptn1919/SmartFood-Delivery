import 'package:flutter/material.dart';
import 'package:testing/features/common/app_components.dart';
import 'package:testing/features/home/presentations/dish_detail_page.dart';
import 'package:testing/features/home/repositories/dish_repository.dart';
import 'package:testing/features/recommend/models/recommendation_feed_model.dart';

class RecommendedDishListCard extends StatelessWidget {
  final RecommendedDishModel dish;
  final DishRepository _dishRepository =
      DishRepository(); // Nếu cần gọi thêm API trong tương lai

  RecommendedDishListCard({
    super.key,
    required this.dish,
  });

  // 👇 TECH LEAD: Hàm xử lý điều hướng sang trang chi tiết
  // 👇 TECH LEAD FIX: Đổi thành Future<void> và thêm từ khóa 'async'
  Future<void> _navigateToDetail(BuildContext context) async {
    final String? uid = dish.dishUid;

    // Check null sớm để code ở dưới không phải bọc if/else dài dòng
    if (uid == null) {
      debugPrint("Lỗi: dishUid bị null");
      return;
    }

    debugPrint("Bắt đầu fetch data cho món: ${dish.dishName}");

    // (Tùy chọn UX) Ở đây bạn có thể show 1 cái Loading Dialog nếu API chạy lâu
    // showLoadingDialog(context);

    // 👇 TECH LEAD FIX: Thêm 'await' để đợi API lấy data xong mới chạy tiếp
    final dishModel = await _dishRepository.getDishDetail(uid);

    // (Tùy chọn UX) Ẩn Loading Dialog
    // hideLoadingDialog(context);

    // 👇 TECH LEAD FIX: BẮT BUỘC kiểm tra mounted sau lệnh await trước khi dùng 'context'
    if (!context.mounted) return;

    if (dishModel == null) {
      showAppSnackBar(
        context,
        'Failed to fetch dish details',
        type: SnackBarType.error,
      );
    } else {
      debugPrint("Fetched thành công: ${dishModel.name}");

      Navigator.push(
        context,
        MaterialPageRoute(
          builder: (context) => DishDetailPage(
              dishId: dishModel.uid), // Nhớ truyền đúng tên tham số của Page
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    // 1. Trích xuất dữ liệu thực tế từ Model
    final String imageUrl = dish.publicUrl ?? "https://via.placeholder.com/150";
    final bool hasAllergy = dish.allergyWarning ?? false;
    final bool isFav = dish.isFavorite ?? false;

    // Lấy lý do nổi bật nhất (phần tử đầu tiên trong mảng reasons)
    final String? topReason = (dish.reasons != null && dish.reasons!.isNotEmpty)
        ? dish.reasons!.first
        : null;

    return Container(
      margin: AppInsets.cardBottom,
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius:
            BorderRadius.circular(AppRadii.card), // Bo góc mềm mại hơn
        boxShadow: [
          BoxShadow(
            color: Colors.black.withOpacity(0.05),
            blurRadius: 10,
            offset: const Offset(0, 4),
          )
        ],
        // Nếu có cảnh báo dị ứng, viền thẻ màu đỏ nhạt để báo hiệu
        border: hasAllergy
            ? Border.all(color: Colors.red.withOpacity(0.3), width: 1)
            : null,
      ),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(AppRadii.card),
          onTap: () => _navigateToDetail(context),
          child: Opacity(
            // Làm mờ nhẹ nếu chứa thành phần dị ứng
            opacity: hasAllergy ? 0.7 : 1.0,
            child: Padding(
              padding: AppInsets.allXl,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // ==========================================
                  // PHẦN 1: THÔNG TIN CƠ BẢN (Nửa trên)
                  // ==========================================
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.center,
                    children: [
                      // 1. ẢNH MÓN ĂN
                      Stack(
                        children: [
                          ClipRRect(
                            borderRadius: BorderRadius.circular(10),
                            child: Image.network(
                              imageUrl,
                              width: 76,
                              height: 76,
                              cacheWidth: 152,
                              cacheHeight: 152,
                              fit: BoxFit.cover,
                              errorBuilder: (context, error, stackTrace) =>
                                  Container(
                                width: 76,
                                height: 76,
                                color: Colors.grey[100],
                                child: const Icon(Icons.fastfood_rounded,
                                    color: Colors.grey, size: 24),
                              ),
                            ),
                          ),
                          // Bắn tim nhỏ xíu góc ảnh nếu đang favorite
                          if (isFav)
                            Positioned(
                              top: 4,
                              right: 4,
                              child: Container(
                                padding: const EdgeInsets.all(4),
                                decoration: BoxDecoration(
                                  color: AppColors.surface.withOpacity(0.9),
                                  shape: BoxShape.circle,
                                ),
                                child: const Icon(Icons.favorite_rounded,
                                    color: AppColors.primaryRed, size: 12),
                              ),
                            )
                        ],
                      ),
                      const SizedBox(width: 12),

                      // 2. TÊN & RATING
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            Text(
                              dish.dishName ?? "Unknown Dish",
                              style: const TextStyle(
                                fontSize: 16,
                                fontWeight: FontWeight.bold,
                                color: Colors.black87,
                                height: 1.2,
                              ),
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                            ),
                            const SizedBox(height: 6),
                            Row(
                              children: [
                                const Icon(Icons.star_rounded,
                                    color: Colors.amber, size: 16),
                                const SizedBox(width: 4),
                                Text(
                                  "${dish.avgRating ?? 0.0}",
                                  style: const TextStyle(
                                    fontSize: 13,
                                    fontWeight: FontWeight.w600,
                                    color: Colors.black54,
                                  ),
                                ),
                                if (hasAllergy) ...[
                                  const SizedBox(width: 8),
                                  Container(
                                    padding: const EdgeInsets.symmetric(
                                        horizontal: 6, vertical: 2),
                                    decoration: BoxDecoration(
                                        color: Colors.red[50],
                                        borderRadius: BorderRadius.circular(4)),
                                    child: const Text("Allergens",
                                        style: TextStyle(
                                            fontSize: 10,
                                            color: Colors.red,
                                            fontWeight: FontWeight.bold)),
                                  )
                                ]
                              ],
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(width: 8),

                      // 3. GIÁ TIỀN & MŨI TÊN
                      Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        crossAxisAlignment: CrossAxisAlignment.end,
                        children: [
                          Text(
                            "${(dish.price ?? 0).toStringAsFixed(0)}đ",
                            style: AppTextStyles.recommendedPrice,
                          ),
                          const SizedBox(height: 8),
                          const Icon(Icons.arrow_forward_ios_rounded,
                              color: Colors.black26, size: 14),
                        ],
                      )
                    ],
                  ),

                  // ==========================================
                  // PHẦN 2: AI REASON (Nửa dưới)
                  // ==========================================
                  if (topReason != null) ...[
                    const SizedBox(height: 12),
                    Container(
                      width: double.infinity,
                      padding: const EdgeInsets.symmetric(
                          horizontal: 10, vertical: 8),
                      decoration: BoxDecoration(
                        // Tone màu gradient siêu nhẹ tạo cảm giác "AI thông minh"
                        gradient: LinearGradient(
                          colors: [
                            Colors.orange.withOpacity(0.05),
                            Colors.pinkAccent.withOpacity(0.05)
                          ],
                          begin: Alignment.topLeft,
                          end: Alignment.bottomRight,
                        ),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Icon(Icons.auto_awesome_rounded,
                              color: Colors.orangeAccent, size: 16),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(
                              topReason, // VD: "Phù hợp với mục tiêu giảm mỡ của bạn"
                              style: TextStyle(
                                fontSize: 12,
                                fontWeight: FontWeight.w500,
                                color: Colors
                                    .brown[700], // Chữ nâu trầm cho dễ đọc
                                fontStyle: FontStyle.italic,
                              ),
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ]
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
