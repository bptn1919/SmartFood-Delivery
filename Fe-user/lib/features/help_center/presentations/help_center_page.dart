import 'package:flutter/material.dart';

class HelpCenterPage extends StatelessWidget {
  const HelpCenterPage({super.key});

  // --- MÀU SẮC CHỦ ĐẠO (Dùng lại màu đỏ AmoMeal của bạn) ---
  final Color _primaryRed = const Color(0xFFE84D67);
  final Color _bg = const Color(0xFFF7F7F7);
  final Color _textDark = const Color(0xFF2D3142);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _bg,
      appBar: AppBar(
        backgroundColor: Colors.white,
        elevation: 0,
        centerTitle: true,
        title: Text(
          "Help Center",
          style: TextStyle(color: _textDark, fontSize: 18, fontWeight: FontWeight.bold),
        ),
      ),
      body: SingleChildScrollView(
        physics: const BouncingScrollPhysics(),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // 1. HEADER BANNER
            Container(
              width: double.infinity,
              color: Colors.white,
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 24),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    "Hi there,",
                    style: TextStyle(color: Colors.grey.shade500, fontSize: 16),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    "How can we help\nyou today?",
                    style: TextStyle(color: _textDark, fontSize: 28, fontWeight: FontWeight.bold, height: 1.2),
                  ),
                  const SizedBox(height: 24),
                  
                  // THANH TÌM KIẾM ẢO
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
                    decoration: BoxDecoration(
                      color: _bg,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Row(
                      children: [
                        Icon(Icons.search, color: Colors.grey.shade400),
                        const SizedBox(width: 12),
                        Text("Search for topics or questions...", style: TextStyle(color: Colors.grey.shade400)),
                      ],
                    ),
                  ),
                ],
              ),
            ),

            const SizedBox(height: 24),

            // 2. QUICK CONTACT BOXES (Live Chat & Hotline)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              child: Row(
                children: [
                  Expanded(
                    child: _buildContactCard(
                      icon: Icons.chat_bubble_outline,
                      title: "Live Chat",
                      subtitle: "Typical reply: 2 mins",
                      color: _primaryRed,
                      onTap: () {
                        // TODO: Open Chat
                      },
                    ),
                  ),
                  const SizedBox(width: 16),
                  Expanded(
                    child: _buildContactCard(
                      icon: Icons.phone_in_talk_outlined,
                      title: "Call Us",
                      subtitle: "24/7 Support",
                      color: const Color(0xFF4A3225), // Màu nâu giống _textBrown của bạn
                      onTap: () {
                        // TODO: Call Hotline
                      },
                    ),
                  ),
                ],
              ),
            ),

            const SizedBox(height: 32),

            // 3. FAQ SECTION (Câu hỏi thường gặp)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24),
              child: Text(
                "Frequently Asked Questions",
                style: TextStyle(color: _textDark, fontSize: 18, fontWeight: FontWeight.bold),
              ),
            ),
            const SizedBox(height: 12),
            
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              child: Container(
                decoration: BoxDecoration(
                  color: Colors.white,
                  borderRadius: BorderRadius.circular(16),
                  boxShadow: [
                    BoxShadow(color: Colors.black.withOpacity(0.03), blurRadius: 10, offset: const Offset(0, 4))
                  ],
                ),
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(16),
                  child: Column(
                    children: [
                      _buildFAQItem(
                        question: "Where is my order?",
                        answer: "You can track your order in real-time by going to the 'Orders' tab and tapping on your active order. The Chef's location will be displayed on the map.",
                      ),
                      _buildDivider(),
                      _buildFAQItem(
                        question: "How do I cancel my order?",
                        answer: "Orders can only be cancelled before the Chef starts preparing your meal (usually within 2 minutes of placing the order). Go to Order Details and tap 'Cancel'.",
                      ),
                      _buildDivider(),
                      _buildFAQItem(
                        question: "The food arrived damaged or incomplete",
                        answer: "We sincerely apologize for the inconvenience. Please use the Live Chat option above and provide a picture of the received food. We will process a refund or replacement immediately.",
                      ),
                      _buildDivider(),
                      _buildFAQItem(
                        question: "How do refunds work?",
                        answer: "Refunds for cancelled orders are processed automatically. It may take 3-5 business days for the amount to reflect in your bank account depending on your payment method.",
                      ),
                    ],
                  ),
                ),
              ),
            ),
            const SizedBox(height: 40), // Spacing bottom
          ],
        ),
      ),
    );
  }

  // --- WIDGET PHỤ TRỢ: Thẻ Liên Hệ ---
  Widget _buildContactCard({
    required IconData icon,
    required String title,
    required String subtitle,
    required Color color,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(16),
          boxShadow: [
            BoxShadow(color: Colors.black.withOpacity(0.04), blurRadius: 8, offset: const Offset(0, 4))
          ],
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: color.withOpacity(0.1),
                shape: BoxShape.circle,
              ),
              child: Icon(icon, color: color, size: 24),
            ),
            const SizedBox(height: 16),
            Text(title, style: TextStyle(color: _textDark, fontWeight: FontWeight.bold, fontSize: 16)),
            const SizedBox(height: 4),
            Text(subtitle, style: TextStyle(color: Colors.grey.shade500, fontSize: 12)),
          ],
        ),
      ),
    );
  }

  // --- WIDGET PHỤ TRỢ: FAQ Item (Accordion) ---
  Widget _buildFAQItem({required String question, required String answer}) {
    return Theme(
      data: ThemeData().copyWith(dividerColor: Colors.transparent), // Xóa vạch kẻ mặc định của ExpansionTile
      child: ExpansionTile(
        iconColor: _primaryRed,
        collapsedIconColor: Colors.grey.shade400,
        title: Text(
          question,
          style: TextStyle(color: _textDark, fontSize: 14, fontWeight: FontWeight.w600),
        ),
        childrenPadding: const EdgeInsets.only(left: 16, right: 16, bottom: 16),
        children: [
          Text(
            answer,
            style: TextStyle(color: Colors.grey.shade600, fontSize: 13, height: 1.5),
          ),
        ],
      ),
    );
  }

  // --- WIDGET PHỤ TRỢ: Đường kẻ ngang ---
  Widget _buildDivider() {
    return Divider(height: 1, color: Colors.grey.shade100, indent: 16, endIndent: 16);
  }
}