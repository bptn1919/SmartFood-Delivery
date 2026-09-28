import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:web_socket_channel/web_socket_channel.dart';
import 'package:http/http.dart' as http;

class ChatService {
  WebSocketChannel? _channel;

  static String get wsBaseUrl {
    if (kIsWeb) {
      // 🌐 Trình duyệt Web (Chrome/Edge)
      return 'ws://127.0.0.1:8000';
    } else {
      // 📱 Máy ảo Android
      return 'ws://10.0.2.2:8000';
      //return 'ws://app-alb-1522971553.us-east-1.elb.amazonaws.com:80';
    }
  }

  static String get baseUrl {
    if (kIsWeb) {
      // 🌐 Trình duyệt Web (Chrome/Edge)
      return 'http://127.0.0.1:8000';
    } else {
      // 📱 Máy ảo Android
      //return 'http://10.0.2.2:8000';
      return 'http://app-alb-production-1252640903.us-east-1.elb.amazonaws.com';
    }
  }

  // Hàm mở kết nối
  void connect({
    required String roomId,
    required String token,
    required Function(Map<String, dynamic>) onMessageReceived,
    void Function(Map<String, dynamic>)? onErrorEvent,
  }) {
    // 1. ĐỔI URL SANG LUỒNG MONGODB

    final wsUrl = Uri.parse('$wsBaseUrl/ws/mongo-chat/$roomId/?token=$token');

    try {
      _channel = WebSocketChannel.connect(wsUrl);
      debugPrint("✅ Đã mở luồng WebSocket tới phòng Mongo: $roomId");

      // Lắng nghe tin nhắn từ Backend trả về
      _channel!.stream.listen(
        (message) {
          final data = jsonDecode(message);
          if (data is! Map<String, dynamic>) return;

          if (data['type'] == 'error') {
            onErrorEvent?.call(data);
            return;
          }

          onMessageReceived(data); // Đẩy dữ liệu sang UI
        },
        onError: (error) => debugPrint("🚨 Lỗi WebSocket: $error"),
        onDone: () => debugPrint("❌ WebSocket is closed"),
      );
    } catch (e) {
      debugPrint("🚨 Lỗi kết nối: $e");
    }
  }

  // 2. NHẬN TRỰC TIẾP CHUỖI JSON TỪ UI VÀ BẮN ĐI
  void sendMessage(String payload) {
    if (_channel != null && payload.trim().isNotEmpty) {
      // Không bọc thêm jsonEncode nữa vì UI đã làm rồi
      _channel!.sink.add(payload);
    }
  }

  // Đóng kết nối khi thoát màn hình Chat
  void disconnect() {
    _channel?.sink.close();
  }

  Future<String?> getOrCreateRoom({
    required String token,
    required int
        partnerId, // Backend của bạn đang yêu cầu ID dạng số nguyên (int)
  }) async {
    try {
      final url = Uri.parse('$baseUrl/api/mongo-chat/room/get-or-create/');

      final response = await http.post(
        url,
        headers: {
          'Authorization': 'Bearer $token',
          'Content-Type': 'application/json',
        },
        body: jsonEncode({
          'partner_id': partnerId,
        }),
      );

      // Backend của bạn trả về 200 (Nếu đã có) hoặc 201 (Nếu tạo mới)
      if (response.statusCode == 200 || response.statusCode == 201) {
        // Parse tiếng Việt/Unicode chuẩn
        final data = jsonDecode(utf8.decode(response.bodyBytes));
        return data['room_id'];
      } else {
        debugPrint(
            "🚨 Error Get/Create Room: ${response.statusCode} - ${response.body}");
        return null;
      }
    } catch (e) {
      debugPrint("🚨 Exception when Get/Create Room: $e");
      return null;
    }
  }
}
