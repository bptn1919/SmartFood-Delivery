// --- MODEL ---
class ChatMessage {
  final String id;
  final String senderName;
  final String message;
  final String time;
  final bool isOnline;
  final int? unreadCount;
  final bool isSender; // true nếu là mình gửi (bong bóng màu vàng)

  ChatMessage({
    required this.id,
    required this.senderName,
    required this.message,
    required this.time,
    this.isOnline = false,
    this.unreadCount,
    this.isSender = false,
  });
}

// --- MOCK DATA DÀNH CHO TRANG LIST ---
final List<ChatMessage> mockConversationList = [
  ChatMessage(
    id: '1',
    senderName: 'Chris Evan',
    message: 'Hello, Your Meal Is On The Way.',
    time: '08:34 PM',
    isOnline: true,
    unreadCount: 1,
  ),
  ChatMessage(id: '2', senderName: 'Anna', message: 'Got it, thanks!', time: '10:00 PM', isOnline: true),
  ChatMessage(
    id: '3',
    senderName: 'Long',
    message: 'Your Meal Is Ready And Will...',
    time: '14:34 PM',
    isOnline: true,
    unreadCount: 1,
  ),
  ChatMessage(id: '4', senderName: 'Adam', message: 'Thank You For Your Order!', time: '08:29 PM', isOnline: true),
  ChatMessage(id: '5', senderName: 'Gordon', message: 'I will add extra spicy.', time: '10:34 AM', isOnline: true),
];

// --- MOCK DATA DÀNH CHO TRANG CHI TIẾT CỦA CHRIS EVAN ---
final List<ChatMessage> mockChatDetailChris = [
  ChatMessage(id: '1a', senderName: 'Me', message: 'Hi, I\'d like to order 2 portions of Bánh Khọt.', time: '08:20 PM', isSender: true),
  ChatMessage(id: '1b', senderName: 'Chris Evan', message: 'Hello! Sure, would you like any extra toppings?', time: '08:21 PM'),
  ChatMessage(id: '1c', senderName: 'Me', message: 'Yes, please add some shrimp.', time: '08:22 PM', isSender: true),
  ChatMessage(id: '1d', senderName: 'Chris Evan', message: 'Got it. Your order will be ready in about 20 minutes.', time: '08:25 PM'),
  ChatMessage(id: '1e', senderName: 'Me', message: 'Great, thank you!', time: '08:26 PM', isSender: true),
];