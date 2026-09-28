import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:testing/core/network/api_constants.dart';
import 'package:testing/features/chat/repositories/chat_block_repository.dart';
import '../service/chat_service.dart';
import '../service/chat_upload_service.dart';
import 'package:image_picker/image_picker.dart';
import '../../common/app_components.dart';
import 'dart:io';

class ChatDetailPage extends StatefulWidget {
  final String senderName;
  final String roomId;
  final String token;
  final int myUserId;
  final int partnerUserId;

  const ChatDetailPage({
    super.key,
    required this.senderName,
    required this.roomId,
    required this.token,
    required this.myUserId,
    required this.partnerUserId,
  });

  @override
  State<ChatDetailPage> createState() => _ChatDetailPageState();
}

class _ChatDetailPageState extends State<ChatDetailPage> {
  final TextEditingController _messageCtrl = TextEditingController();
  final ChatService _chatService = ChatService();
  final ChatBlockRepository _blockRepository = ChatBlockRepository();
  final ChatUploadService _uploadService =
      ChatUploadService(); // Khởi tạo Upload Service

  final List<Map<String, dynamic>> _messages = [];

  bool _isLoadingHistory = true;
  bool _isUploadingImage = false; // Trạng thái loading khi đang đẩy file lên S3
  bool _isCheckingBlockState = true;
  bool _hasBlockedPartner = false;
  bool _isBlockedByPartner = false;
  bool _isMutatingBlockState = false;

  // --- BẢNG MÀU ---
  final Color _kTextBrownColor = const Color(0xFF4A2B29);
  final Color _kBubbleSender = const Color(0xFFFDE49E);
  final Color _kBubbleReceiver = const Color(0xFFEBEBEB);

  @override
  void initState() {
    super.initState();
    _bootstrapChat();
  }

  bool get _isChatBlocked => _hasBlockedPartner || _isBlockedByPartner;

  String get _blockedHintText {
    if (_hasBlockedPartner) return 'You blocked this user';
    if (_isBlockedByPartner) return 'Chat is blocked';
    return 'Chat is blocked';
  }

  Future<void> _bootstrapChat() async {
    await _loadBlockState();
    await _loadHistoryAndConnect();
  }

  Future<void> _loadBlockState() async {
    if (widget.partnerUserId <= 0) {
      if (mounted) setState(() => _isCheckingBlockState = false);
      return;
    }

    try {
      final blockedUserIds = await _blockRepository.getBlockedUserIds();
      if (!mounted) return;
      setState(() {
        _hasBlockedPartner = blockedUserIds.contains(widget.partnerUserId);
        _isCheckingBlockState = false;
      });
    } catch (e) {
      debugPrint('🚨 Failed to load blocked users: $e');
      if (mounted) setState(() => _isCheckingBlockState = false);
    }
  }

  // Lấy lịch sử từ MONGODB
  Future<void> _loadHistoryAndConnect() async {
    try {
      // 1. ĐỔI SANG API MONGO-CHAT
      final urlBase = ApiConstants.baseUrl;
      final url =
          Uri.parse('$urlBase/api/mongo-chat/${widget.roomId}/messages/');

      final response = await http.get(
        url,
        headers: {
          'Authorization': 'Bearer ${widget.token}',
          'Content-Type': 'application/json',
        },
      );

      if (response.statusCode == 200) {
        final List<dynamic> historyData =
            jsonDecode(utf8.decode(response.bodyBytes));

        if (mounted) {
          setState(() {
            _messages.addAll(
                historyData.map((e) => e as Map<String, dynamic>).toList());
            _isLoadingHistory = false;
          });
        }
      } else {
        debugPrint("🚨 Lỗi tải lịch sử chat: ${response.statusCode}");
        if (mounted) setState(() => _isLoadingHistory = false);
      }
    } catch (e) {
      debugPrint("🚨 Lỗi Exception API: $e");
      if (mounted) setState(() => _isLoadingHistory = false);
    } finally {
      // Mở ống WebSocket (Nhớ cập nhật URL trong ChatService sang ws://.../mongo-chat/...)
      _chatService.connect(
        roomId: widget.roomId,
        token: widget.token,
        onMessageReceived: _handleNewMessage,
        onErrorEvent: _handleWebSocketError,
      );
    }
  }

