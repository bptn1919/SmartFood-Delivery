import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:testing/features/chef_manager/models/chef_order_model.dart';
import 'package:testing/features/chef_manager/repositories/chef_order_repository.dart';
import 'chef_order_detail_page.dart';

class ChefOrderPage extends StatefulWidget {
  const ChefOrderPage({super.key});

  @override
  State<ChefOrderPage> createState() => _ChefOrderPageState();
}

class _ChefOrderPageState extends State<ChefOrderPage> {
  // Styling colors
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _textBrown = const Color(0xFF4A3225);

  final ChefOrderRepository _repo = ChefOrderRepository();
  List<ChefOrderModel> _orders = [];
  bool _isLoading = true;
  String _currentStatus = "All";
  final TextEditingController _searchCtrl = TextEditingController();

  @override
  void initState() {
    super.initState();
    _loadOrders();
  }

  Future<void> _loadOrders() async {
    setState(() => _isLoading = true);
    final data = await _repo.getChefOrders(
      search: _searchCtrl.text,
      status: _currentStatus == "All" ? null : _currentStatus,
    );

    if (mounted) {
      setState(() {
        _orders = data;
        _isLoading = false;
      });
    }
  }

  @override
  void dispose() {
    _searchCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        // Search Bar
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
          child: Row(
            children: [
              Expanded(
                child: Container(
                  height: 45,
                  decoration: BoxDecoration(
                    color: Colors.grey[100],
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: TextField(
                    controller: _searchCtrl,
                    onSubmitted: (_) => _loadOrders(),
                    decoration: const InputDecoration(
                      hintText: "Search orders...",
                      border: InputBorder.none,
                      prefixIcon: Icon(Icons.search, color: Colors.grey),
                      contentPadding: const EdgeInsets.symmetric(vertical: 10),
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 10),
              InkWell(
                onTap: () {
                  // Show filter bottom sheet
                },
                child: Container(
                  height: 45,
                  width: 45,
                  decoration: BoxDecoration(
                    color: Colors.grey[100],
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Icon(Icons.filter_list, color: _primaryRed),
                ),
              )
            ],
          ),
        ),

        // Order List
        Expanded(
          child: _isLoading
              ? const Center(child: CircularProgressIndicator())
              : RefreshIndicator(
                  onRefresh: _loadOrders,
                  child: ListView.builder(
                    padding: const EdgeInsets.all(16),
                    itemCount: _orders.length,
                    itemBuilder: (context, index) {
                      return _buildOrderItem(_orders[index]);
                    },
                  ),
                ),
        ),
      ],
    );
  }

  Widget _buildOrderItem(ChefOrderModel order) {
    final currencyFormat = NumberFormat.currency(locale: 'vi_VN', symbol: 'đ');

    return Container(
      margin: const EdgeInsets.only(bottom: 16),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withOpacity(0.05),
            blurRadius: 10,
            offset: const Offset(0, 4),
          )
        ],
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Image
          ClipRRect(
            borderRadius: BorderRadius.circular(12),
            child: Image.network(
              order.firstImage,
              width: 80,
              height: 80,
              cacheWidth: 160,
              cacheHeight: 160,
              fit: BoxFit.cover,
              errorBuilder: (_, __, ___) => Container(
                width: 80,
                height: 80,
                color: Colors.grey[200],
                child: const Icon(Icons.fastfood),
              ),
            ),
          ),
          const SizedBox(width: 12),

          // Info Column
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  order.displayId,
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.bold,
                    color: _textBrown,
                  ),
                ),
                Text(
                  "Date: ${order.createdDate.substring(0, 10)}",
                  style: const TextStyle(fontSize: 12, color: Colors.grey),
                ),

                const Divider(height: 16, thickness: 1),

                // Price Row
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    _buildInfoColumn(
                        "Price", currencyFormat.format(order.totalPrice)),
                    _buildInfoColumn("Quantity", "${order.totalQuantity}"),
                    _buildInfoColumn(
                        "Total", currencyFormat.format(order.totalPrice)),
                  ],
                ),

                const SizedBox(height: 12),

                // Action Row
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    SizedBox(
                      height: 32,
                      child: ElevatedButton(
                        onPressed: () async {
                          await Navigator.push(
                            context,
                            MaterialPageRoute(
                              builder: (context) => ChefOrderDetailPage(
                                orderUid: order.uid,
                              ),
                            ),
                          );
                          _loadOrders();
                        },
                        style: ElevatedButton.styleFrom(
                          backgroundColor: _primaryRed,
                          foregroundColor: Colors.white,
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(8),
                          ),
                          padding: const EdgeInsets.symmetric(horizontal: 20),
                        ),
                        child: const Text("Detail",
                            style: TextStyle(fontSize: 12)),
                      ),
                    ),

                    // Status
                    RichText(
                      text: TextSpan(
                        text: "Status: ",
                        style: const TextStyle(
                            color: Colors.black87,
                            fontSize: 12,
                            fontWeight: FontWeight.bold),
                        children: [
                          TextSpan(
                            text: order.status,
                            style: TextStyle(
                              color: _getStatusColor(order.status),
                              fontWeight: FontWeight.bold,
                            ),
                          )
                        ],
                      ),
                    )
                  ],
                )
              ],
            ),
          )
        ],
      ),
    );
  }

  Widget _buildInfoColumn(String label, String value) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: const TextStyle(fontSize: 10, color: Colors.grey)),
        const SizedBox(height: 2),
        Text(value,
            style: const TextStyle(fontSize: 12, fontWeight: FontWeight.bold)),
      ],
    );
  }

  Color _getStatusColor(String status) {
    switch (status.toUpperCase()) {
      case 'PENDING':
        return Colors.grey;
      case 'CONFIRMED_SHOP':
        return Colors.blue;
      case 'DELIVERING':
        return Colors.orange;
      case 'COMPLETED':
        return Colors.green;
      case 'CANCELLED':
        return Colors.red;
      default:
        return Colors.black;
    }
  }
}
