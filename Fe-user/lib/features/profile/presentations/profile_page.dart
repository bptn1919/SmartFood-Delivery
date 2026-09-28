import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:testing/core/network/api_constants.dart';
import 'package:testing/features/notifications/presentations/notification_page.dart';


import 'package:testing/features/profile/presentations/change_password_page.dart';
import 'package:testing/features/profile/presentations/chef_profile_page.dart';
import 'package:testing/features/profile/presentations/edit_profile_page.dart';
import 'package:testing/features/profile/presentations/shipping_address_page.dart';
import 'package:testing/features/profile/repository/chef_profile_repository.dart';

import 'package:testing/features/profile/repository/customer_profile_repository.dart';
import 'package:testing/features/chef_manager/repositories/chef_management_repository.dart';
import 'package:testing/features/auth/repositories/auth_repository.dart';
import 'package:testing/features/recommend/presentations/allergy_profile_page.dart';
import 'package:testing/features/recommend/presentations/nutrition_page.dart';
import 'package:testing/features/wallet/presentations/chef_wallet_page.dart';
import 'package:testing/features/wallet/presentations/customer_wallet_page.dart';

import 'chef_registration_page.dart';
import 'my_reviews_page.dart';
import 'chef_analytics_page.dart';
import '../../common/app_components.dart';
import '../../chat/presentations/conversation_list_page.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'dart:convert';
import 'package:http/http.dart' as http;

class ProfilePage extends StatefulWidget {
  const ProfilePage({super.key});

  @override
  State<ProfilePage> createState() => _ProfilePageState();
}

class _ProfilePageState extends State<ProfilePage> {
  // --- STYLING ---
  final Color _primaryOrange = const Color(0xFFFFB68C);
  final Color _textBrown = const Color(0xFF4A3225);
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _inputFillColor = const Color(0xFFEEEEEE);

  // --- REPOSITORIES ---
  final ChefManagementRepository _chefRepo = ChefManagementRepository(); 
  final AuthRepository _authRepo = AuthRepository();

  final CustomerProfileRepository _customerRepo = CustomerProfileRepository(); 
  final ChefProfileRepository _chefProfileRepo = ChefProfileRepository();
  
  // --- STATE QUẢN LÝ LUỒNG ---
  bool _isLoadingRole = true;
  bool _isChef = false; // User có phải là Chef không?
  bool _isChefMode = false; // CÔNG TẮC: Đang xem ở chế độ Chef hay Customer?
  
  String _currentToken = "";
  int _currentUserId = 0;

  // --- DỮ LIỆU HIỂN THỊ (Sẽ được đắp bằng API) ---
  String _customerName = "Loading...";
  String _customerAvatar = "";
  String _chefName = "Loading...";
  String _chefAvatar = "";

  @override
  void initState() {
    super.initState();
    _loadUserData(); 
  }

  Future<void> _loadUserData() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final token = prefs.getString("token") ?? ''; 
      final userId = prefs.getInt("user_id") ?? 0;        

      // 1. Kiểm tra User có role Chef hay không
      final isChef = await _chefRepo.checkIsChef();

      // 2. TECH LEAD TODO: Gọi API lấy thông tin Customer ở đây
      final customerProfile = await _customerRepo.getCustomerProfile();
      
      // 3. TECH LEAD TODO: Nếu isChef = true, gọi API lấy thông tin Chef
      final chefProfile = isChef ? await _chefProfileRepo.getChefProfile(userId) : null;

      if (!mounted) return;

