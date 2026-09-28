import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:testing/features/chat/presentations/chat_detail_page.dart';
import 'package:testing/features/chat/service/chat_service.dart';
import 'package:testing/features/chef_manager/models/chef_order_detail_model.dart';
import 'package:testing/features/chef_manager/repositories/chef_order_repository.dart';
import 'package:testing/features/chef_manager/services/chef_tracking.dart';
import 'package:testing/features/common/app_components.dart';

class ChefOrderDetailPage extends StatefulWidget {
  final String orderUid;

  const ChefOrderDetailPage({super.key, required this.orderUid});

  @override
  State<ChefOrderDetailPage> createState() => _ChefOrderDetailPageState();
}

class _ChefOrderDetailPageState extends State<ChefOrderDetailPage> {
  // Styling colors matching the pattern
  final Color _primaryOrange = const Color(0xFFFFB68C);
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _textBrown = const Color(0xFF4A3225);
  final Color _accentRed = const Color(0xFFFF3B30);

  bool _isLoading = true;
  ChefOrderDetailModel? _order;
  final ChefOrderRepository _repo = ChefOrderRepository();
  final ChefTrackingService _trackingService = ChefTrackingService();
  final ChatService _chatService = ChatService();
  bool _isCreatingChat = false;

  @override
  void initState() {
    super.initState();
    _loadData();
  }

  Future<void> _loadData() async {
    setState(() => _isLoading = true);
    final data = await _repo.getOrderDetail(widget.orderUid);
    debugPrint(
        "Fetched Order Detail for UID ~~~~<~>~<~>~<~>~<~> ${data?.status}");
    if (mounted) {
      setState(() {
        _order = data;
        _isLoading = false;
      });
    }
  }

  Future<void> _processOrderStatus(String action) async {
    setState(() => _isLoading = true);
    bool success = false;
    String successMsg = "";

    debugPrint(
        "Processing action------------------------------------: $action for order UID: ${widget.orderUid}");

    try {
      if (action == 'CONFIRMED_SHOP') {
        success = await _repo.confirmOrder(widget.orderUid);
        successMsg = "Order confirmed successfully!";
      } else if (action == 'START_PROCESSING') {
        success = await _repo.startProcessing(widget.orderUid);
        successMsg = "Order is now being processed!";
      } else if (action == 'START_DELIVERY') {
        debugPrint(
            "Starting delivery, success: $success for order UID 22 33: ${widget.orderUid}");
        success = await _repo.startDelivery(widget.orderUid);
        debugPrint(
            "Starting delivery, success: $success for order UID: ${widget.orderUid}");
        // _trackingService.startTracking(
        //     widget.orderUid); // Bắt đầu tracking vị trí giao hàng trên Firebase
        successMsg = "Order is out for delivery!";
      } else if (action == 'COMPLETED') {
        success = await _repo.completeOrder(widget.orderUid);
        _trackingService.stopTracking(
            widget.orderUid); // Dừng tracking vị trí giao hàng trên Firebase
        successMsg = "Order completed successfully!";
      }

      if (mounted) {
        if (success) {
          showAppSnackBar(context, successMsg, type: SnackBarType.success);
          _loadData(); // Tải lại data để lấy status mới nhất từ server
        } else {
          showAppSnackBar(context, "Action failed. Please try again.",
              type: SnackBarType.error);
          setState(() => _isLoading = false);
        }
      }
    } catch (e) {
      if (mounted) {
        setState(() => _isLoading = false);
        showAppSnackBar(context, "Error: $e", type: SnackBarType.error);
      }
    }
  }

  Future<void> _startChatWithCustomer() async {
    final order = _order;
    if (order == null || order.customerUserId <= 0) {
      showAppSnackBar(
        context,
        'Cannot start chat with this customer right now.',
        type: SnackBarType.error,
      );
      return;
    }

    setState(() => _isCreatingChat = true);

    try {
      final prefs = await SharedPreferences.getInstance();
      final token = prefs.getString('token') ?? '';
      final myUserId = prefs.getInt('user_id') ?? 0;

      if (token.isEmpty || myUserId <= 0) {
        throw Exception('Missing chat session. Please log in again.');
      }

      final roomId = await _chatService.getOrCreateRoom(
        token: token,
        partnerId: order.customerUserId,
      );

      if (!mounted) return;
      setState(() => _isCreatingChat = false);

      if (roomId == null || roomId.isEmpty) {
        showAppSnackBar(
          context,
          'Could not open chat. Please try again.',
          type: SnackBarType.error,
        );
        return;
      }

      Navigator.push(
        context,
        MaterialPageRoute(
          builder: (_) => ChatDetailPage(
            senderName: order.customerName,
            roomId: roomId,
            token: token,
            myUserId: myUserId,
            partnerUserId: order.customerUserId,
          ),
        ),
      );
    } catch (e) {
      if (!mounted) return;
      setState(() => _isCreatingChat = false);
      showAppSnackBar(
        context,
        'Failed to start chat: $e',
        type: SnackBarType.error,
      );
    }
  }

