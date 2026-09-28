import 'package:flutter/material.dart';
import 'package:testing/features/auth/repositories/auth_repository.dart';
import 'package:testing/features/auth/models/user_model.dart';

import 'app_theme.dart';

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
    const Color activeColor = AppColors.surface;
    final Color inactiveColor = AppColors.surface.withOpacity(0.7);
    
    return FutureBuilder<UserModel?>(
      future: AuthRepository().getUserFromCache(),
      builder: (context, snapshot) {
        // Xử lý loading state
        if (snapshot.connectionState == ConnectionState.waiting) {
          // Hiển thị bottom bar mặc định (không có fastfood) trong khi loading
          return _buildDefaultBottomBar(activeColor, inactiveColor);
        }
        
        // Xử lý lỗi hoặc không có user
        if (snapshot.hasError || snapshot.data == null) {
          // Nếu lỗi hoặc không có user, hiển thị bottom bar mặc định (không có fastfood)
          return _buildDefaultBottomBar(activeColor, inactiveColor);
        }
        
        // Lấy thông tin user từ cache
        final UserModel user = snapshot.data!;
        final bool isChef = user.isChef ?? false;
        
        // Định nghĩa các mục navigation
        final List<NavItem> allNavItems = [
          const NavItem(icon: Icons.home, inactiveIcon: Icons.home_outlined, index: 0),
          const NavItem(icon: Icons.fastfood, inactiveIcon: Icons.fastfood_outlined, index: 1, requiresChef: true),
          const NavItem(icon: Icons.favorite, inactiveIcon: Icons.favorite_border, index: 2),
          const NavItem(icon: Icons.assignment, inactiveIcon: Icons.assignment_outlined, index: 3),
          const NavItem(icon: Icons.headset_mic, inactiveIcon: Icons.headset_mic_outlined, index: 4),
          const NavItem(icon: Icons.person, inactiveIcon: Icons.person_outlined, index: 5),
        ];
        
        // Lọc các mục dựa trên role
        final List<NavItem> filteredNavItems = allNavItems.where((item) {
          if (item.requiresChef) {
            return isChef;
          }
          return true;
        }).toList();
        
        // Tạo mapping từ index cũ sang index mới
        final Map<int, int> oldToNewIndexMap = {};
        for (int i = 0; i < filteredNavItems.length; i++) {
          oldToNewIndexMap[filteredNavItems[i].index] = i;
        }
        
        // Tính toán selectedIndex mới
        int newSelectedIndex = -1;
        if (oldToNewIndexMap.containsKey(selectedIndex)) {
          newSelectedIndex = oldToNewIndexMap[selectedIndex]!;
        } else {
          newSelectedIndex = 0;
        }

        return Container(
          height: 80,
          color: AppColors.surface, // 👇 TECH LEAD FIX: Bọc một lớp nền trắng (hoặc màu nền màn hình) để che đi màu lộ ra ở góc bo
          child: Container(
            height: 80,
            decoration: const BoxDecoration(
              color: AppColors.accentPink, // Màu hồng-đỏ chính
              borderRadius: BorderRadius.vertical(top: Radius.circular(AppRadii.dialog)),
            ),
            child: SafeArea(
              top: false,
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                children: filteredNavItems.asMap().entries.map((entry) {
                  final int currentIndex = entry.key;
                  final NavItem item = entry.value;
                  final bool isSelected = newSelectedIndex == currentIndex;
                  
                  return IconButton(
                    padding: EdgeInsets.zero,
                    constraints: const BoxConstraints(),
                    icon: Icon(
                      isSelected ? item.icon : item.inactiveIcon,
                      size: 28,
                      color: isSelected ? activeColor : inactiveColor,
                    ),
                    onPressed: () {
                      onItemTapped(item.index);
                    },
                  );
                }).toList(),
              ),
            ),
          ),
        );

      },
    );
  }
  
  // Bottom bar mặc định khi loading hoặc lỗi (không có tab fastfood)
  Widget _buildDefaultBottomBar(Color activeColor, Color inactiveColor) {
    final List<NavItem> defaultNavItems = [
      const NavItem(icon: Icons.home, inactiveIcon: Icons.home_outlined, index: 0),
      const NavItem(icon: Icons.favorite, inactiveIcon: Icons.favorite_border, index: 2),
      const NavItem(icon: Icons.assignment, inactiveIcon: Icons.assignment_outlined, index: 3),
      const NavItem(icon: Icons.headset_mic, inactiveIcon: Icons.headset_mic_outlined, index: 4),
      const NavItem(icon: Icons.person, inactiveIcon: Icons.person_outlined, index: 5),
    ];
    
    // Tìm index phù hợp
    int newSelectedIndex = 0;
    for (int i = 0; i < defaultNavItems.length; i++) {
      if (defaultNavItems[i].index == selectedIndex) {
        newSelectedIndex = i;
        break;
      }
    }
    
    return BottomAppBar(
      color: AppColors.accentPink,
      elevation: 0,
      padding: EdgeInsets.zero,
      child: SafeArea(
        top: false,
        child: Container(
          height: 56,
          padding: AppInsets.horizontalMd,
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceAround,
            children: defaultNavItems.asMap().entries.map((entry) {
              final int currentIndex = entry.key;
              final NavItem item = entry.value;
              final bool isSelected = newSelectedIndex == currentIndex;
              
              return IconButton(
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints(),
                icon: Icon(
                  isSelected ? item.icon : item.inactiveIcon,
                  size: 28,
                  color: isSelected ? activeColor : inactiveColor,
                ),
                onPressed: () {
                  onItemTapped(item.index);
                },
              );
            }).toList(),
          ),
        ),
      ),
    );
  }
}

class NavItem {
  final IconData icon;
  final IconData inactiveIcon;
  final int index;
  final bool requiresChef;
  
  const NavItem({
    required this.icon,
    required this.inactiveIcon,
    required this.index,
    this.requiresChef = false,
  });
}