      setState(() {
        _currentToken = token;
        _currentUserId = userId;
        _isChef = isChef;
        
        // MOCK DATA: (Thay bằng dữ liệu từ API)
        _customerName = customerProfile?.fullname ?? "Customer Name"; 
        _customerAvatar = customerProfile?.avatar ?? "https://i.pravatar.cc/150?img=11"; 
        
        if (isChef) {
          _chefName = chefProfile?.fullname ?? "Chef Name"; 
          _chefAvatar = chefProfile?.avatar ?? "https://i.pravatar.cc/150?img=12"; 
          // Mặc định bật Chef Mode nếu muốn, hoặc cứ để false để họ vào app với tư cách người đi mua đồ ăn trước.
          // _isChefMode = true; 
        }

        _isLoadingRole = false; 
      });
      
    } catch (e) {
      debugPrint("🚨 Lỗi load data Profile: $e");
      if (mounted) setState(() => _isLoadingRole = false);
    }
  }

  Future<void> _handleLogout() async {
    try {
      String? fcmToken;
      if (kIsWeb) {
        fcmToken = null; 
      } else {
        fcmToken = await FirebaseMessaging.instance.getToken();
      }

      final prefs = await SharedPreferences.getInstance();
      final jwtToken = prefs.getString("token"); 
      final urlBase = ApiConstants.baseUrl; 

      if (fcmToken != null && jwtToken != null) {
        http.post(
          Uri.parse('$urlBase/api/mongo-chat/device/unregister/'), 
          headers: {
            'Authorization': 'Bearer $jwtToken',
            'Content-Type': 'application/json',
          },
          body: jsonEncode({"fcm_token": fcmToken}),
        ).then((response) {
          debugPrint("✅ Unregister API Status: ${response.statusCode}");
        }).catchError((e) {
          debugPrint("🚨 Lỗi API Unregister ngầm: $e");
        });
      }

      await _authRepo.logout(); 

      if (!mounted) return;
      Navigator.pushReplacementNamed(context, "/login");
      
    } catch (e) {
      showAppSnackBar(context, 'Logout failed: $e', type: SnackBarType.error);
      debugPrint('🚨 Logout failed: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        backgroundColor: Colors.white,
        elevation: 0,
        centerTitle: true,
        title: Text(
          _isChefMode ? "Chef Dashboard" : "Profile", // Thay đổi Title theo Mode
          style: TextStyle(
            color: _textBrown, 
            fontSize: 20, 
            fontWeight: FontWeight.bold
          ),
        ),
      ),
      body: _isLoadingRole 
        ? const Center(child: CircularProgressIndicator())
        : SingleChildScrollView(
        child: Column(
          children: [
            const SizedBox(height: 20),
            
            // ===== 1. AVATAR & INFO SECTION (Tự động đổi theo Mode) =====
            _buildAvatarSection(),
            
            const SizedBox(height: 30),

            // ===== 2. MENU ITEMS =====
            
            // 2.1 Các Menu DÙNG CHUNG (Mode nào cũng thấy)
            _buildMenuOption(
              icon: Icons.chat_bubble_outline, 
              title: "Messages", 
              onTap: _navigateToMessages,
            ),
            _buildMenuOption(
              icon: Icons.notifications_none, 
              title: "Notification", 
              onTap: _navigateToNotifications,
            ),

            // 2.2 Các Menu CỦA RIÊNG KHÁCH HÀNG (CUSTOMER)
            if (!_isChefMode) ...[
              _buildMenuOption(
                icon: Icons.person_outline, 
                title: "Edit Profile", 
                onTap: _navigateToEditProfile,
              ),
              _buildMenuOption(
                icon: Icons.storefront_outlined, 
                title: "My Wallet",
                onTap: () {
                  if (_currentToken.isEmpty) return;
                  Navigator.push(
                    context,
                    MaterialPageRoute(
                      builder: (_) => CustomerWalletPage(),
                    ),
                  );
                }
              ),
              _buildMenuOption(
                icon: Icons.star_border, 
                title: "My Reviews", 
                onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const MyReviewsPage())),
              ),
              _buildMenuOption(
                icon: Icons.person_outline, 
                title: "Nutrition Profile", 
                onTap: _navigateToNutritionProfile,
              ),
              _buildMenuOption(
                icon: Icons.person_outline, 
                title: "Allergy Profile", 
                onTap: _navigateToAllergyProfile,
              ),
              _buildMenuOption(
                icon: Icons.location_on_outlined, 
                title: "Shipping Address", 
                onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const ShippingAddressPage())),
              ),
              _buildMenuOption(
                icon: Icons.lock_outline, 
                title: "Change Password", 
                onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const ChangePasswordPage())),
              ),
            ],

            // 2.3 Các Menu CỦA RIÊNG ĐẦU BẾP (CHEF)
            if (_isChefMode) ..._buildChefMenuOptions(),

            // 2.4 Nút đăng ký làm Chef (Chỉ hiện nếu chưa từng đăng ký)
            if (!_isChef)
              _buildMenuOption(
                icon: Icons.restaurant_menu,
                title: "Become a Chef",
                onTap: _navigateToRegister,
              ),

            const SizedBox(height: 40),

            // ===== 3. SIGN OUT BUTTON =====
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24.0),
              child: SizedBox(
                width: double.infinity,
                height: 50,
                child: ElevatedButton.icon(
                  style: ElevatedButton.styleFrom(
                    backgroundColor: _primaryRed, 
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(10),
                    ),
                    elevation: 0,
                  ),
                  onPressed: _handleLogout,
                  icon: const Icon(Icons.logout, color: Colors.white),
                  label: const Text(
                    "Sign Out", 
                    style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Colors.white)
                  ),
                ),
              ),
            ),
            
            const SizedBox(height: 40),
          ],
        ),
      ),
    );
  }

  // --- WIDGET BUILDERS ---

  Widget _buildAvatarSection() {
    final displayAvatar = _isChefMode ? _chefAvatar : _customerAvatar;
    final displayName = _isChefMode ? _chefName : _customerName;
    final displayRole = _isChefMode ? "Chef / Store" : "Customer";

    return Column(
      children: [
        Stack(
          children: [
            // Ảnh đại diện
            Container(
              width: 110,
              height: 110,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: _inputFillColor,
                border: Border.all(color: _primaryOrange, width: 2),
                image: displayAvatar.isNotEmpty 
                    ? DecorationImage(image: NetworkImage(displayAvatar), fit: BoxFit.cover)
                    : null,
              ),
              child: displayAvatar.isEmpty 
                  ? const Icon(Icons.person, size: 50, color: Colors.grey)
                  : null,
            ),
            
            // Nút Edit Avatar (Chỉ cho phép Edit nhanh khi ở Customer Mode)
            if (!_isChefMode)
              Positioned(
                bottom: 0,
                right: 0,
                child: GestureDetector(
                  onTap: _navigateToEditProfile,
                  child: Container(
                    padding: const EdgeInsets.all(6),
                    decoration: BoxDecoration(
                      color: _primaryRed, 
                      shape: BoxShape.circle,
                      border: Border.all(color: Colors.white, width: 2),
                    ),
                    child: const Icon(Icons.edit, color: Colors.white, size: 16),
                  ),
                ),
              )
          ],
        ),
        const SizedBox(height: 16),
        Text(
          displayName, 
          style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold, color: _textBrown),
        ),
        const SizedBox(height: 4),
        Text(
          displayRole, 
          style: const TextStyle(fontSize: 15, color: Colors.grey),
        ),

        // Nút lật công tắc (Chỉ hiện khi User thực sự có Profile Chef)
        if (_isChef) ...[
          const SizedBox(height: 16),
          OutlinedButton.icon(
            style: OutlinedButton.styleFrom(
              foregroundColor: _primaryRed,
              side: BorderSide(color: _primaryRed),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
            ),
            icon: Icon(_isChefMode ? Icons.person_outline : Icons.storefront, size: 18),
            label: Text(_isChefMode ? "Switch to Buyer Mode" : "Switch to Chef Mode"),
            onPressed: () {
              setState(() {
                _isChefMode = !_isChefMode; 
              });
            },
          )
        ]
      ],
    );
  }

  Widget _buildMenuOption({
    required IconData icon, 
    required String title, 
    required VoidCallback onTap
  }) {
    return ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 24, vertical: 4),
      leading: Container(
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: _primaryRed.withOpacity(0.1), 
          shape: BoxShape.circle,
        ),
        child: Icon(icon, color: _primaryRed, size: 22), 
      ),
      title: Text(
        title, 
        style: TextStyle(fontWeight: FontWeight.w600, fontSize: 16, color: _textBrown) 
      ),
      trailing: const Icon(Icons.arrow_forward_ios, size: 16, color: Colors.black54),
      onTap: onTap,
    );
  }

  List<Widget> _buildChefMenuOptions() {
    return [
      _buildMenuOption(
        icon: Icons.analytics_outlined,
        title: "AI Analytics Dashboard",
        onTap: () {
          if (_currentToken.isEmpty) return;
          Navigator.push(
            context,
            MaterialPageRoute(builder: (_) => ChefAnalyticsPage(token: _currentToken)),
          );
        }
      ),
      _buildMenuOption(
        icon: Icons.storefront_outlined, 
        title: "Chef Profile Settings",
        onTap: () {
          if (_currentToken.isEmpty) return;
          Navigator.push(
            context,
            MaterialPageRoute(
              builder: (_) => EditChefProfilePage(token: _currentToken, chefId: _currentUserId),
            ),
          );
        }
      ),
      _buildMenuOption(
        icon: Icons.storefront_outlined, 
        title: "Chef Wallet",
        onTap: () {
          if (_currentToken.isEmpty) return;
          Navigator.push(
            context,
            MaterialPageRoute(
              builder: (_) => ChefWalletPage(chefId: _currentUserId),
            ),
          );
        }
      ),
    ];
  }

  // --- NAVIGATION LOGIC ---

  void _navigateToEditProfile() async {
    if (_currentToken.isEmpty || _currentUserId == 0) {
      showAppSnackBar(context, 'Please log in again to edit profile', type: SnackBarType.warning);
      return;
    }
    
    // Đợi kết quả từ trang Edit trả về (Áp dụng Cách 1)
    final result = await Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => EditProfilePage(token: _currentToken),
      ),
    );

    // TECH LEAD FIX: Chỉ gán lại dữ liệu nếu Update thành công, TUYỆT ĐỐI KHÔNG ÉP _isChef = true ở đây nữa
    if (result == true) {
      // Reload lại dữ liệu từ Server/Cache để cập nhật UI
      _loadUserData(); 
    }
  }

  void _navigateToNutritionProfile() async {
    if (_currentToken.isEmpty || _currentUserId == 0) {
      showAppSnackBar(context, 'Please log in again to view nutrition profile', type: SnackBarType.warning);
      return;
    }
    
    // Đợi kết quả từ trang Edit trả về (Áp dụng Cách 1)
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => NutritionPage(),
      ),
    );

  }

  void _navigateToAllergyProfile() async {
    if (_currentToken.isEmpty || _currentUserId == 0) {
      showAppSnackBar(context, 'Please log in again to view allergy profile', type: SnackBarType.warning);
      return;
    }
    
    // Đợi kết quả từ trang Edit trả về (Áp dụng Cách 1)
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => AllergyProfilePage(),
      ),
    );

  }

  void _navigateToRegister() async {
    if (_currentToken.isEmpty || _currentUserId == 0) {
      showAppSnackBar(context, 'Please log in again to register', type: SnackBarType.warning);
      return;
    }
    final result = await Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => ChefRegistrationPage(token: _currentToken),
      ),
    );
    
    // Nếu đăng ký làm Chef thành công
    if (result == true) {
      setState(() {
        _isChef = true;
        _isChefMode = true; // Đăng ký xong thì ném thẳng họ sang Mode Chef cho xịn
      });
      _loadUserData(); // Load lại để lấy Chef Profile
    }
  }

  void _navigateToMessages() {
    if (_currentToken.isEmpty || _currentUserId == 0) {
      showAppSnackBar(context, 'Please log in again to check the message', type: SnackBarType.warning);
      return;
    }
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => ConversationListPage(
          token: _currentToken,      
          myUserId: _currentUserId, 
        ),
      ),
    );
  }

  void _navigateToNotifications() {
    if (_currentToken.isEmpty || _currentUserId == 0) {
      showAppSnackBar(context, 'Please log in again to check the notifications', type: SnackBarType.warning);
      return;
    }
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => NotificationPage(),
      ),
    );
  }
}