  void _handleNewMessage(Map<String, dynamic> data) {
    if (!mounted) return;
    setState(() {
      _messages.insert(0, data);
    });
  }

  void _handleWebSocketError(Map<String, dynamic> data) {
    if (!mounted) return;

    final code = data['code']?.toString();
    final message =
        data['message']?.toString() ?? 'Cannot send message. Chat is blocked.';

    if (code == 'CHAT_BLOCKED') {
      setState(() => _isBlockedByPartner = true);
      showAppSnackBar(context, message, type: SnackBarType.warning);
      return;
    }

    showAppSnackBar(context, message, type: SnackBarType.error);
  }

  void _showBlockedSnackBar() {
    showAppSnackBar(
      context,
      _blockedHintText,
      type: SnackBarType.warning,
    );
  }

  Future<void> _confirmBlockUser() async {
    final shouldBlock = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Block user?'),
        content: Text(
          'You will no longer be able to send messages to ${widget.senderName}.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('Block'),
          ),
        ],
      ),
    );

    if (shouldBlock == true) {
      await _blockUser();
    }
  }

  Future<void> _blockUser() async {
    if (widget.partnerUserId <= 0) {
      showAppSnackBar(
        context,
        'Cannot block this user right now.',
        type: SnackBarType.error,
      );
      return;
    }

    setState(() => _isMutatingBlockState = true);
    try {
      await _blockRepository.blockUser(widget.partnerUserId);
      if (!mounted) return;
      setState(() {
        _hasBlockedPartner = true;
        _isMutatingBlockState = false;
      });
      showAppSnackBar(
        context,
        'User blocked.',
        type: SnackBarType.success,
      );
    } catch (e) {
      if (!mounted) return;
      setState(() => _isMutatingBlockState = false);
      showAppSnackBar(
        context,
        'Failed to block user: $e',
        type: SnackBarType.error,
      );
    }
  }

  Future<void> _unblockUser() async {
    if (widget.partnerUserId <= 0) {
      showAppSnackBar(
        context,
        'Cannot unblock this user right now.',
        type: SnackBarType.error,
      );
      return;
    }

    setState(() => _isMutatingBlockState = true);
    try {
      await _blockRepository.unblockUser(widget.partnerUserId);
      if (!mounted) return;
      setState(() {
        _hasBlockedPartner = false;
        _isMutatingBlockState = false;
      });
      showAppSnackBar(
        context,
        'User unblocked.',
        type: SnackBarType.success,
      );
    } catch (e) {
      if (!mounted) return;
      setState(() => _isMutatingBlockState = false);
      showAppSnackBar(
        context,
        'Failed to unblock user: $e',
        type: SnackBarType.error,
      );
    }
  }

  // Gửi tin nhắn Text
  void _sendMessage() {
    if (_isChatBlocked) {
      _showBlockedSnackBar();
      return;
    }

    final text = _messageCtrl.text;
    if (text.trim().isEmpty) return;

    // 2. CHUẨN HÓA PAYLOAD THEO MONGO SCHEMA
    final payload = jsonEncode({
      "type": "text",
      "content": text,
    });

    // Lưu ý: Đảm bảo _chatService.sendMessage() của bạn chấp nhận chuỗi JSON này
    // và bắn thẳng qua sink.add() mà không bọc thêm {} nữa.
    _chatService.sendMessage(payload);
    _messageCtrl.clear();
  }

  // Xử lý Chọn & Upload Ảnh
  void onPickAndSendImage() async {
    if (_isChatBlocked) {
      _showBlockedSnackBar();
      return;
    }

    final ImagePicker picker = ImagePicker();
    final XFile? image = await picker.pickImage(source: ImageSource.gallery);

    if (image != null) {
      if (!mounted) return;
      // Bật hiệu ứng loading UI
      setState(() => _isUploadingImage = true);

      String? finalS3Url = await _uploadService.uploadAndGetChatImageUrl(
          File(image.path), widget.token);
      if (!mounted) return;

      if (finalS3Url != null) {
        // Bắn payload ảnh qua WebSocket
        final messagePayload = jsonEncode(
            {"type": "image", "content": "", "file_url": finalS3Url});

        _chatService.sendMessage(messagePayload);
      } else {
        if (mounted) {
          showAppSnackBar(
            context,
            'Failed to upload image. Please try again!',
            type: SnackBarType.error, // Gọi màu đỏ để báo lỗi
          );
        }
      }

      // Tắt loading
      if (mounted) setState(() => _isUploadingImage = false);
    }
  }

  @override
  void dispose() {
    _chatService.disconnect();
    _messageCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        backgroundColor: Colors.white,
        elevation: 0,
        centerTitle: true,
        leading: IconButton(
          onPressed: () => Navigator.pop(context),
          icon:
              Icon(Icons.arrow_back_ios_new, color: _kTextBrownColor, size: 20),
        ),
        title: Text(widget.senderName,
            style: TextStyle(
                color: _kTextBrownColor, fontWeight: FontWeight.bold)),
        actions: [
          PopupMenuButton<String>(
            enabled: !_isCheckingBlockState &&
                !_isMutatingBlockState &&
                widget.partnerUserId > 0,
            icon: _isMutatingBlockState
                ? const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : Icon(Icons.more_vert, color: _kTextBrownColor),
            onSelected: (value) {
              if (value == 'block') {
                _confirmBlockUser();
              } else if (value == 'unblock') {
                _unblockUser();
              }
            },
            itemBuilder: (context) => [
              PopupMenuItem(
                value: _hasBlockedPartner ? 'unblock' : 'block',
                child: Row(
                  children: [
                    Icon(
                      _hasBlockedPartner
                          ? Icons.lock_open_outlined
                          : Icons.block,
                      size: 18,
                    ),
                    const SizedBox(width: 10),
                    Text(_hasBlockedPartner ? 'Unblock User' : 'Block User'),
                  ],
                ),
              ),
            ],
          ),
        ],
      ),
      body: Column(
        children: [
          // Khu vực hiện tin nhắn
          Expanded(
            child: _isLoadingHistory
                ? const Center(
                    child: CircularProgressIndicator(color: Color(0xFFFF5722)))
                : ListView.builder(
                    reverse: true,
                    padding: const EdgeInsets.symmetric(
                        horizontal: 20, vertical: 10),
                    itemCount: _messages.length,
                    itemBuilder: (context, index) {
                      final msg = _messages[index];

                      // 3. ĐỌC ID TỪ CẤU TRÚC DENORMALIZE
                      final senderInfo = msg['sender'] as Map<String, dynamic>?;
                      final isMe = senderInfo?['id'] == widget.myUserId;

                      final msgType = msg['type'] ?? 'text';
                      final msgContent = msg['content'] ?? '';
                      final fileUrl = msg['file_url'];

                      return Padding(
                        padding: const EdgeInsets.only(bottom: 16),
                        child: Align(
                          alignment: isMe
                              ? Alignment.centerRight
                              : Alignment.centerLeft,
                          child: Container(
                            constraints: BoxConstraints(
                                maxWidth:
                                    MediaQuery.of(context).size.width * 0.75),
                            padding: EdgeInsets.all(msgType == 'image'
                                ? 4
                                : 12), // Bo sát nếu là ảnh
                            decoration: BoxDecoration(
                              color: msgType == 'image'
                                  ? Colors.transparent
                                  : (isMe ? _kBubbleSender : _kBubbleReceiver),
                              borderRadius: BorderRadius.only(
                                topLeft: const Radius.circular(20),
                                topRight: const Radius.circular(20),
                                bottomLeft: isMe
                                    ? const Radius.circular(20)
                                    : const Radius.circular(4),
                                bottomRight: isMe
                                    ? const Radius.circular(4)
                                    : const Radius.circular(20),
                              ),
                            ),
                            // 4. RENDER UI THEO LOẠI TIN NHẮN
                            child: msgType == 'image' && fileUrl != null
                                ? ClipRRect(
                                    borderRadius: BorderRadius.circular(16),
                                    child: Image.network(
                                      fileUrl,
                                      cacheWidth:
                                          (MediaQuery.sizeOf(context).width *
                                                  1.5)
                                              .round(),
                                      cacheHeight:
                                          (MediaQuery.sizeOf(context).width *
                                                  1.5)
                                              .round(),
                                      fit: BoxFit.cover,
                                      loadingBuilder:
                                          (context, child, loadingProgress) {
                                        if (loadingProgress == null) {
                                          return child;
                                        }
                                        return const Padding(
                                          padding: EdgeInsets.all(20),
                                          child: CircularProgressIndicator(
                                              color: Color(0xFFFF5722)),
                                        );
                                      },
                                      errorBuilder:
                                          (context, error, stackTrace) =>
                                              const Icon(Icons.broken_image,
                                                  size: 50),
                                    ),
                                  )
                                : Text(msgContent,
                                    style: TextStyle(
                                        fontSize: 15,
                                        color: _kTextBrownColor,
                                        height: 1.3)),
                          ),
                        ),
                      );
                    },
                  ),
          ),

          // Hiển thị thanh Loading nhẹ khi đang đẩy ảnh
          if (_isUploadingImage)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 8),
              child: Text("Đang gửi ảnh...",
                  style: TextStyle(
                      color: Colors.grey,
                      fontSize: 12,
                      fontStyle: FontStyle.italic)),
            ),

          // Khu vực nhập tin nhắn
          Container(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
            decoration: BoxDecoration(
              color: Colors.white,
              boxShadow: [
                BoxShadow(
                    color: Colors.black.withValues(alpha: 0.05),
                    blurRadius: 10,
                    offset: const Offset(0, -2))
              ],
            ),
            child: Row(
              children: [
                Icon(Icons.sentiment_satisfied_alt,
                    color: Colors.grey[600], size: 28),
                const SizedBox(width: 12),
                Expanded(
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    decoration: BoxDecoration(
                        color: const Color(0xFFF5F5F5),
                        borderRadius: BorderRadius.circular(25)),
                    child: TextField(
                      controller: _messageCtrl,
                      enabled: !_isChatBlocked,
                      onSubmitted:
                          _isChatBlocked ? null : (_) => _sendMessage(),
                      decoration: InputDecoration(
                        hintText: _isChatBlocked ? _blockedHintText : 'Message',
                        hintStyle: const TextStyle(color: Colors.grey),
                        border: InputBorder.none,
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 12),

                // 5. NÚT CHỌN ẢNH ĐƯỢC KÍCH HOẠT
                GestureDetector(
                  onTap: _isUploadingImage || _isChatBlocked
                      ? null
                      : onPickAndSendImage,
                  child: Icon(Icons.attach_file,
                      color: _isUploadingImage || _isChatBlocked
                          ? Colors.grey[300]
                          : Colors.grey[600],
                      size: 26),
                ),

                const SizedBox(width: 12),
                GestureDetector(
                  onTap: _isChatBlocked ? null : _sendMessage,
                  child: Icon(Icons.send_rounded,
                      color: _isChatBlocked
                          ? Colors.grey[300]
                          : const Color(0xFFFF5722),
                      size: 26),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
