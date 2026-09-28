import 'dart:convert';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:http/http.dart' as http;
import 'package:testing/core/network/api_constants.dart';
import '../../../main.dart'; 
import 'package:flutter/foundation.dart';

class NotificationService {
  // 👇 TECH LEAD FIX: Biến class thành Singleton chuẩn để xài chung 1 instance toàn App
  static final NotificationService _instance = NotificationService._internal();
  factory NotificationService() => _instance;
  NotificationService._internal();

  final FirebaseMessaging _firebaseMessaging = FirebaseMessaging.instance;
  final FlutterLocalNotificationsPlugin _localNotifications = FlutterLocalNotificationsPlugin();
  
  final String apiBaseUrl = ApiConstants.baseUrl;
  String? _cachedBearerToken;

  // =========================================================================
  // LUỒNG 1: GỌI Ở MAIN.DART (Bật ăng-ten lắng nghe, KHÔNG CẦN TOKEN)
  // =========================================================================
  Future<void> initListeners() async {
    // 1. Setup Local Notifications (Popup hiển thị khi đang mở app)
    const AndroidInitializationSettings androidInit = AndroidInitializationSettings('@mipmap/ic_launcher');
    const InitializationSettings initSettings = InitializationSettings(android: androidInit);
    
    await _localNotifications.initialize(
      settings: initSettings, 
      onDidReceiveNotificationResponse: (NotificationResponse response) {
        if (response.payload != null) _navigateToChatRoom(response.payload!);
      },
    );

    const AndroidNotificationChannel channel = AndroidNotificationChannel(
      'chat_high_importance_channel', 
      'Chat Notifications', 
      description: 'This channel is used for real-time chat notifications.', 
      importance: Importance.max, 
    );

    await _localNotifications
        .resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>()
        ?.createNotificationChannel(channel);

    // 2. Lắng nghe tin nhắn (Foreground & Background)
    FirebaseMessaging.onMessage.listen(_showLocalNotification);
    FirebaseMessaging.onMessageOpenedApp.listen(_handleNotificationClick);
  }

  // =========================================================================
  // LUỒNG 2: GỌI SAU KHI LOGIN THÀNH CÔNG (Xin quyền & Cập nhật Server)
  // =========================================================================
  Future<void> registerDeviceAndToken(String bearerToken) async {
    _cachedBearerToken = bearerToken;
    String? fcmToken;

    debugPrint("🔔 User's notification permission status: Alibababababbaba");
    // 1. XIN QUYỀN (Nếu user chưa cấp, nó sẽ hiện Popup)
    if (kIsWeb) {
      // 🌐 TRÊN WEB: Bỏ qua TẤT CẢ các hàm của Firebase Messaging
      print("👉 Chạy trên Web: Bỏ qua xin quyền và lấy FCM Token.");
      // Thêm timestamp để tránh lỗi unique=True ở Backend nếu đăng nhập nhiều acc
      fcmToken = "web_dummy_token_${DateTime.now().millisecondsSinceEpoch}"; 
      
    } else {
      // 📱 TRÊN MOBILE: Chạy luồng thật
      print('📲 Requesting Notification Permission...');
      
      // 1. Xin quyền
      NotificationSettings settings = await _firebaseMessaging.requestPermission(
        alert: true, badge: true, sound: true,
      );


      debugPrint("🔔 User's notification permission status: ${settings.authorizationStatus}");

      if (settings.authorizationStatus == AuthorizationStatus.authorized) {
        print('✅ Notification permission granted.');

        // 2. LẤY TOKEN (Delay nhẹ 1 nhịp để OS xử lý xong permission nếu có)
        await Future.delayed(const Duration(milliseconds: 500));
        fcmToken = await _firebaseMessaging.getToken();
        
        if (fcmToken != null) {
          print('📲 FCM Token retrieved: $fcmToken');
          await _sendTokenToServer(fcmToken, bearerToken);
        }

        // 3. Lắng nghe Token Refresh
        _firebaseMessaging.onTokenRefresh.listen((newToken) {
          _sendTokenToServer(newToken, bearerToken);
        });
      } else {
        print('🚨 Notification permission denied by user.');
      }
    }

    if (fcmToken != null) {
      print('🚀 Sẵn sàng gửi Token lên BE: $fcmToken');
      await _sendTokenToServer(fcmToken, bearerToken);
    }
  }

  // --- CÁC HÀM NỘI BỘ (Giữ nguyên logic của bạn, chỉ dọn dẹp code) ---
  void _showLocalNotification(RemoteMessage message) {
    RemoteNotification? notification = message.notification;
    if (notification != null) {
      _localNotifications.show(
        id: notification.hashCode,
        title: notification.title,
        body: notification.body,
        notificationDetails: const NotificationDetails(
          android: AndroidNotificationDetails(
            'chat_high_importance_channel', 
            'Chat Notifications', 
            importance: Importance.max,
            priority: Priority.high,
            icon: '@mipmap/ic_launcher',
          ),
        ),
        payload: message.data['room_id']?.toString(), 
      );
    }
  }

  void _handleNotificationClick(RemoteMessage message) {
    final roomId = message.data['room_id'];
    if (roomId != null) _navigateToChatRoom(roomId.toString());
  }

  void _navigateToChatRoom(String roomId) {
    // Nếu app bị kill, context có thể chưa sẵn sàng, nên kiểm tra kỹ
    if (navigatorKey.currentState != null && _cachedBearerToken != null) {
      navigatorKey.currentState?.pushNamed(
        '/chat-detail',
        arguments: {'room_id': roomId, 'token': _cachedBearerToken},
      );
    }
  }

  Future<void> _sendTokenToServer(String fcmToken, String bearerToken) async {
    try {
      final response = await http.post(
        Uri.parse('$apiBaseUrl/api/mongo-chat/device/register/'),
        headers: {
          'Authorization': 'Bearer $bearerToken',
          'Content-Type': 'application/json',
        },
        body: jsonEncode({"fcm_token": fcmToken}),
      );
      if (response.statusCode == 200 || response.statusCode == 201) {
        print('✅ FCM Token successfully registered on Backend.');
      }
    } catch (e) {
      print('🚨 API Error during FCM Token registration: $e');
    }
  }
}