import '../network/api_constants.dart';

class ImageHelper {
  /// Hàm xử lý đường dẫn ảnh từ API
  static String getValidUrl(String? path) {
    if (path == null || path.isEmpty) {
      return ""; // Trả về rỗng để UI hiện ảnh fallback
    }
    
    // Nếu ảnh đã là link tuyệt đối (http...), giữ nguyên
    if (path.startsWith("http")) {
      return path;
    }
    
    String host = ApiConstants.baseUrl; 
    
    // Nếu ApiConstants.baseUrl có đuôi /api, ta cắt nó đi
    if (host.endsWith("/api") || host.endsWith("/api/")) {
      host = host.replaceAll("/api/", "").replaceAll("/api", "");
    }
    
    // Xử lý dấu gạch chéo để tránh double slash (//)
    if (host.endsWith("/") && path.startsWith("/")) {
      return "$host${path.substring(1)}";
    } else if (!host.endsWith("/") && !path.startsWith("/")) {
      return "$host/$path";
    }
    
    return "$host$path";
  }
}