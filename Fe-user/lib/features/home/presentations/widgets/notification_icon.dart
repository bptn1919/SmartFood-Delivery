import 'package:flutter/material.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:testing/features/notifications/presentations/notification_page.dart';
import 'package:badges/badges.dart' as badges;
import 'package:testing/features/notifications/repositories/notification_repository.dart';

class NotificationIconButton extends StatefulWidget {
  const NotificationIconButton({super.key});

  @override
  State<NotificationIconButton> createState() => _NotificationIconButtonState();
}

class _NotificationIconButtonState extends State<NotificationIconButton> {
  int _unreadCount = 0;

  final NotificationRepository _notificationRepo = NotificationRepository();

  @override
  void initState() {
    super.initState();
    _fetchUnreadCount();
    _setupFirebaseListener();
  }

  void _setupFirebaseListener() {
    // Lắng nghe thông báo khi app đang bật (Foreground)
    FirebaseMessaging.onMessage.listen((RemoteMessage message) {
      _fetchUnreadCount();
    });
  }


  Future<void> _fetchUnreadCount() async {
    try {
      final count = await _notificationRepo.getUnreadCount(); 
      
      if (mounted) {
        setState(() => _unreadCount = count);
      }
    } catch (e) {
      debugPrint("🚨 Lỗi UI khi gọi đếm thông báo: $e");
    }
  }

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: () {
        // Reset số đếm khi bấm vào xem thông báo
        setState(() => _unreadCount = 0);
        
        Navigator.push(
          context,
          MaterialPageRoute(builder: (context) => const NotificationPage()),
        );
        _fetchUnreadCount();
      },
      child: Container(
        padding: const EdgeInsets.all(8),
        decoration: BoxDecoration(
          color: Colors.white.withOpacity(0.2), // Nền mờ đồng bộ với UI Header
          shape: BoxShape.circle,
        ),
        child: badges.Badge(
          position: badges.BadgePosition.topEnd(top: -12, end: -10),
          showBadge: _unreadCount > 0, // Chỉ hiện chấm đỏ khi có thông báo
          badgeAnimation: const badges.BadgeAnimation.fade(
            animationDuration: Duration(milliseconds: 300),
          ),
          badgeContent: Text(
            _unreadCount > 99 ? '99+' : _unreadCount.toString(),
            style: const TextStyle(
              color: Colors.white, 
              fontSize: 10, 
              fontWeight: FontWeight.bold
            ),
          ),
          badgeStyle: const badges.BadgeStyle(
            badgeColor: Colors.redAccent,
            padding: const EdgeInsets.all(5),
            elevation: 0,
          ),
          child: const Icon(
            Icons.notifications_none_rounded, 
            color: Colors.white, 
            size: 26,
          ),
        ),
      ),
    );
  }
}
