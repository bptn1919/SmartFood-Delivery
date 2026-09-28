import 'package:flutter/material.dart';
import 'package:testing/features/cart/logic/cart_utils.dart';
import 'package:testing/features/chat/presentations/chat_detail_page.dart';
import 'package:testing/features/chat/service/chat_service.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:testing/features/home/sections/chef_certificate_section.dart';
import 'package:intl/intl.dart';

// --- Core & Config ---
import '../../../core/utils/image_helper.dart';

// --- Repositories ---
import '../repositories/dish_repository.dart';
import '../repositories/menu_repository.dart';
import '../../checkout/repositories/chef_repository.dart';

// --- Models ---
import '../models/dish_model.dart';
import '../models/menu_model.dart';
import '../../checkout/models/chef_model.dart';

// --- Common UI ---
import '../../common/cart_icon.dart';
import 'dish_detail_page.dart';
import '../../common/app_components.dart';
import '../../report/presentations/create_report_bottom_sheet.dart';

class MenuSection {
  final MenuModel menu;
  final List<DishModel> dishes;
  MenuSection(this.menu, this.dishes);
}

class DetailChefPage extends StatefulWidget {
  final String chefId;

  const DetailChefPage({super.key, required this.chefId});

  @override
  State<DetailChefPage> createState() => _DetailChefPageState();
}

class _DetailChefPageState extends State<DetailChefPage> {
  final DishRepository _dishRepo = DishRepository();
  final MenuRepository _menuRepo = MenuRepository();
  final ChefRepository _chefRepo = ChefRepository();
  final ChatService _chatService = ChatService();

  late Future<ChefModel?> _chefFuture;
  late Future<List<MenuSection>> _menuSectionsFuture;

  bool _isCreatingChat = false;
  String _currentToken = "";
  int _currentUserId = 0;

  // Styling colors matching the pattern
  final Color _primaryOrange = const Color(0xFFFFBB94);
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _primaryDark = const Color(0xFF2D3436);

  @override
  void initState() {
    super.initState();
    _loadData();
    _loadUserData();
  }

  Future<void> _loadUserData() async {
    try {
      // 1. Lấy thông tin đăng nhập từ Local Storage
      final prefs = await SharedPreferences.getInstance();
      final token = prefs.getString("token") ??
          ''; // Thay đổi key tùy theo project của bạn
      final userId =
          prefs.getInt("user_id") ?? 0; // Thay đổi key tùy theo project của bạn

      if (!mounted) return;

      // 3. Cập nhật giao diện
      setState(() {
        _currentToken = token;
        _currentUserId = userId;
      });

      debugPrint("🔍 ID = $_currentUserId");
    } catch (e) {
      debugPrint("🚨 Lỗi load data Profile: $e");
    }
  }

  Future<void> _startChatWithChef(String chefName, int partnerUserId) async {
    setState(() => _isCreatingChat = true);

    try {
      // Token và ID User của bạn đang đăng nhập
      final token = _currentToken;
      final myUserId = _currentUserId;

      debugPrint("🚨 CHECK TOKEN TRƯỚC KHI GỌI API: '$token'");

      // 👇 2. GỌI XUỐNG REPOSITORY Ở ĐÂY 👇
      final String? roomId = await _chatService.getOrCreateRoom(
        token: token,
        partnerId: partnerUserId,
      );

      if (!mounted) return;
      setState(() => _isCreatingChat = false);

      // 3. Xử lý kết quả
      if (roomId != null && roomId.isNotEmpty) {
        Navigator.push(
          context,
          MaterialPageRoute(
              builder: (context) => ChatDetailPage(
                    roomId: roomId,
                    senderName: chefName,
                    token: token,
                    myUserId: myUserId,
                    partnerUserId: partnerUserId,
                  )),
        );
      } else {
        // Repository trả về null (lỗi mạng, server...)
        showAppSnackBar(context, 'Không thể kết nối đến máy chủ chat!',
            type: SnackBarType.error);
      }
    } catch (e) {
      if (!mounted) return;
      setState(() => _isCreatingChat = false);
      showAppSnackBar(context, 'System error: $e', type: SnackBarType.error);
    }
  }

  Future<void> _showReportSheet() async {
    final chefId = int.tryParse(widget.chefId);
    if (chefId == null) {
      showAppSnackBar(
        context,
        'Cannot report this chef right now.',
        type: SnackBarType.error,
      );
      return;
    }

    final submitted = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (_) => CreateReportBottomSheet(chefId: chefId),
    );

