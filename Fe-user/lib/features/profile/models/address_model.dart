import 'dart:convert';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:testing/core/network/api_client.dart';
import 'package:testing/core/network/api_constants.dart';
import 'package:flutter/material.dart';

class NotificationRepository {
  final String baseUrl = '${ApiConstants.baseUrl}/api/mongo-chat'; // Sửa domain cho đúng môi trường
  final ApiClient _api;

  NotificationRepository({ApiClient? api}) : _api = api ?? ApiClient();

  Future<String?> _getToken() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString('token'); // Nhớ check lại key lưu token của bạn
  }

  Future<int> getUnreadCount() async {
    try {
      debugPrint("🚀 Calling API: /api/notifications/unread-count");
      
      // Gọi API GET. Nhớ đảm bảo ApiClient đã đính kèm Bearer Token
      final response = await _api.get('/api/notifications/unread-count');
      
      if (response != null) {
        final data = response['data'];
        if (data != null && data['unread_count'] != null) {
          return (data['unread_count'] as num).toInt();
        }
      }
      
      return 0; // Mặc định nếu API không trả về gì
      
    } catch (e) {
      debugPrint("❌ Error fetching unread count: $e");
      return 0; // Trả về 0 để lỡ có rớt mạng app cũng không bị crash
    }
  }

  Future<List<dynamic>> getNotifications() async {
    final token = await _getToken();
    if (token == null) {
      debugPrint("🚨 Thiếu Token, hủy gọi API");
      return [];
    }

    try {
      final response = await http.get(
        Uri.parse('$baseUrl/notifications/'),
        headers: {'Authorization': 'Bearer $token'},
      );

      // In ra Console để rọi đèn pin vào tận hang ổ dữ liệu
      debugPrint("📦 [API STATUS]: ${response.statusCode}");
      debugPrint("📦 [API BODY]: ${response.body}");

      if (response.statusCode == 200) {
        final rawData = jsonDecode(response.body);

        // Trường hợp 1: Data ngon nghẻ, chuẩn List
        if (rawData is List) {
          return rawData;
        } 
        // Trường hợp 2: Data bị gói trong Map
        else if (rawData is Map) {
          debugPrint("⚠️ CẢNH BÁO: Backend trả về Map, đang cố gỡ gói...");
          
          // Nhiều Backend hay có thói quen nhét List vào key 'data', 'items' hoặc 'results'
          if (rawData.containsKey('data') && rawData['data'] is List) {
            return rawData['data'];
          } else if (rawData.containsKey('items') && rawData['items'] is List) {
            return rawData['items'];
          } else if (rawData.containsKey('results') && rawData['results'] is List) {
            return rawData['results'];
          }
          
          debugPrint("❌ Chịu! Không tìm thấy List nào trong Map này.");
          return [];
        }
      } else {
        // Bắt gọn các lỗi 401 (Hết hạn token), 404 (Sai link)
        debugPrint("💥 [API ERROR]: Mã lỗi ${response.statusCode}");
      }
    } catch (e) {
      debugPrint("💥 [NETWORK/PARSE CRASH]: $e");
    }

    // Chốt chặn cuối cùng: Luôn trả về mảng rỗng để bảo vệ UI
    return [];
  }

  // Future<void> markAsRead(String id) async {
  //   final token = await _getToken();
  //   await http.put(
  //     Uri.parse('$baseUrl/notifications/$id/read/'),
  //     headers: {'Authorization': 'Bearer $token'},
  //   );
  // }

  // Future<void> deleteNotification(String id) async {
  //   final token = await _getToken();
  //   await http.delete(
  //     Uri.parse('$baseUrl/notifications/$id/'),
  //     headers: {'Authorization': 'Bearer $token'},
  //   );
  // }

  Future<void> markAsRead(String id) async {
    final token = await _getToken();
    if (token == null) {
      debugPrint("🚨 Thiếu Token, hủy thao tác Mark as Read");
      return;
    }

    try {
      final response = await http.put(
        Uri.parse('$baseUrl/notifications/$id/read/'),
        headers: {'Authorization': 'Bearer $token'},
      );

      if (response.statusCode == 200) {
        debugPrint("✅ Đã đánh dấu đọc thành công Noti: $id");
      } else {
        // Báo lỗi nếu ID sai, hoặc URL thay đổi
        debugPrint("💥 [LỖI MARK READ] Mã ${response.statusCode}: ${response.body}");
      }
    } catch (e) {
      debugPrint("💥 [LỖI NETWORK] Không thể gọi API Mark Read: $e");
    }
  }

  Future<void> deleteNotification(String id) async {
    final token = await _getToken();
    if (token == null) {
      debugPrint("🚨 Thiếu Token, hủy thao tác Delete");
      return;
    }

    try {
      final response = await http.delete(
        Uri.parse('$baseUrl/notifications/$id/'),
        headers: {'Authorization': 'Bearer $token'},
      );

      if (response.statusCode == 200) {
        debugPrint("🗑️ Đã xóa thành công Noti: $id trên Server");
      } else {
        debugPrint("💥 [LỖI DELETE] Mã ${response.statusCode}: ${response.body}");
      }
    } catch (e) {
      debugPrint("💥 [LỖI NETWORK] Không thể gọi API Delete: $e");
    }
  }
}