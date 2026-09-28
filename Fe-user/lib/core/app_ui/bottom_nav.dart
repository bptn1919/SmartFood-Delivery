import 'package:flutter/material.dart';
//import 'package:flutter_application_1/features/checkout/order.dart';
//import 'package:flutter_application_1/features/home/home.dart';

class CustomBottomNavBar extends StatelessWidget {

  final int selectedIndex;
  final ValueChanged<int> onItemTapped;

  const CustomBottomNavBar({
    super.key, 
    
    required this.selectedIndex,
    required this.onItemTapped,
    });

  @override
  Widget build(BuildContext context) {

    const Color activeColor = Colors.white;
    final Color inactiveColor = Colors.white.withOpacity(0.7);

    return BottomAppBar(
      color: const Color(0xFFE84D67),
      notchMargin: 8,
      child: SizedBox(
        height: 44,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 6),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceAround,
            children: [
              IconButton(
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints(),
                icon: Icon(
                  selectedIndex == 0 ? Icons.home : Icons.home_outlined,
                  ),
                color: selectedIndex == 0 ? activeColor : inactiveColor,
                onPressed: () {
                  onItemTapped(0);
                },
              ),
              IconButton(
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints(),
                icon: Icon(
                  selectedIndex == 1 ? Icons.fastfood : Icons.fastfood_outlined,
                ),
                color: selectedIndex == 1 ? activeColor : inactiveColor,
                onPressed: () {
                  onItemTapped(1);
                },
              ),
              IconButton(
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints(),
                icon: Icon(
                  selectedIndex == 2 ? Icons.favorite : Icons.favorite_border,
                ),
                color: selectedIndex == 2 ? activeColor : inactiveColor,
                onPressed: () {
                  onItemTapped(2);
                },
              ),
              IconButton(
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints(),
                icon: Icon(
                  selectedIndex == 3 ? Icons.assignment : Icons.assignment_outlined,
                ),
                color: selectedIndex == 3 ? activeColor : inactiveColor,
                onPressed: () {
                  onItemTapped(3);
                },
              ),
              IconButton(
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints(),
                icon: const Icon(Icons.headset_mic_outlined), // Giữ nguyên
                color: inactiveColor, // MUST CHANGE
                onPressed: () {
                  onItemTapped(4);
                },
              ),
              IconButton(
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints(),
                icon: Icon(
                  // Sử dụng icon person hoặc account_circle
                  selectedIndex == 5 ? Icons.person : Icons.person_outline, 
                ),
                // Nhớ thay đổi index tương ứng (ở đây là 5 nếu nó nằm cuối)
                color: selectedIndex == 5 ? activeColor : inactiveColor,
                onPressed: () {
                  onItemTapped(5);
                },
              ),
            ],
          ),
        ),
      ),
    );
  }
}
