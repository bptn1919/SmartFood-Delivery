import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:testing/core/network/api_constants.dart';
import 'chat_detail_page.dart';

class ConversationListPage extends StatefulWidget {
  final String token;
  final int myUserId;

  const ConversationListPage({
    super.key,
    required this.token,
    required this.myUserId,
  });

  @override
  State<ConversationListPage> createState() => _ConversationListPageState();
}

class _ConversationListPageState extends State<ConversationListPage> {
  final Color _kHeaderColor = const Color(0xFFFFBB94);
  final Color _kTextBrownColor = const Color(0xFF4A2B29);
  final Color _kNotificationColor = const Color(0xFFFF5722);
  final Color _kOnlineColor = const Color(0xFF4CAF50);

  List<dynamic> _conversations = [];
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _fetchConversations();
  }

  Future<void> _fetchConversations() async {
    try {
      // 1. ĐỔI SANG API MONGO-CHAT
      final urlBase = ApiConstants.baseUrl;
      final url = Uri.parse('$urlBase/api/mongo-chat/conversations/');
      final response = await http.get(
        url,
        headers: {
          'Authorization': 'Bearer ${widget.token}',
          'Content-Type': 'application/json',
        },
      );

      if (response.statusCode == 200) {
        final data = jsonDecode(utf8.decode(response.bodyBytes));
        if (mounted) {
          setState(() {
            _conversations = data;
            _isLoading = false;
          });
        }
      } else {
        debugPrint("🚨 Lỗi lấy danh sách chat: ${response.statusCode}");
        if (mounted) setState(() => _isLoading = false);
      }
    } catch (e) {
      debugPrint("🚨 Exception: $e");
      if (mounted) setState(() => _isLoading = false);
    }
  }

  // Hàm format thời gian
  String _formatTime(String? isoString) {
    if (isoString == null || isoString.isEmpty) return "";
    try {
      final date = DateTime.parse(isoString).toLocal();
      final hour = date.hour.toString().padLeft(2, '0');
      final minute = date.minute.toString().padLeft(2, '0');
      return "$hour:$minute";
    } catch (e) {
      return "";
    }
  }

  // --- HÀM HELPER LẤY THÔNG TIN NGƯỜI NHẮN VỚI MÌNH ---
  // Vì trong Mongo ta lưu mảng participants (Customer & Chef),
  // ta cần tìm người có ID khác với ID của mình để hiển thị.
  String _getPartnerName(Map<String, dynamic> chatData) {
    // Nếu bạn có nhúng tên vào participants thì lọc ở đây.
    // Tạm thời nếu mảng participants chứa role, ta hardcode hoặc nếu backend có trả 'partner_name' thì dùng.
    // Đoạn này phụ thuộc vào việc lúc tạo Room, Backend bạn lưu JSON thế nào.
    // Nếu Backend chưa trả partner_name, bạn phải tự xử lý hoặc kêu Backend trả thêm field này lúc query.
    return chatData['partner_name'] ?? 'User';
  }

  int _getPartnerUserId(Map<String, dynamic> chatData) {
    final directId = chatData['partner_user_id'] ?? chatData['partner_id'];
    if (directId is num) return directId.toInt();
    
    return 0;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      body: Column(
        children: [
          // --- HEADER CONG MÀU CAM ---
          Container(
            padding:
                const EdgeInsets.only(top: 60, left: 20, right: 20, bottom: 30),
            decoration: BoxDecoration(
              color: _kHeaderColor,
              borderRadius: const BorderRadius.only(
                bottomLeft: Radius.circular(30),
                bottomRight: Radius.circular(30),
              ),
            ),
            child: Row(
              children: [
                IconButton(
                  onPressed: () => Navigator.pop(context),
                  icon: Icon(Icons.arrow_back_ios_new,
                      color: _kTextBrownColor, size: 20),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                        horizontal: 16, vertical: 12),
                    decoration: BoxDecoration(
                      color: Colors.white,
                      borderRadius: BorderRadius.circular(30),
                    ),
                    child: Row(
                      children: const [
                        Icon(Icons.search, color: Colors.grey, size: 20),
                        SizedBox(width: 10),
                        Text('Search Chef',
                            style: TextStyle(color: Colors.grey, fontSize: 14)),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),

          // --- PHẦN BODY MÀU TRẮNG BO GÓC TRÊN ---
          Expanded(
            child: Transform.translate(
              offset: const Offset(0, -20),
              child: Container(
                padding: const EdgeInsets.all(24),
                decoration: const BoxDecoration(
                  color: Colors.white,
                  borderRadius: BorderRadius.only(
                    topLeft: Radius.circular(30),
                    topRight: Radius.circular(30),
                  ),
                ),
                child: Column(
                  children: [
                    // Tiêu đề Message
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      crossAxisAlignment: CrossAxisAlignment.end,
                      children: [
                        Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              'Message',
                              style: TextStyle(
                                  fontSize: 28,
                                  fontWeight: FontWeight.bold,
                                  color: _kTextBrownColor),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              '${_conversations.length} Messages',
                              style: TextStyle(
                                  fontSize: 14,
                                  color: _kNotificationColor,
                                  fontWeight: FontWeight.bold),
                            ),
                          ],
                        ),
                      ],
                    ),
                    const SizedBox(height: 24),

                    // Danh sách Chat thực tế
                    Expanded(
                      child: _isLoading
                          ? const Center(
                              child: CircularProgressIndicator(
                                  color: Color(0xFFFF5722)))
                          : _conversations.isEmpty
                              ? const Center(
                                  child: Text("Bạn chưa có cuộc hội thoại nào",
                                      style: TextStyle(color: Colors.grey)))
                              : ListView.separated(
                                  padding: EdgeInsets.zero,
                                  itemCount: _conversations.length,
                                  separatorBuilder: (context, index) =>
                                      const SizedBox(height: 16),
                                  itemBuilder: (context, index) {
                                    final chat = _conversations[index];

                                    // 2. LẤY DATA TỪ CẤU TRÚC MONGO
                                    final String roomId =
                                        chat['_id']?.toString() ?? '';
                                    final String partnerName =
                                        _getPartnerName(chat);
                                    final int partnerUserId =
                                        _getPartnerUserId(chat);
                                    final Map<String, dynamic>? lastMsgMap =
                                        chat['last_message'];
                                    final String latestContent =
                                        lastMsgMap?['content'] ?? '';
                                    final String updatedAt =
                                        chat['updated_at'] ?? '';

                                    return GestureDetector(
                                      onTap: () {
                                        // Bấm vào thì đẩy qua màn hình Detail Chat
                                        Navigator.push(
                                          context,
                                          MaterialPageRoute(
                                              builder: (context) =>
                                                  ChatDetailPage(
                                                    roomId: roomId,
                                                    senderName: partnerName,
                                                    token: widget.token,
                                                    myUserId: widget.myUserId,
                                                    partnerUserId:
                                                        partnerUserId,
                                                  )),
                                        ).then((_) {
                                          // Load lại list khi back từ Detail về
                                          _fetchConversations();
                                        });
                                      },
                                      child: Container(
                                        padding: const EdgeInsets.all(16),
                                        decoration: BoxDecoration(
                                          color: const Color(0xFFF8F8F8),
                                          borderRadius:
                                              BorderRadius.circular(20),
                                        ),
                                        child: Row(
                                          children: [
                                            // Avatar + Nút xanh Online
                                            Stack(
                                              children: [
                                                CircleAvatar(
                                                  radius: 25,
                                                  backgroundColor:
                                                      Colors.grey[300],
                                                  child: const Icon(
                                                      Icons.person_outline,
                                                      color: Colors.grey,
                                                      size: 30),
                                                ),
                                                Positioned(
                                                  bottom: 0,
                                                  right: 0,
                                                  child: Container(
                                                    width: 14,
                                                    height: 14,
                                                    decoration: BoxDecoration(
                                                      color: _kOnlineColor,
                                                      shape: BoxShape.circle,
                                                      border: Border.all(
                                                          color: Colors.white,
                                                          width: 2),
                                                    ),
                                                  ),
                                                ),
                                              ],
                                            ),
                                            const SizedBox(width: 16),

                                            // Tên & Tin nhắn Preview
                                            Expanded(
                                              child: Column(
                                                crossAxisAlignment:
                                                    CrossAxisAlignment.start,
                                                children: [
                                                  Text(partnerName,
                                                      style: TextStyle(
                                                          fontSize: 16,
                                                          fontWeight:
                                                              FontWeight.bold,
                                                          color:
                                                              _kTextBrownColor)),
                                                  const SizedBox(height: 4),
                                                  // Hiện nội dung tin nhắn mới nhất
                                                  Text(latestContent,
                                                      style: TextStyle(
                                                          fontSize: 13,
                                                          color:
                                                              Colors.grey[700]),
                                                      maxLines: 1,
                                                      overflow: TextOverflow
                                                          .ellipsis),
                                                ],
                                              ),
                                            ),

                                            // Thời gian
                                            Column(
                                              crossAxisAlignment:
                                                  CrossAxisAlignment.end,
                                              children: [
                                                Text(_formatTime(updatedAt),
                                                    style: const TextStyle(
                                                        fontSize: 11,
                                                        color: Colors.grey)),
                                                const SizedBox(height: 24),
                                              ],
                                            ),
                                          ],
                                        ),
                                      ),
                                    );
                                  },
                                ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
