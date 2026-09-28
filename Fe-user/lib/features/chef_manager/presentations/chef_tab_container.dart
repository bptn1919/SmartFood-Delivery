import 'package:flutter/material.dart';
import 'package:testing/features/chef_manager/presentations/chef_menu_page.dart';
import 'package:testing/features/chef_manager/presentations/chef_order_page.dart';
import 'package:testing/features/chef_manager/presentations/voucher_list_page.dart'; // Thêm import cho Voucher Page
import '../repositories/chef_management_repository.dart';

class ChefTabContainer extends StatefulWidget {
  const ChefTabContainer({super.key});

  @override
  State<ChefTabContainer> createState() => _ChefTabContainerState();
}

class _ChefTabContainerState extends State<ChefTabContainer> {
  // Styling colors matching the pattern
  final Color _primaryOrange = const Color(0xFFFFB68C);
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _textBrown = const Color(0xFF4A3225);
  
  bool _isChef = false; 
  bool _isLoading = true;
  
  final _chefRepo = ChefManagementRepository();

  @override
  void initState() {
    super.initState();
    _checkRole();
  }

  Future<void> _checkRole() async {
    final result = await _chefRepo.checkIsChef();
    if (!mounted) return;
    setState(() {
      _isChef = result;
      _isLoading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    // Loading state
    if (_isLoading) {
      return Scaffold(
        backgroundColor: _primaryOrange,
        body: const Center(
          child: CircularProgressIndicator(color: Color(0xFFE55866)),
        ),
      );
    }

    // Not a Chef state
    if (!_isChef) {
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
                    icon: const Icon(Icons.chevron_left, color: Colors.black, size: 30),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                  
                  // Title in center
                  const Expanded(
                    child: Text(
                      "Chef Access",
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
                child: Center(
                  child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(
                          Icons.lock_outline,
                          size: 80,
                          color: Colors.grey[400],
                        ),
                        const SizedBox(height: 16),
                        Text(
                          "Not a Chef Yet",
                          style: TextStyle(
                            fontSize: 22,
                            fontWeight: FontWeight.bold,
                            color: _textBrown,
                          ),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          "Please register as a Chef in your Profile to access this section.",
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            color: Colors.grey[600],
                            fontSize: 14,
                          ),
                        ),
                        const SizedBox(height: 24),
                        SizedBox(
                          width: 200,
                          height: 50,
                          child: ElevatedButton(
                            style: ElevatedButton.styleFrom(
                              backgroundColor: _primaryRed,
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(25),
                              ),
                            ),
                            onPressed: () {
                              Navigator.pop(context);
                            },
                            child: const Text(
                              "Go Back",
                              style: TextStyle(
                                fontSize: 16,
                                fontWeight: FontWeight.bold,
                                color: Colors.white,
                              ),
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      );
    }

    // Chef mode - Main Tab View (3 tabs)
    return DefaultTabController(
      length: 3, // Đổi từ 2 thành 3
      child: Scaffold(
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
                    icon: const Icon(Icons.chevron_left, color: Colors.black, size: 30),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                  
                  // Title in center
                  const Expanded(
                    child: Text(
                      "Chef Dashboard",
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
                child: Column(
                  children: [
                    // Custom Tab Bar
                    Padding(
                      padding: const EdgeInsets.fromLTRB(20, 20, 20, 0),
                      child: Container(
                        decoration: BoxDecoration(
                          color: Colors.grey[100],
                          borderRadius: BorderRadius.circular(30),
                        ),
                        child: TabBar(
                          indicator: BoxDecoration(
                            color: _primaryRed,
                            borderRadius: BorderRadius.circular(30),
                          ),
                          indicatorSize: TabBarIndicatorSize.tab,
                          labelColor: Colors.white,
                          unselectedLabelColor: Colors.grey[600],
                          tabs: const [
                            Tab(text: "Orders"),
                            Tab(text: "Menu"),
                            Tab(text: "Vouchers"), // Thêm tab Voucher
                          ],
                        ),
                      ),
                    ),
                    
                    // Tab Bar View
                    const Expanded(
                      child: TabBarView(
                        children: [
                          ChefOrderPage(),
                          ChefMenuPage(),
                          ChefVoucherListPage(), // Thêm page Voucher
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}