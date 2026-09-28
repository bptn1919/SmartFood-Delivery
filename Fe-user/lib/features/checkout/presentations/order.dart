import 'dart:async';
import 'package:flutter/material.dart';
import '../repositories/order_repository.dart';
import '../models/customer_order.dart';
import 'order_detail.dart';
import 'package:intl/intl.dart';

class OrdersPage extends StatefulWidget {
  const OrdersPage({super.key});

  @override
  State<OrdersPage> createState() => _OrdersPageState();
}

class _OrdersPageState extends State<OrdersPage> {
  // --- CONSTANTS ---
  final Color _primaryOrange = const Color(0xFFFFB68C);
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _textBrown = const Color(0xFF4A3225);

  // --- STATE ---
  final _repo = OrderRepository();
  bool _loading = true;
  String? _error;
  List<CustomerOrder> _orders = [];

  // --- FILTER ---
  String _query = '';
  String _sortType = "desc";
  Timer? _searchDebounce;
  final _searchController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _searchDebounce?.cancel();
    _searchController.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    if (!mounted) return;
    setState(() {
      _loading = true;
      _error = null;
    });

    try {
      final list = await _repo.getMyOrders(
        search: _query.trim().isEmpty ? null : _query,
        orderBy: "created_at",
        sortType: _sortType,
      );

      debugPrint("🔍 UI received: ${list.length} orders");

      if (mounted) {
        setState(() {
          _orders = list;
          _loading = false;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _error = e.toString();
          _loading = false;
        });
      }
    }
  }

  void _onSearchChanged(String query) {
    _query = query;
    _searchDebounce?.cancel();
    _searchDebounce = Timer(const Duration(milliseconds: 500), _load);
  }

  void _toggleSort() {
    setState(() => _sortType = (_sortType == "desc") ? "asc" : "desc");
    _load();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _primaryOrange,
      body: Column(
        children: [
          // ===== HEADER WITH PATTERN (GIỮ NGUYÊN) =====
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 60, 20, 20),
            child: const Center(
              child: Text(
                "My Orders",
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 28,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
          ),

          // ===== BODY WITH WHITE CARD (GIỮ NGUYÊN) =====
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
              child: Column(
                children: [
                  // Search Bar & Sort (GIỮ NGUYÊN)
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 20, 16, 10),
                    child: Row(
                      children: [
                        Expanded(
                          child: Container(
                            decoration: BoxDecoration(
                              color: Colors.grey.shade100,
                              borderRadius: BorderRadius.circular(12),
                            ),
                            child: TextField(
                              controller: _searchController,
                              onChanged: _onSearchChanged,
                              decoration: const InputDecoration(
                                hintText: 'Search orders...',
                                prefixIcon:
                                    Icon(Icons.search, color: Colors.grey),
                                border: InputBorder.none,
                                contentPadding: EdgeInsets.symmetric(
                                    horizontal: 16, vertical: 14),
                              ),
                            ),
                          ),
                        ),
                        const SizedBox(width: 10),
                        Container(
                          decoration: BoxDecoration(
                            color: Colors.grey.shade100,
                            borderRadius: BorderRadius.circular(12),
                          ),
                          child: IconButton(
                            icon: Icon(
                              _sortType == "desc"
                                  ? Icons.sort
                                  : Icons.filter_list,
                              color: _primaryRed,
                            ),
                            onPressed: _toggleSort,
                            tooltip: "Sort by date",
                          ),
                        ),
                      ],
                    ),
                  ),

                  // Content List
                  Expanded(
                    child: _buildContent(),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildContent() {
    if (_loading) {
      return const Center(
          child: CircularProgressIndicator(color: Color(0xFFE55866)));
    }

    if (_error != null) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.error_outline, size: 40, color: Colors.red),
            const SizedBox(height: 8),
            Text(_error!, style: const TextStyle(color: Colors.red)),
            TextButton(onPressed: _load, child: const Text("Retry"))
          ],
        ),
      );
    }

    if (_orders.isEmpty) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.receipt_long, size: 60, color: Colors.grey.shade300),
            const SizedBox(height: 16),
            const Text('No orders found', style: TextStyle(color: Colors.grey)),
          ],
        ),
      );
    }

    return RefreshIndicator(
      onRefresh: _load,
      color: _primaryRed,
      child: ListView.separated(
        padding: const EdgeInsets.all(16),
        itemCount: _orders.length,
        separatorBuilder: (_, __) => const SizedBox(height: 16),
        itemBuilder: (context, index) {
          final order = _orders[index];

          // 👇 CHỈ THAY ĐỔI DÒNG NÀY: Dùng UI Card chuẩn Figma 👇
          return _buildFigmaOrderCard(order);
        },
      ),
    );
  }

  // ===========================================================================
  // WIDGET THẺ ĐƠN HÀNG CHUẨN FIGMA
  // ===========================================================================
  Widget _buildFigmaOrderCard(CustomerOrder order) {
    final String displayDate = _formatOrderDate(order.deliveryDate);
    final String displayTime =
        order.deliveryTime.isEmpty ? '--:--' : order.deliveryTime;
    final String rawStatus = order.status.name;

    final String status = rawStatus.isNotEmpty
        ? '${rawStatus[0].toUpperCase()}${rawStatus.substring(1).toLowerCase()}'
        : 'Pending';

    // 👇 TECH LEAD FIX 1: Lấy tổng số lượng tất cả các món ăn trong đơn
    final int totalItemsQty =
        order.items.fold(0, (sum, item) => sum + item.quantity);

    // Tổng tiền của cả đơn hàng (Chỉnh 'totalPrice' thành biến đúng trong model của bạn)
    final double orderTotal = order.totalPrice;

    // 👇 Tích hợp phương thức giao hàng chúng ta vừa làm
    final String deliveryMethod =
        (order.deliveryType == 'SELF_PICKUP') ? 'Pick-up' : 'Delivery';

    final String dishImage =
        order.items.isEmpty ? '' : (order.items.first.imageUrl ?? '');

    final currencyFormatter = NumberFormat.currency(
      locale: 'vi_VN',
      symbol: 'đ',
      decimalDigits: 0,
    );

    String displayTitle = "Order #${order.uid}";

    if (order.items.isNotEmpty) {
      final firstDishName = order.items.first.dishName;
      if (order.items.length > 1) {
        final remainingItems = order.items.length - 1;
        displayTitle =
            "$firstDishName & $remainingItems ${remainingItems > 1 ? "items" : "item"}";
      } else {
        displayTitle = firstDishName;
      }
    }

    // Đổi màu text theo status
    Color statusColor = Colors.grey;
    if (status.toLowerCase() == "pending") {
      statusColor = Colors.grey;
    } else if (status.toLowerCase() == "confirmed_shop") {
      statusColor = Colors.blue;
    } else if (status.toLowerCase() == "delivering") {
      statusColor = Colors.orange;
    } else if (status.toLowerCase() == "completed") {
      statusColor = Colors.green;
    } else if (status.toLowerCase() == "cancelled") {
      statusColor = Colors.red;
    } else {
      statusColor = Colors.black;
    }

    return GestureDetector(
      onTap: () {
        Navigator.push(
          context,
          MaterialPageRoute(
              builder: (_) => OrderDetailPage(orderUid: order.uid)),
        );
      },
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: const Color(0xFFF6F6F6),
          borderRadius: BorderRadius.circular(20),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // --- CỘT TRÁI: Ảnh + Nút Track Order ---
            Column(
              children: [
                ClipRRect(
                  borderRadius: BorderRadius.circular(12),
                  child: Image.network(
                    dishImage,
                    width: 80,
                    height: 80,
                    // 👇 TECH LEAD FIX 2: Bỏ cacheWidth/cacheHeight để chống méo ảnh
                    fit: BoxFit.cover,
                    errorBuilder: (_, __, ___) => Container(
                        width: 80, height: 80, color: Colors.grey[300]),
                  ),
                ),
                const SizedBox(height: 10),
                SizedBox(
                  width: 80,
                  height: 26,
                  child: ElevatedButton(
                    style: ElevatedButton.styleFrom(
                      backgroundColor: const Color(0xFFF7B731),
                      padding: EdgeInsets.zero,
                      shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(6)),
                      elevation: 0,
                    ),
                    onPressed: () {
                      // Xử lý Track Order (nếu cần)
                    },
                    child: const Text("Track order",
                        style: TextStyle(
                            fontSize: 10,
                            color: Colors.white,
                            fontWeight: FontWeight.bold)),
                  ),
                ),
              ],
            ),
            const SizedBox(width: 16),

            // --- CỘT PHẢI: Chi tiết Đơn hàng ---
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // ID + Ngày
                  Text(displayTitle,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                          fontSize: 18, // Giảm nhẹ xíu để chống tràn chữ
                          fontWeight: FontWeight.w900,
                          color: Color(0xFF4A3225))),
                  const SizedBox(height: 2),
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          '$displayDate • $displayTime',
                          style: TextStyle(
                              fontSize: 12, color: Colors.grey.shade500),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    ],
                  ),

                  const SizedBox(height: 8),
                  const Divider(color: Colors.grey, thickness: 1, height: 1),
                  const SizedBox(height: 8),

                  // 👇 TECH LEAD FIX 3: Bảng tính UX mới
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      _buildInfoCol("Items", "$totalItemsQty"),
                      _buildInfoCol("Method", deliveryMethod),
                      _buildInfoCol(
                          "Total", currencyFormatter.format(orderTotal)),
                    ],
                  ),

                  const SizedBox(height: 8),
                  const Divider(color: Colors.grey, thickness: 1, height: 1),
                  const SizedBox(height: 8),

                  // Tracking Status
                  Row(
                    children: [
                      const Text("Tracking Status",
                          style: TextStyle(
                              fontSize: 12,
                              fontWeight: FontWeight.bold,
                              color: Color(0xFF4A3225))),
                      const SizedBox(width: 12),
                      Text(status,
                          style: TextStyle(
                              fontSize: 12,
                              fontWeight: FontWeight.bold,
                              color: statusColor)),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  // Cột chữ nhỏ trong Card (Price, Qty, Total)
  Widget _buildInfoCol(String label, String value) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label,
            style: TextStyle(fontSize: 11, color: Colors.grey.shade600)),
        const SizedBox(height: 2),
        Text(value,
            style: TextStyle(
                fontSize: 12, fontWeight: FontWeight.bold, color: _textBrown)),
      ],
    );
  }

  String _formatOrderDate(String? rawDate) {
    if (rawDate == null || rawDate.isEmpty) return "N/A";
    try {
      final date = DateTime.parse(rawDate);
      final months = [
        'Jan',
        'Feb',
        'Mar',
        'Apr',
        'May',
        'Jun',
        'Jul',
        'Aug',
        'Sep',
        'Oct',
        'Nov',
        'Dec'
      ];
      // Ép ngày luôn có 2 chữ số (VD: 05 thay vì 5)
      final dayStr = date.day.toString().padLeft(2, '0');
      return "$dayStr ${months[date.month - 1]}, ${date.year}";
    } catch (e) {
      return rawDate; // Nếu lỗi parse thì trả về nguyên gốc
    }
  }
}