  // 👇 THÊM HÀM NÀY: Helper vẽ nút bấm cho sạch code
  Widget _buildActionButton(String text, Color color, VoidCallback onPressed,
      {bool isFullWidth = false}) {
    final button = ElevatedButton(
      onPressed: onPressed,
      style: ElevatedButton.styleFrom(
        backgroundColor: color,
        padding: const EdgeInsets.symmetric(vertical: 15),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(25)),
      ),
      child: Text(text,
          style: const TextStyle(
              color: Colors.white, fontSize: 16, fontWeight: FontWeight.bold)),
    );

    return isFullWidth
        ? SizedBox(width: double.infinity, child: button)
        : button;
  }

  // 👇 THÊM HÀM NÀY: Helper chọn màu text cho các trạng thái cuối (Completed, Cancelled...)
  Color _getStatusColor(String status) {
    switch (status) {
      case 'COMPLETED':
        return Colors.green;
      case 'REJECTED':
      case 'CANCELLED':
        return _accentRed;
      default:
        return Colors.grey;
    }
  }

  void _showRejectDialog() {
    final TextEditingController reasonCtrl = TextEditingController();

    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: const Text("Reject Order",
            style: TextStyle(fontWeight: FontWeight.bold)),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text("Please enter the reason for rejection:"),
            const SizedBox(height: 10),
            TextField(
              controller: reasonCtrl,
              decoration: const InputDecoration(
                hintText: "E.g. Out of stock, Closing soon...",
                border: OutlineInputBorder(),
              ),
              maxLines: 2,
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text("Cancel", style: TextStyle(color: Colors.grey)),
          ),
          ElevatedButton(
            onPressed: () {
              Navigator.pop(context);
              _handleRejectAction(reasonCtrl.text);
            },
            style: ElevatedButton.styleFrom(backgroundColor: Colors.red),
            child: const Text("Confirm Reject",
                style: TextStyle(color: Colors.white)),
          ),
        ],
      ),
    ).whenComplete(reasonCtrl.dispose);
  }

  Future<void> _handleRejectAction(String reason) async {
    if (reason.isEmpty) {
      showAppSnackBar(
        context,
        "Please enter a reason!",
        type: SnackBarType.warning,
      );
      return;
    }

    setState(() => _isLoading = true);

    final success = await _repo.rejectOrder(widget.orderUid, reason);

    if (mounted) {
      setState(() => _isLoading = false);
      if (success) {
        showAppSnackBar(
          context,
          "Order rejected successfully",
          type: SnackBarType.success,
        );
        _loadData();
      } else {
        showAppSnackBar(
          context,
          "Failed to reject order. Please try again.",
          type: SnackBarType.error,
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final currencyFormat = NumberFormat.currency(locale: 'en_US', symbol: '\$');

    debugPrint("Customer User ID for chat: ${_order!.customerUserId}");

    if (_isLoading) {
      return Scaffold(
        backgroundColor: _primaryOrange,
        body: const Center(child: CircularProgressIndicator()),
      );
    }

    if (_order == null) {
      return Scaffold(
        backgroundColor: _primaryOrange,
        body: const Center(child: Text("Order not found")),
      );
    }

    return Scaffold(
      backgroundColor: _primaryOrange,
      body: Column(
        children: [
          // ===== HEADER WITH PATTERN =====
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 60, 20, 20),
            child: Row(
              children: [
                // Back button
                IconButton(
                  icon: const Icon(Icons.chevron_left,
                      color: Colors.black, size: 30),
                  onPressed: () => Navigator.of(context).pop(),
                ),

                // Title in center
                const Expanded(
                  child: Text(
                    "Order Detail",
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: Colors.white,
                      fontSize: 28,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),

                // Placeholder for balance
                const SizedBox(width: 48),
              ],
            ),
          ),

          // ===== BODY WITH WHITE CARD =====
          Expanded(
            child: Container(
              width: double.infinity,
              decoration: const BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.only(
                  topLeft: Radius.circular(30),
                  topRight: Radius.circular(30),
                ),
              ),
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // --- 1. HEADER (ID & NAME) ---
                    Container(
                      width: double.infinity,
                      padding: const EdgeInsets.all(20),
                      decoration: BoxDecoration(
                        color: const Color(0xFFFFF0E0),
                        borderRadius: BorderRadius.circular(16),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            "${_order!.displayId} - ${_order!.customerName}",
                            style: TextStyle(
                              fontSize: 22,
                              fontWeight: FontWeight.bold,
                              color: _textBrown,
                            ),
                          ),
                          const SizedBox(height: 10),
                          _buildContactRow(Icons.phone, _order!.phoneNumber),
                          const SizedBox(height: 5),
                          _buildContactRow(Icons.location_on, _order!.address),
                          
                          if (_order!.customerUserId > 0) ...[
                            const SizedBox(height: 14),
                            SizedBox(
                              width: double.infinity,
                              child: OutlinedButton.icon(
                                onPressed: _isCreatingChat
                                    ? null
                                    : _startChatWithCustomer,
                                icon: _isCreatingChat
                                    ? const SizedBox(
                                        width: 16,
                                        height: 16,
                                        child: CircularProgressIndicator(
                                          strokeWidth: 2,
                                        ),
                                      )
                                    : const Icon(Icons.chat_bubble_outline),
                                label: Text(
                                  _isCreatingChat
                                      ? 'Opening chat...'
                                      : 'Chat with Customer',
                                ),
                                style: OutlinedButton.styleFrom(
                                  foregroundColor: _primaryRed,
                                  side: BorderSide(color: _primaryRed),
                                  shape: RoundedRectangleBorder(
                                    borderRadius: BorderRadius.circular(12),
                                  ),
                                ),
                              ),
                            ),
                          ],
                        ],
                      ),
                    ),

                    const SizedBox(height: 20),

                    // --- 2. LIST ITEMS ---
                    const Text("Order Items",
                        style: TextStyle(
                            fontSize: 18,
                            fontWeight: FontWeight.bold,
                            color: Color(0xFF4A3225))),
                    const SizedBox(height: 12),
                    ListView.builder(
                      padding: EdgeInsets.zero,
                      shrinkWrap: true,
                      physics: const NeverScrollableScrollPhysics(),
                      itemCount: _order!.items.length,
                      itemBuilder: (context, index) {
                        return _buildDishItem(
                            _order!.items[index], currencyFormat);
                      },
                    ),

                    // --- 3. NOTE SECTION ---
                    if (_order!.note != null && _order!.note!.isNotEmpty) ...[
                      const SizedBox(height: 16),
                      const Text("Note",
                          style: TextStyle(
                              fontWeight: FontWeight.bold,
                              fontSize: 16,
                              color: Color(0xFF4A3225))),
                      const SizedBox(height: 8),
                      Container(
                        width: double.infinity,
                        padding: const EdgeInsets.all(12),
                        decoration: BoxDecoration(
                          color: const Color(0xFFFFF9C4),
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: Text(_order!.note!),
                      ),
                    ],

                    const SizedBox(height: 20),
                    const Divider(thickness: 1),

                    // --- 4. PAYMENT SUMMARY ---
                    const SizedBox(height: 16),
                    const Text("Payment Summary",
                        style: TextStyle(
                            fontSize: 18,
                            fontWeight: FontWeight.bold,
                            color: Color(0xFF4A3225))),
                    const SizedBox(height: 12),
                    Column(
                      children: [
                        _buildSummaryRow(
                            "Payment method", _order!.paymentMethod,
                            isBold: true),
                        _buildSummaryRow("Subtotal",
                            currencyFormat.format(_order!.subTotal)),
                        _buildSummaryRow("Tax and Fees",
                            currencyFormat.format(_order!.taxAndFees)),
                        _buildSummaryRow("Delivery",
                            currencyFormat.format(_order!.deliveryFee)),
                        const Padding(
                            padding: EdgeInsets.symmetric(vertical: 8),
                            child: DottedLine()),
                        _buildSummaryRow(
                            "Total", currencyFormat.format(_order!.totalPrice),
                            isTotal: true),
                      ],
                    ),

                    const SizedBox(height: 24),

                    // --- 5. ACTION BUTTONS ---
                    if (_order!.status == 'PENDING') ...[
                      Row(
                        children: [
                          Expanded(
                              child: _buildActionButton(
                                  "Reject", _accentRed, _showRejectDialog)),
                          const SizedBox(width: 15),
                          Expanded(
                              child: _buildActionButton("Accept", _primaryRed,
                                  () => _processOrderStatus('CONFIRMED_SHOP'))),
                        ],
                      ),
                    ] else if (_order!.status == 'CONFIRMED_SYSTEM') ...[
                      Row(
                        children: [
                          Expanded(
                              child: _buildActionButton(
                                  "Reject", _accentRed, _showRejectDialog)),
                          const SizedBox(width: 15),
                          Expanded(
                              child: _buildActionButton("Accept", _primaryRed,
                                  () => _processOrderStatus('CONFIRMED_SHOP'))),
                        ],
                      ),
                    ] else if (_order!.status == 'CONFIRMED_SHOP') ...[
                      _buildActionButton(
                          "Start Processing",
                          Colors.orange.shade700,
                          () => _processOrderStatus('START_PROCESSING'),
                          isFullWidth: true),
                    ] else if (_order!.status == 'PROCESSING') ...[
                      _buildActionButton("Start Delivery", Colors.blue.shade600,
                          () => _processOrderStatus('START_DELIVERY'),
                          isFullWidth: true),
                    ] else if (_order!.status == 'DELIVERING') ...[
                      _buildActionButton(
                          "Complete Order",
                          Colors.green.shade600,
                          () => _processOrderStatus('COMPLETED'),
                          isFullWidth: true),
                    ] else ...[
                      // Dành cho các trạng thái không còn action nào nữa (COMPLETED, REJECTED, CANCELLED)
                      Center(
                        child: Text(
                          "Order status: ${_order!.status}",
                          style: TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.bold,
                            color: _getStatusColor(_order!.status),
                          ),
                        ),
                      ),
                    ],
                    const SizedBox(height: 20),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  // --- WIDGET HELPERS ---

  Widget _buildContactRow(IconData icon, String text) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, size: 16, color: Colors.black87),
        const SizedBox(width: 8),
        Expanded(
            child: Text(text,
                style: const TextStyle(color: Colors.black87, fontSize: 13))),
      ],
    );
  }

  Widget _buildDishItem(ChefOrderItem item, NumberFormat fmt) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 16.0),
      child: Row(
        children: [
          // Dish image
          ClipRRect(
            borderRadius: BorderRadius.circular(12),
            child: Image.network(
              item.image,
              width: 60,
              height: 60,
              cacheWidth: 120,
              cacheHeight: 120,
              fit: BoxFit.cover,
              errorBuilder: (_, __, ___) => Container(
                color: Colors.grey[200],
                width: 60,
                height: 60,
                child: const Icon(Icons.fastfood, color: Colors.grey),
              ),
            ),
          ),
          const SizedBox(width: 12),
          // Information
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(item.name,
                    style: const TextStyle(
                        fontWeight: FontWeight.bold, fontSize: 16)),
                Text(item.date,
                    style: const TextStyle(
                        fontSize: 10, color: Color(0xFFE55866))),
                const SizedBox(height: 4),
                Text(fmt.format(item.price),
                    style: const TextStyle(
                        fontSize: 16,
                        color: Color(0xFFE55866),
                        fontWeight: FontWeight.bold)),
              ],
            ),
          ),
          // Quantity
          Text("X${item.quantity}",
              style:
                  const TextStyle(fontSize: 16, fontWeight: FontWeight.w500)),
        ],
      ),
    );
  }

  Widget _buildSummaryRow(String label, String value,
      {bool isBold = false, bool isTotal = false}) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8.0),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(
            label,
            style: TextStyle(
              color: Colors.black87,
              fontSize: isTotal ? 18 : 14,
              fontWeight:
                  (isBold || isTotal) ? FontWeight.bold : FontWeight.normal,
            ),
          ),
          Text(
            value,
            style: TextStyle(
              color: isTotal ? _primaryRed : Colors.black87,
              fontSize: isTotal ? 18 : 14,
              fontWeight:
                  (isBold || isTotal) ? FontWeight.bold : FontWeight.normal,
            ),
          ),
        ],
      ),
    );
  }
}

// Dotted line widget
class DottedLine extends StatelessWidget {
  const DottedLine({super.key});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: List.generate(
          150 ~/ 2,
          (index) => Expanded(
                child: Container(
                  color: index % 2 == 0
                      ? Colors.transparent
                      : const Color(0xFFE55866).withValues(alpha: 0.3),
                  height: 1,
                ),
              )),
    );
  }
}