    if (!mounted || submitted != true) return;
    showAppSnackBar(
      context,
      'Report submitted successfully.',
      type: SnackBarType.success,
    );
  }

  void _loadData() {
    _chefFuture = _chefRepo.getChefDetail(widget.chefId);
    _menuSectionsFuture = _loadMenusAndDishes();
  }

  Future<List<MenuSection>> _loadMenusAndDishes() async {
    try {
      final menus = await _menuRepo.getMenusOfChef(widget.chefId);
      if (menus.isEmpty) return [];

      final futures = menus.map((menu) async {
        try {
          final dishes = await _dishRepo.getDishesInMenu(menu.uid);
          return MenuSection(menu, dishes);
        } catch (e) {
          return MenuSection(menu, []);
        }
      });
      return Future.wait(futures);
    } catch (e) {
      rethrow;
    }
  }

  Widget _buildChatButton(String chefName, int partnerUserId) {
    return InkWell(
      onTap: _isCreatingChat
          ? null
          : () => _startChatWithChef(chefName, partnerUserId),
      borderRadius: BorderRadius.circular(50),
      child: Container(
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: _primaryRed.withValues(alpha: 0.1),
          shape: BoxShape.circle,
        ),
        child: _isCreatingChat
            ? SizedBox(
                width: 24,
                height: 24,
                child: CircularProgressIndicator(
                    strokeWidth: 2, color: _primaryRed))
            : Icon(Icons.chat_bubble_outline, color: _primaryRed, size: 24),
      ),
    );
  }

  // ===========================================================================
  // MAIN UI BUILD
  // ===========================================================================

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _primaryOrange,
      body: FutureBuilder<ChefModel?>(
        future: _chefFuture,
        builder: (context, chefSnap) {
          if (chefSnap.connectionState == ConnectionState.waiting) {
            return const Center(
              child: CircularProgressIndicator(
                valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
              ),
            );
          }

          if (chefSnap.hasError) {
            return Center(
              child: Container(
                padding: const EdgeInsets.all(20),
                margin: const EdgeInsets.all(20),
                decoration: BoxDecoration(
                  color: Colors.white,
                  borderRadius: BorderRadius.circular(20),
                ),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(Icons.error_outline, size: 48, color: _primaryRed),
                    const SizedBox(height: 16),
                    Text('Error: ${chefSnap.error}'),
                    const SizedBox(height: 16),
                    ElevatedButton(
                      onPressed: () {
                        setState(() {
                          _loadData();
                        });
                      },
                      style: ElevatedButton.styleFrom(
                        backgroundColor: _primaryRed,
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(20),
                        ),
                      ),
                      child: const Text('Retry'),
                    ),
                  ],
                ),
              ),
            );
          }

          final chef = chefSnap.data;

          if (chef == null) {
            return const Center(
              child: Text(
                'Chef not found',
                style: TextStyle(color: Colors.white),
              ),
            );
          }

          final chefName =
              chef.fullname.isNotEmpty ? chef.fullname : "Unknown Chef";
          final chefAvatar =
              chef.imageUrl.isNotEmpty ? chef.imageUrl : "assets/images/d1.jpg";
          final chefRating = chef.rating;
          final chefOrders = chef.numberOfOrders;
          final chefBio = chef.bio;

          return Column(
            children: [
              // ===== ENHANCED HEADER =====
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 55, 16, 20),
                child: Row(
                  children: [
                    // Enhanced Back button with circular background
                    IconButton(
                      icon: const Icon(Icons.chevron_left,
                          color: Colors.black, size: 30),
                      onPressed: () => Navigator.of(context).pop(),
                    ),

                    // Title with shadow
                    Expanded(
                      child: Text(
                        chefName,
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 24,
                          fontWeight: FontWeight.bold,
                          shadows: [
                            Shadow(
                              blurRadius: 10,
                              color: Colors.black26,
                              offset: Offset(0, 2),
                            ),
                          ],
                        ),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),

                    PopupMenuButton<String>(
                      icon: const Icon(Icons.more_vert, color: Colors.black),
                      onSelected: (value) {
                        if (value == 'report') {
                          _showReportSheet();
                        }
                      },
                      itemBuilder: (context) => const [
                        PopupMenuItem(
                          value: 'report',
                          child: Row(
                            children: [
                              Icon(Icons.flag_outlined, size: 18),
                              SizedBox(width: 10),
                              Text('Report chef'),
                            ],
                          ),
                        ),
                      ],
                    ),

                    // Cart icon without background
                    const CartIconButton(),
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
                      topLeft: Radius.circular(35),
                      topRight: Radius.circular(35),
                    ),
                  ),
                  child: SingleChildScrollView(
                    physics: const BouncingScrollPhysics(),
                    padding: const EdgeInsets.fromLTRB(20, 20, 20, 40),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        // A. Enhanced Hero Image
                        _buildHeroImage(chefAvatar),

                        const SizedBox(height: 20),

                        // B. Enhanced Chef Info Section
                        _buildChefInfo(
                            chefName, chef.email, chef.phone, chef.userId),

                        const SizedBox(height: 20),

                        // C. Enhanced Stats Row
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            _buildEnhancedStatCard(
                              Icons.star_rounded,
                              chefRating.toStringAsFixed(1),
                              chefRating > 0
                                  ? "${(chefRating * 50).toInt()}+ reviews"
                                  : "No reviews",
                              chefRating >= 4.5 ? Colors.amber : Colors.grey,
                            ),
                            const SizedBox(width: 12),
                            _buildEnhancedStatCard(
                              Icons.restaurant_menu_rounded,
                              chefOrders.toString(),
                              chefOrders > 0 ? "Orders completed" : "No orders",
                              _primaryOrange,
                            ),
                            const SizedBox(width: 12),
                            // _buildEnhancedStatCard(
                            //   Icons.verified_rounded,
                            //   "Certified",
                            //   "Food safety",
                            //   _primaryRed,
                            // ),

                            ChefCertificationCard(
                                isCertified:
                                    true), //chef.isFoodSafetyCertified),
                          ],
                        ),

                        const SizedBox(height: 24),

                        // D. Enhanced Bio Section
                        if (chefBio.isNotEmpty) _buildBioSection(chefBio),

                        const SizedBox(height: 24),

                        // E. Stylish Divider
                        Container(
                          height: 1,
                          color: Colors.grey[100],
                        ),

                        const SizedBox(height: 24),

                        // F. Menu List
                        _buildMenuAndDishesList(),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          );
        },
      ),
    );
  }

  // --- ENHANCED HERO IMAGE ---
  Widget _buildHeroImage(String imageUrl) {
    return Center(
      child: SizedBox(
        height: 240,
        width: double.infinity,
        child: Stack(
          alignment: Alignment.bottomCenter,
          children: [
            // Main image with gradient overlay
            ClipRRect(
              borderRadius: BorderRadius.circular(25),
              child: Stack(
                children: [
                  imageUrl.startsWith('http')
                      ? Image.network(
                          imageUrl,
                          height: 240,
                          width: double.infinity,
                          cacheWidth:
                              (MediaQuery.sizeOf(context).width * 2).round(),
                          cacheHeight: 480,
                          fit: BoxFit.cover,
                          errorBuilder: (_, __, ___) => Image.asset(
                            "assets/images/d1.jpg",
                            height: 240,
                            width: double.infinity,
                            fit: BoxFit.cover,
                          ),
                        )
                      : Image.asset(
                          "assets/images/d1.jpg",
                          height: 240,
                          width: double.infinity,
                          fit: BoxFit.cover,
                        ),
                  Container(
                    height: 240,
                    decoration: BoxDecoration(
                      gradient: LinearGradient(
                        begin: Alignment.topCenter,
                        end: Alignment.bottomCenter,
                        colors: [
                          Colors.transparent,
                          Colors.black.withValues(alpha: 0.3),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
            // Enhanced avatar with border and shadow
            Positioned(
              right: 20,
              bottom: -30,
              child: Container(
                padding: const EdgeInsets.all(4),
                decoration: BoxDecoration(
                  color: Colors.white,
                  shape: BoxShape.circle,
                  boxShadow: [
                    BoxShadow(
                      color: Colors.black.withValues(alpha: 0.2),
                      blurRadius: 15,
                      offset: const Offset(0, 5),
                    ),
                  ],
                ),
                child: CircleAvatar(
                  radius: 40,
                  backgroundColor: Colors.white,
                  backgroundImage: imageUrl.startsWith('http')
                      ? NetworkImage(imageUrl) as ImageProvider
                      : const AssetImage("assets/images/d1.jpg"),
                ),
              ),
            )
          ],
        ),
      ),
    );
  }

  // --- ENHANCED CHEF INFO SECTION ---
  Widget _buildChefInfo(
      String name, String? email, String? phone, int partnerUserId) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFFF7F7F7),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: _primaryOrange.withValues(alpha: 0.2),
                  borderRadius: BorderRadius.circular(12),
                ),
                child:
                    Icon(Icons.person_outline, color: _primaryOrange, size: 20),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  name,
                  style: const TextStyle(
                    fontSize: 20,
                    fontWeight: FontWeight.bold,
                    color: Color(0xFF2D3436),
                  ),
                ),
              ),
              _buildChatButton(name, partnerUserId),
            ],
          ),
          if (email != null && email.isNotEmpty) ...[
            const SizedBox(height: 12),
            Row(
              children: [
                Icon(Icons.email_outlined, color: Colors.grey[500], size: 16),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    email,
                    style: TextStyle(color: Colors.grey[600], fontSize: 14),
                  ),
                ),
              ],
            ),
          ],
          if (phone != null && phone.isNotEmpty) ...[
            const SizedBox(height: 8),
            Row(
              children: [
                Icon(Icons.phone_outlined, color: Colors.grey[500], size: 16),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    phone,
                    style: TextStyle(color: Colors.grey[600], fontSize: 14),
                  ),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }

  // --- ENHANCED BIO SECTION ---
  Widget _buildBioSection(String bio) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            _primaryOrange.withValues(alpha: 0.1),
            _primaryOrange.withValues(alpha: 0.05),
          ],
        ),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.format_quote, color: _primaryOrange, size: 20),
              const SizedBox(width: 8),
              Text(
                "About the Chef",
                style: TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.bold,
                  color: _primaryDark,
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            bio,
            style: TextStyle(
              color: Colors.grey[600],
              height: 1.5,
              fontSize: 14,
            ),
          ),
        ],
      ),
    );
  }

  // --- ENHANCED STAT CARD ---
  Widget _buildEnhancedStatCard(
      IconData icon, String value, String label, Color iconColor) {
    return Expanded(
      child: Container(
        margin: const EdgeInsets.symmetric(horizontal: 2),
        padding: const EdgeInsets.symmetric(vertical: 14),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: Colors.grey.shade100, width: 1),
          boxShadow: [
            BoxShadow(
              color: Colors.grey.withValues(alpha: 0.08),
              blurRadius: 10,
              offset: const Offset(0, 2),
            ),
          ],
        ),
        child: Column(
          children: [
            Icon(icon, size: 24, color: iconColor),
            const SizedBox(height: 6),
            Text(
              value,
              style: const TextStyle(
                fontWeight: FontWeight.bold,
                fontSize: 16,
                color: Color(0xFF2D3436),
              ),
            ),
            const SizedBox(height: 2),
            Text(
              label,
              textAlign: TextAlign.center,
              style: TextStyle(
                color: Colors.grey[500],
                fontSize: 10,
              ),
            ),
          ],
        ),
      ),
    );
  }

  // --- ENHANCED MENU LIST ---
  Widget _buildMenuAndDishesList() {
    return FutureBuilder<List<MenuSection>>(
      future: _menuSectionsFuture,
      builder: (context, snapshot) {
        if (snapshot.connectionState == ConnectionState.waiting) {
          return const Center(
            child: Padding(
              padding: EdgeInsets.all(20),
              child: CircularProgressIndicator(
                valueColor: AlwaysStoppedAnimation<Color>(Color(0xFFE55866)),
              ),
            ),
          );
        }

        if (snapshot.hasError) {
          return Center(
            child: Column(
              children: [
                Icon(Icons.error_outline, color: _primaryRed, size: 48),
                const SizedBox(height: 12),
                Text('Error loading menu: ${snapshot.error}'),
              ],
            ),
          );
        }

        final sections = snapshot.data ?? [];
        if (sections.isEmpty) {
          return Container(
            padding: const EdgeInsets.all(40),
            decoration: BoxDecoration(
              color: const Color(0xFFF7F7F7),
              borderRadius: BorderRadius.circular(20),
            ),
            child: const Center(
              child: Column(
                children: [
                  Icon(Icons.restaurant_menu_outlined,
                      size: 48, color: Colors.grey),
                  SizedBox(height: 12),
                  Text("No menu available",
                      style: TextStyle(color: Colors.grey)),
                ],
              ),
            ),
          );
        }

        return Column(
          children: sections.map((section) {
            return Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (section.menu.name.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 12, top: 8),
                    child: Row(
                      children: [
                        Container(
                          width: 4,
                          height: 20,
                          decoration: BoxDecoration(
                            color: _primaryRed,
                            borderRadius: BorderRadius.circular(2),
                          ),
                        ),
                        const SizedBox(width: 8),
                        Text(
                          section.menu.name,
                          style: const TextStyle(
                            fontWeight: FontWeight.bold,
                            fontSize: 20,
                            color: Color(0xFF2D3436),
                          ),
                        ),
                      ],
                    ),
                  ),
                ...section.dishes.map((dish) => _EnhancedDishItemCard(
                      dish: dish,
                      onAdd: () => CartUtils.showAddToCartModal(context, dish,
                          initialQuantity: 1),
                    )),
                const SizedBox(height: 20),
              ],
            );
          }).toList(),
        );
      },
    );
  }
}

