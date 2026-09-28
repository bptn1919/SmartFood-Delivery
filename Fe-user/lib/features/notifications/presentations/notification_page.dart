import 'package:flutter/material.dart';
import 'package:testing/features/chat/presentations/chat_detail_page.dart';
import 'package:testing/features/checkout/presentations/order_detail.dart';
import 'package:testing/features/chef_manager/presentations/chef_order_detail_page.dart';
import 'package:testing/features/chef_manager/repositories/chef_management_repository.dart';
import 'package:testing/features/notifications/models/noti_model.dart';
import '../repositories/notification_repository.dart';
import 'package:shared_preferences/shared_preferences.dart';
// --- MOCK MODEL (Sau này bạn thay bằng Model lấy từ Backend) ---

class NotificationPage extends StatefulWidget {
  const NotificationPage({super.key});

  @override
  State<NotificationPage> createState() => _NotificationPageState();
}

class _NotificationPageState extends State<NotificationPage> {
  final NotificationRepository _repo = NotificationRepository();
  List<NotiModel> _notifications = [];
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _fetchData();
  }

  Future<void> _fetchData() async {
    final data = await _repo.getNotifications();
    if (mounted) {
      setState(() {
        _notifications = data.map((json) => NotiModel.fromJson(json)).toList();
        _isLoading = false;
      });
    }
  }

  void _markAllAsRead() async {
    setState(() {
      for (var noti in _notifications) {
        noti.isRead = true;
      }
    });
    await _repo.markAsRead("all");
  }

  void _deleteNotification(String id) {
    setState(() {
      _notifications.removeWhere((noti) => noti.id == id);
    });
    _repo.deleteNotification(id); // API call chạy ngầm
  }

  Future<String?> _getToken() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString('token'); // Nhớ check lại key lưu token của bạn
  }

  void _onNotificationTapped(NotiModel noti) async {
    if (!noti.isRead) {
      setState(() => noti.isRead = true);
      _repo.markAsRead(noti.id);
    }

    if (noti.type == 'chat') {
      final token = await _getToken();
      final myUserId = noti.userId;
      final roomId = noti.roomId;
      final senderName = noti.senderName;

      if (token != null &&
          roomId != null &&
          senderName != null &&
          myUserId != null) {
        if (!mounted) return;

        Navigator.push(
          context,
          MaterialPageRoute(
            builder: (_) => ChatDetailPage(
              senderName: senderName,
              roomId: roomId,
              token: token,
              myUserId: myUserId,
              partnerUserId: int.tryParse(noti.partnerUserId ?? '0') ?? 0,
            ),
          ),
        );
      } else {
        debugPrint(
            "🚨 Missing data for navigation! Token=$token, Room=$roomId, Sender=$senderName, UserId=$myUserId");
      }
    }
    // Sau này có type 'order' thì viết tiếp vào đây
    else if (noti.type == 'order') {
      final orderId = noti.orderId;
      debugPrint(
          "🔍 Navigating to Order Detail with OrderId=${noti.orderId}"); // Rọi đèn pin xem orderId có đúng không
      if (orderId != null) {
        ChefManagementRepository authRepo = ChefManagementRepository();
        final isChef = await authRepo.checkIsChef();
        if (!mounted) return;
        if (isChef) {
          Navigator.push(
            context,
            MaterialPageRoute(
              builder: (_) => ChefOrderDetailPage(
                orderUid: orderId,
              ),
            ),
          );
        } else {
          Navigator.push(
            context,
            MaterialPageRoute(
              builder: (_) => OrderDetailPage(
                orderUid: orderId,
              ),
            ),
          );
        }
      } else {
        debugPrint("🚨 Missing data for navigation! OrderId=$orderId");
      }
    }
  }

  // Tiện ích lấy Icon theo loại thông báo
  IconData _getIconForType(String type) {
    switch (type) {
      case 'chat':
        return Icons.chat_bubble_rounded;
      case 'order':
        return Icons.local_shipping_rounded;
      case 'promo':
        return Icons.local_offer_rounded;
      default:
        return Icons.notifications_rounded;
    }
  }

  // Tiện ích lấy Màu theo loại thông báo
  Color _getColorForType(String type) {
    switch (type) {
      case 'chat':
        return Colors.blue;
      case 'order':
        return Colors.green;
      case 'promo':
        return Colors.orange;
      default:
        return Colors.grey;
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFFF7F7F7),
      appBar: AppBar(
        backgroundColor: Colors.white,
        elevation:
            0, // Nhấn chìm elevation để tạo cảm giác phẳng, nối liền với background
        iconTheme: const IconThemeData(color: Colors.black87),
        title: const Text('Notifications',
            style:
                TextStyle(color: Colors.black87, fontWeight: FontWeight.bold)),
        actions: [
          if (_notifications.any((n) => !n.isRead))
            TextButton(
              onPressed: _markAllAsRead,
              child: const Text('Mark all read',
                  style: TextStyle(
                      color: Color(0xFFFFBB94), fontWeight: FontWeight.bold)),
            ),
        ],
      ),
      body: _isLoading
          ? const Center(
              child: CircularProgressIndicator(color: Color(0xFFFFBB94)))
          : _notifications.isEmpty
              ? _buildEmptyState()
              : ListView.separated(
                  // 👇 TECH LEAD FIX: Thêm padding bao quanh để list lùi vào giữa và cách xa Header
                  padding:
                      const EdgeInsets.symmetric(horizontal: 16, vertical: 20),
                  physics: const BouncingScrollPhysics(),
                  itemCount: _notifications.length,
                  // 👇 TECH LEAD FIX: Thay Divider bằng khoảng trống giữa các Card
                  separatorBuilder: (context, index) =>
                      const SizedBox(height: 12),
                  itemBuilder: (context, index) =>
                      _buildNotificationTile(_notifications[index]),
                ),
    );
  }

  Widget _buildEmptyState() {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Container(
            padding: const EdgeInsets.all(24),
            decoration: BoxDecoration(
                color: Colors.white,
                shape: BoxShape.circle,
                boxShadow: [
                  BoxShadow(
                      color: Colors.black.withValues(alpha: 0.05),
                      blurRadius: 20,
                      offset: const Offset(0, 10)),
                ]),
            child: Icon(Icons.notifications_off_outlined,
                size: 60, color: Colors.grey[300]),
          ),
          const SizedBox(height: 24),
          Text("No notifications yet",
              style: TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.bold,
                  color: Colors.grey[800])),
          const SizedBox(height: 8),
          Text("When you get messages or order updates,\nthey'll show up here.",
              textAlign: TextAlign.center,
              style: TextStyle(color: Colors.grey[500], height: 1.5)),
        ],
      ),
    );
  }

  Widget _buildNotificationTile(NotiModel noti) {
    final themeColor = _getColorForType(noti.type);

    return Dismissible(
      key: Key(noti.id),
      direction: DismissDirection.endToStart,
      onDismissed: (direction) => _deleteNotification(noti.id),
      background: Container(
        decoration: BoxDecoration(
          color: Colors.redAccent,
          borderRadius: BorderRadius.circular(
              16), // Bo góc background khi vuốt xóa khớp với Card
        ),
        alignment: Alignment.centerRight,
        padding: const EdgeInsets.only(right: 20),
        child: const Icon(Icons.delete_outline, color: Colors.white, size: 28),
      ),
      // 👇 TECH LEAD FIX: Bọc Card nổi thay vì Material phẳng
      child: Container(
        decoration: BoxDecoration(
          color: noti.isRead
              ? Colors.white
              : themeColor.withValues(
                  alpha: 0.02), // Nền hơi ám màu nếu chưa đọc
          borderRadius: BorderRadius.circular(16),
          border: noti.isRead
              ? Border.all(color: Colors.transparent)
              : Border.all(
                  color: themeColor.withValues(alpha: 0.3),
                  width: 1.2), // Viền nổi bật cho tin chưa đọc
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.04),
              blurRadius: 10,
              offset: const Offset(0, 4),
            ),
          ],
        ),
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            borderRadius: BorderRadius.circular(16),
            onTap: () => _onNotificationTapped(noti),
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // Icon đại diện
                  Container(
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                        color: noti.isRead
                            ? Colors.grey[100]
                            : themeColor.withValues(alpha: 0.15),
                        shape: BoxShape.circle),
                    child: Icon(_getIconForType(noti.type),
                        color: noti.isRead ? Colors.grey[400] : themeColor,
                        size: 22),
                  ),
                  const SizedBox(width: 16),

                  // Nội dung chính
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          (noti.type == 'chat' &&
                                  noti.senderName != null &&
                                  noti.senderName!.isNotEmpty)
                              ? noti.senderName!
                              : noti.title,
                          style: TextStyle(
                            fontSize: 15,
                            fontWeight:
                                noti.isRead ? FontWeight.w600 : FontWeight.bold,
                            color:
                                noti.isRead ? Colors.grey[800] : Colors.black87,
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        const SizedBox(height: 6),
                        Text(
                          noti.body,
                          style: TextStyle(
                            fontSize: 14,
                            color: noti.isRead
                                ? Colors.grey[500]
                                : Colors.grey[700],
                            height: 1.4,
                          ),
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                        const SizedBox(height: 8),
                        Text(
                          noti.timeAgo,
                          style: TextStyle(
                            fontSize: 12,
                            color: Colors.grey[400],
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                      ],
                    ),
                  ),

                  // Dấu chấm đỏ/xanh thông báo chưa đọc
                  if (!noti.isRead)
                    Container(
                        margin: const EdgeInsets.only(top: 4, left: 8),
                        width: 10,
                        height: 10,
                        decoration: BoxDecoration(
                            color: themeColor,
                            shape: BoxShape.circle,
                            boxShadow: [
                              BoxShadow(
                                  color: themeColor.withValues(alpha: 0.4),
                                  blurRadius: 4,
                                  offset: const Offset(0, 2))
                            ])),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
