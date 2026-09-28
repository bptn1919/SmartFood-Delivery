import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:testing/features/chef_manager/presentations/chef_tab_container.dart';
import 'package:testing/features/help_center/presentations/help_center_page.dart';
import 'package:testing/features/recommend/presentations/recommend_page.dart';

import '../../checkout/presentations/order.dart';
import '../../common/bottom_nav.dart';
import '../../profile/presentations/profile_page.dart';
import 'widgets/home_tab_content.dart';

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  int _selectedIndex = 0;

  static const List<Widget> _pages = <Widget>[
    HomeTabContent(),
    ChefTabContainer(),
    RecommendPage(),
    OrdersPage(),
    HelpCenterPage(),
    ProfilePage(),
  ];

  void _onItemTapped(int index) {
    setState(() => _selectedIndex = index);
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (bool didPop, dynamic result) {
        if (didPop) return;

        if (_selectedIndex != 0) {
          _onItemTapped(0);
        } else {
          SystemNavigator.pop();
        }
      },
      child: Scaffold(
        body: IndexedStack(
          index: _selectedIndex,
          children: _pages,
        ),
        bottomNavigationBar: CustomBottomNavBar(
          selectedIndex: _selectedIndex,
          onItemTapped: _onItemTapped,
        ),
      ),
    );
  }
}