// --- ENHANCED DISH ITEM CARD ---
class _EnhancedDishItemCard extends StatelessWidget {
  final DishModel dish;
  final VoidCallback onAdd;

  const _EnhancedDishItemCard({required this.dish, required this.onAdd});

  void _navigateToDetail(BuildContext context) {
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (context) => DishDetailPage(
          dishId: dish.uid,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final imgUrl = ImageHelper.getValidUrl(dish.imageUrl);
    final currencyFormatter = NumberFormat.currency(
      locale: 'vi_VN',
      symbol: 'đ',
      decimalDigits: 0,
    );
    return Container(
      margin: const EdgeInsets.only(bottom: 16),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(20),
        boxShadow: [
          BoxShadow(
            color: Colors.grey.withValues(alpha: 0.08),
            blurRadius: 12,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Material(
        color: Colors
            .transparent, // Phải để transparent để không che mất màu/shadow của Container
        child: InkWell(
          borderRadius: BorderRadius.circular(
              20), // Bo góc hiệu ứng chạm khớp với Container
          onTap: () => _navigateToDetail(context),
          child: Row(
            children: [
              // Enhanced Image with rounded corners
              ClipRRect(
                borderRadius:
                    const BorderRadius.horizontal(left: Radius.circular(20)),
                child: Stack(
                  children: [
                    Image.network(
                      imgUrl,
                      width: 110,
                      height: 110,
                      cacheWidth: 220,
                      fit: BoxFit.cover,
                      errorBuilder: (_, __, ___) => Container(
                        width: 110,
                        height: 110,
                        color: Colors.grey[200],
                        child: Icon(Icons.fastfood,
                            size: 40, color: Colors.grey[400]),
                      ),
                    ),
                  ],
                ),
              ),
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        dish.name,
                        style: const TextStyle(
                          fontWeight: FontWeight.bold,
                          fontSize: 16,
                          color: Color(0xFF2D3436),
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      const SizedBox(height: 4),
                      if (dish.description != null &&
                          dish.description!.isNotEmpty)
                        Text(
                          dish.description!,
                          style: TextStyle(
                            color: Colors.grey[500],
                            fontSize: 12,
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      const SizedBox(height: 8),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          // Enhanced price chip
                          Container(
                            padding: const EdgeInsets.symmetric(
                                horizontal: 8, vertical: 4),
                            decoration: BoxDecoration(
                              color: const Color(0xFFE55866)
                                  .withValues(alpha: 0.1),
                              borderRadius: BorderRadius.circular(12),
                            ),
                            child: Text(
                              currencyFormatter.format(dish.price),
                              style: TextStyle(
                                  fontSize: 15,
                                  color: const Color(0xFFE55866),
                                  fontWeight: FontWeight.bold),
                            ),
                          ),
                          // Enhanced add button
                          GestureDetector(
                            onTap: onAdd,
                            child: Container(
                              padding: const EdgeInsets.all(8),
                              decoration: BoxDecoration(
                                color: const Color(0xFFE55866),
                                borderRadius: BorderRadius.circular(12),
                                boxShadow: [
                                  BoxShadow(
                                    color: const Color(0xFFE55866)
                                        .withValues(alpha: 0.3),
                                    blurRadius: 8,
                                    offset: const Offset(0, 2),
                                  ),
                                ],
                              ),
                              child: const Icon(
                                Icons.add_rounded,
                                color: Colors.white,
                                size: 20,
                              ),
                            ),
                          ),
                        ],
                      )
                    ],
                  ),
                ),
              )
            ],
          ),
        ),
      ),
    );
  }
}
