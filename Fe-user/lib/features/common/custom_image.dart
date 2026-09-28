import 'package:flutter/material.dart';
import '../../core/utils/image_helper.dart'; // Import ImageHelper bạn đã tạo

class CustomNetworkImage extends StatelessWidget {
  final String? imageUrl;
  final double? width;
  final double? height;
  final BoxFit fit;
  final String fallbackAsset; // Ảnh hiển thị khi lỗi

  const CustomNetworkImage({
    super.key,
    required this.imageUrl,
    this.width,
    this.height,
    this.fit = BoxFit.cover,
    this.fallbackAsset = "assets/images/d4.jpg", // Ảnh mặc định an toàn
  });

  @override
  Widget build(BuildContext context) {
    // 1. Xử lý URL (thêm domain nếu thiếu)
    final validUrl = ImageHelper.getValidUrl(imageUrl);

    // 2. Nếu URL rỗng sau khi xử lý -> Hiện ảnh Asset ngay
    if (validUrl.isEmpty) {
      return Image.asset(
        fallbackAsset,
        width: width,
        height: height,
        fit: fit,
      );
    }

    // 3. Gọi Image.network với cơ chế bắt lỗi
    return Image.network(
      validUrl,
      width: width,
      height: height,
      cacheWidth: ((width ?? MediaQuery.sizeOf(context).width) * 2).round(),
      cacheHeight: ((height ?? MediaQuery.sizeOf(context).height) * 2).round(),
      fit: fit,
      // Xử lý khi đang tải
      loadingBuilder: (context, child, loadingProgress) {
        if (loadingProgress == null) return child;
        return Container(
          width: width,
          height: height,
          color: Colors.grey[200], // Placeholder màu xám
          child: const Center(child: Icon(Icons.image, color: Colors.grey)),
        );
      },
      // Xử lý khi lỗi (404, mất mạng...)
      errorBuilder: (context, error, stackTrace) {
        // Debug: in ra lỗi để biết ảnh nào hỏng
        // print("Image Load Error: $validUrl");
        return Image.asset(
          fallbackAsset,
          width: width,
          height: height,
          fit: fit,
        );
      },
    );
  }
}
