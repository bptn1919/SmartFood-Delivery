class NotiModel {
  final String id;
  final int? userId; // Thêm mới: ID của user sở hữu thông báo
  final String title;
  final String body;
  final String timeAgo;
  final String type;

  final String? roomId; // Thêm mới: Nullable (Chỉ có khi type == 'chat')
  final String? senderName; // Thêm mới: Nullable (Tên hiển thị người gửi)
  final String? partnerUserId;

  final String? orderId; // Thêm mới: Nullable (Chỉ có khi type == 'order')

  bool isRead;

  NotiModel({
    required this.id,
    this.userId,
    required this.title,
    required this.body,
    required this.timeAgo,
    required this.type,
    this.roomId,
    this.senderName,
    this.partnerUserId,
    this.orderId,
    this.isRead = false,
  });

  // Chuyển Data từ API thành Model + tính toán "Time Ago"
  factory NotiModel.fromJson(Map<String, dynamic> json) {
    String calculateTimeAgo(String? dateStr) {
      if (dateStr == null || dateStr.isEmpty) return '';
      try {
        DateTime date = DateTime.parse(dateStr).toLocal();
        Duration diff = DateTime.now().difference(date);

        if (diff.inDays > 0) return '${diff.inDays} days ago';
        if (diff.inHours > 0) return '${diff.inHours} hours ago';
        if (diff.inMinutes > 0) return '${diff.inMinutes} mins ago';
        return 'Just now';
      } catch (e) {
        // TECH LEAD FIX: Bọc try-catch chống crash nếu BE lỡ trả về format ngày tháng bị sai
        return '';
      }
    }

    return NotiModel(
      // Vẫn giữ cơ chế check cả 'id' và '_id' để tương thích tốt nhất
      id: json['id'] ?? json['_id'] ?? '',
      userId: json['user_id'],
      title: json['title'] ?? 'Notification',
      body: json['body'] ?? '',
      timeAgo: calculateTimeAgo(json['created_at']),
      type: json['type'] ?? 'default',
      roomId: json['room_id'],
      senderName: json['sender_name'],
      partnerUserId: json['partner_user_id']?.toString(),
      orderId: json['order_id'],
      isRead: json['is_read'] ?? false,
    );
  }
}
