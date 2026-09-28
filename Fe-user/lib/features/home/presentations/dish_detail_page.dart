import 'package:flutter/material.dart';
import 'package:testing/features/auth/models/user_model.dart';
import 'package:testing/features/checkout/repositories/review_repository.dart';
import 'package:testing/features/checkout/widgets/review_bottom_sheet.dart';
import 'package:testing/features/auth/repositories/auth_repository.dart';
import 'package:testing/features/home/models/dish_ingredients_model.dart';
import 'package:testing/features/home/presentations/full_reviews_page.dart';
import 'package:testing/features/home/sections/dish_nutrition_section.dart';
import 'package:testing/features/home/repositories/dish_repository.dart';
import '../models/dish_model.dart';
import '../../cart/logic/cart_utils.dart';
import '../../common/app_components.dart';
import '../../report/presentations/create_report_bottom_sheet.dart';
import 'package:intl/intl.dart';

class DishDetailPage extends StatefulWidget {
  final String dishId;

  const DishDetailPage({super.key, required this.dishId});

  @override
  State<DishDetailPage> createState() => _DishDetailPageState();
}

class _DishDetailPageState extends State<DishDetailPage> {
  // --- STATE VARIABLES ---
  int _quantity = 1;
  DishModel? _currentDish;
  bool _isLoadingDetail = true;

  // Review State
  bool _isLoadingReviews = true;
  Map<String, dynamic>? _reviewStats;
  List<dynamic> _reviewsList = [];
  int _totalComments = 0;

  // User State
  UserModel? _currentUser;
  bool _isChef = false;

  //Location
  String _locationString = "Loading location...";

  //Ingredients & Nutrition
  DishIngredientsResponse? _nutritionData;
  bool _isLoadingNutrition = true;

  // Repositories
  final reviewRepo = ReviewRepository();
  final authRepo = AuthRepository();
  final dishRepo = DishRepository();

  // Styling
  final Color _primaryOrange = const Color(0xFFFFB68C);
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _textBrown = const Color(0xFF4A3225);

  @override
  void initState() {
    super.initState();
    _initAllData();
  }

  Future<void> _initAllData() async {
    // 1. Load User từ Cache (Rất nhanh, không cần đợi)
    _loadCurrentUser();

    // 2. Gọi đồng thời (Parallel) 3 API vì cả 3 đều chỉ cần widget.dishId
    // Việc này giúp tiết kiệm thời gian chờ đáng kể!
    Future.wait([
      _fetchFullDishDetails(),
      _fetchNutritionData(),
      _fetchReviewData(),
    ]);
  }

  Future<void> _fetchFullDishDetails() async {
    try {
      // 👇 TECH LEAD FIX 3: Dùng thẳng widget.dishId
      final fullDish = await dishRepo.getDishDetail(widget.dishId);
      if (!mounted) return;

      if (fullDish != null) {
        setState(() {
          _currentDish = fullDish;
          _locationString = fullDish.location ?? "Unknown Location";
          _isLoadingDetail = false;
        });
      } else {
        setState(() => _isLoadingDetail = false);
        // Lưu ý: Đảm bảo showAppSnackBar đã được định nghĩa trong app của bạn
        showAppSnackBar(context, 'Cannot find dish details!',
            type: SnackBarType.warning);
      }
    } catch (e) {
      if (!mounted) return;
      setState(() => _isLoadingDetail = false);
      showAppSnackBar(context, 'System failed: $e', type: SnackBarType.error);
    }
  }

  Future<void> _fetchNutritionData() async {
    setState(() => _isLoadingNutrition = true);

    // Sử dụng _currentDish.uid hoặc widget.dish.uid tùy kiến trúc của bạn
    final data = await dishRepo.getDishIngredients(widget.dishId);

    if (mounted) {
      setState(() {
        _nutritionData = data;
        _isLoadingNutrition = false;
      });
    }
  }

  Future<void> _loadCurrentUser() async {
    final user = await authRepo.getUserFromCache();
    setState(() {
      _currentUser = user;
      _isChef = user?.isChef ?? false;
    });
  }

  Future<void> _fetchReviewData() async {
    try {
      final results = await Future.wait([
        reviewRepo.getDishReviewStats(widget.dishId), // Dùng widget.dishId
        reviewRepo.getDishReviews(widget.dishId,
            page: 1, pageSize: 3), // Dùng widget.dishId
      ]);

      if (mounted) {
        setState(() {
          _reviewStats = results[0]?['data'];
          final listResponse = results[1]?['data'];
          if (listResponse != null && listResponse['content'] != null) {
            _reviewsList = listResponse['content'];
            _totalComments = listResponse['total_rows'] ?? 0;
          }
          _isLoadingReviews = false;
        });
      }
    } catch (e) {
      debugPrint("Review fetch error: $e");
      if (mounted) setState(() => _isLoadingReviews = false);
    }
  }

  // ===========================================================================
  // REVIEW & REPLY ACTIONS (Giữ nguyên logic của bạn)
  // ===========================================================================
  Future<void> _confirmDeleteReview(String reviewUid) async {
    final bool? confirm = await showDialog<bool>(
      context: context,
      builder: (BuildContext context) {
        return AlertDialog(
          shape:
              RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
          title: const Text("Delete Review",
              style: TextStyle(fontWeight: FontWeight.bold)),
          content: const Text(
              "Are you sure you want to delete this review? This action cannot be undone."),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text("Cancel", style: TextStyle(color: Colors.grey)),
            ),
            ElevatedButton(
              style: ElevatedButton.styleFrom(backgroundColor: Colors.red),
              onPressed: () => Navigator.pop(context, true),
              child: const Text("Delete",
                  style: TextStyle(
                      color: Colors.white, fontWeight: FontWeight.bold)),
            ),
          ],
        );
      },
    );

    if (confirm == true) {
      showDialog(
          context: context,
          barrierDismissible: false,
          builder: (c) => const Center(
              child: CircularProgressIndicator(color: Colors.red)));

      try {
        await reviewRepo.deleteReview(reviewUid);

        if (mounted) {
          Navigator.pop(context); // Đóng Dialog loading
          showAppSnackBar(
            context,
            'Review deleted successfully!',
            type: SnackBarType.success,
          );
        }

        _fetchReviewData();
      } catch (e) {
        if (mounted) {
          Navigator.pop(context); // Đóng Dialog loading
          showAppSnackBar(
            context,
            'Error: $e',
            type: SnackBarType.error, // Tự động báo màu đỏ
          );
        }
      }
    }
  }

  Future<void> _showEditReviewSheet(Map<String, dynamic> currentReview) async {
    final reviewUid = currentReview['uid'];
    final currentRating = currentReview['rating'] ?? 5;
    final currentComment = currentReview['comment'] ?? '';

    final reviewData = await showModalBottomSheet<Map<String, dynamic>>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (context) => ReviewBottomSheet(
        dishName: _currentDish!.name,
        initialRating: currentRating.toDouble(),
        initialComment: currentComment,
        isEditMode: true,
      ),
    );

    if (reviewData != null) {
      final int newRating = reviewData['rating'];
      final String newComment = reviewData['comment'];

      showDialog(
          context: context,
          barrierDismissible: false,
          builder: (c) => const Center(
              child: CircularProgressIndicator(color: Color(0xFFE84D67))));

      try {
        await reviewRepo.updateReview(
            reviewUid: reviewUid, rating: newRating, comment: newComment);

        if (mounted) {
          Navigator.pop(context);
          showAppSnackBar(
            context,
            'Update successful!',
            type: SnackBarType.success,
          );
        }

        _fetchReviewData();
      } catch (e) {
        if (mounted) {
          Navigator.pop(context);
          showAppSnackBar(
            context,
            'Error: $e',
            type: SnackBarType.error,
          );
        }
      }
    }
  }

  // ===========================================================================
  // REPLY ACTIONS (Người bán - Chef)
  // ===========================================================================

  Future<void> _showReplySheet(Map<String, dynamic> review) async {
    final reviewUid = review['uid'];
    final existingReply = review['reply'];
    final bool hasReply = existingReply != null;
    final String currentContent = hasReply ? existingReply['content'] : '';

    final replyContent = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (context) => _ReplyBottomSheet(
        dishName: _currentDish!.name,
        existingReply: hasReply ? currentContent : null,
      ),
    );

    if (replyContent != null && replyContent.trim().isNotEmpty) {
      showDialog(
        context: context,
        barrierDismissible: false,
        builder: (c) => const Center(
            child: CircularProgressIndicator(color: Color(0xFFE84D67))),
      );

      try {
        if (hasReply) {
          // Update existing reply
          await reviewRepo.updateReviewReply(
              replyUid: existingReply['uid'], content: replyContent);
          if (mounted) {
            showAppSnackBar(
              context,
              'Reply updated!',
              type: SnackBarType.success,
            );
          }
        } else {
          // Create new reply
          await reviewRepo.createReviewReply(
              reviewUid: reviewUid, content: replyContent);
          if (mounted) {
            showAppSnackBar(
              context,
              'Reply added!',
              type: SnackBarType.success,
            );
          }
        }

        if (mounted) Navigator.pop(context);
        _fetchReviewData(); // Refresh để hiển thị reply mới
      } catch (e) {
        if (mounted) {
          Navigator.pop(context);
          showAppSnackBar(
            context,
            'Error: $e',
            type: SnackBarType.error,
          );
        }
      }
    }
  }

  Future<void> _showEditReplySheet(Map<String, dynamic> reply) async {
    final replyUid = reply['uid'];
    final currentContent = reply['content'] ?? '';

    final newContent = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (context) => _ReplyBottomSheet(
        dishName: _currentDish!.name,
        existingReply: currentContent,
      ),
    );

    if (newContent != null &&
        newContent.trim().isNotEmpty &&
        newContent != currentContent) {
      showDialog(
        context: context,
        barrierDismissible: false,
        builder: (c) => const Center(
            child: CircularProgressIndicator(color: Color(0xFFE84D67))),
      );

      try {
        await reviewRepo.updateReviewReply(
            replyUid: replyUid, content: newContent);

        if (mounted) {
          Navigator.pop(context);
          showAppSnackBar(
            context,
            'Reply updated!',
            type: SnackBarType.success,
          );
        }

        _fetchReviewData(); // Refresh
      } catch (e) {
        if (mounted) {
          Navigator.pop(context);
          showAppSnackBar(
            context,
            'Error: $e',
            type: SnackBarType.error,
          );
        }
      }
    }
  }

  Future<void> _deleteReply(String replyUid) async {
    final bool? confirm = await showDialog<bool>(
      context: context,
      builder: (BuildContext context) {
        return AlertDialog(
          shape:
              RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
          title: const Text("Delete Reply",
              style: TextStyle(fontWeight: FontWeight.bold)),
          content: const Text("Are you sure you want to delete this reply?"),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text("Cancel", style: TextStyle(color: Colors.grey)),
            ),
            ElevatedButton(
              style: ElevatedButton.styleFrom(backgroundColor: Colors.red),
              onPressed: () => Navigator.pop(context, true),
              child: const Text("Delete",
                  style: TextStyle(
                      color: Colors.white, fontWeight: FontWeight.bold)),
            ),
          ],
        );
      },
    );

    if (confirm == true) {
      showDialog(
        context: context,
        barrierDismissible: false,
        builder: (c) =>
            const Center(child: CircularProgressIndicator(color: Colors.red)),
      );

      try {
        await reviewRepo.deleteReviewReply(replyUid);

        if (mounted) {
          Navigator.pop(context);
          showAppSnackBar(
            context,
            'Reply deleted!',
            type: SnackBarType.success,
          );
        }

        _fetchReviewData();
      } catch (e) {
        if (mounted) {
          Navigator.pop(context);
          showAppSnackBar(
            context,
            'Error: $e',
            type: SnackBarType.error,
          );
        }
      }
    }
  }

  // ===========================================================================
  // UI BUILDERS (Đã chia nhỏ để gọn nhẹ)
  // ===========================================================================

  void _increment() => setState(() => _quantity++);
  void _decrement() {
    if (_quantity > 1) setState(() => _quantity--);
  }

  Future<void> _showReportSheet() async {
    final submitted = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (_) => CreateReportBottomSheet(dishUid: widget.dishId),
    );

    if (!mounted || submitted != true) return;
    showAppSnackBar(
      context,
      'Report submitted successfully.',
      type: SnackBarType.success,
    );
  }

  @override
  Widget build(BuildContext context) {
    if (_isLoadingDetail) {
      return const Scaffold(
        body: Center(child: CircularProgressIndicator()),
      );
    }

    if (_currentDish == null) {
      return const Scaffold(
        body: Center(child: Text("Dish not found.")),
      );
    }

    return Scaffold(
      backgroundColor: _primaryOrange,
      body: Column(
        children: [
          _buildTopBar(),
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
                physics: const BouncingScrollPhysics(),
                padding: const EdgeInsets.all(24),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _buildCategoriesAndRating(),
                    const SizedBox(height: 12),

                    _buildChefInfo(), // 👈 Skeleton Tên Chef
                    const SizedBox(height: 16),

                    _buildDishImage(),
                    const SizedBox(height: 24),

                    _buildPriceAndStepper(),
                    const SizedBox(height: 12),
                    Divider(
                        color: _primaryRed.withValues(alpha: 0.3),
                        thickness: 1),
                    const SizedBox(height: 16),

                    _buildDishDescription(), // 👈 Skeleton Mô tả
                    const SizedBox(height: 24),

                    _buildAllergensSection(),

                    _buildNutritionSection(),
                    const SizedBox(height: 40),

                    _buildRatingsAndReviewsSection(),
                  ],
                ),
              ),
            ),
          ),
          _buildBottomCartBar(),
        ],
      ),
    );
  }

  // --- SUB-WIDGETS ---

  Widget _buildTopBar() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 60, 16, 20),
      child: Row(
        children: [
          IconButton(
            icon: Icon(Icons.arrow_back_ios_new, color: _primaryRed, size: 20),
            onPressed: () => Navigator.of(context).pop(),
          ),
          const Spacer(),
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
                    Text('Report dish'),
                  ],
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildAllergensSection() {
    // Trích xuất dữ liệu từ Model (Đảm bảo DishModel của bạn đã có 2 trường này)
    final bool hasAllergy = _currentDish!.allergyWarning ?? false;
    final List<dynamic>? allergens = _currentDish!.allergens;

    // Nếu không có dị ứng, trả về SizedBox rỗng để ẩn hoàn toàn section này
    if (!hasAllergy) {
      return const SizedBox.shrink();
    }

    // Xử lý chuỗi tên các chất dị ứng
    final String allergenText = (allergens != null && allergens.isNotEmpty)
        ? allergens.join(
            ', ') // Nối mảng ['Đậu phộng', 'Tôm'] thành chuỗi "Đậu phộng, Tôm"
        : "ingredients you are allergic to";

    return Container(
      margin:
          const EdgeInsets.only(bottom: 24), // Margin dưới thay cho SizedBox
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: _primaryRed.withOpacity(0.08), // Nền đỏ nhạt cảnh báo
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: _primaryRed.withOpacity(0.3)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.warning_amber_rounded, color: _primaryRed, size: 26),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  "Allergen Warning",
                  style: TextStyle(
                    color: _primaryRed,
                    fontWeight: FontWeight.bold,
                    fontSize: 16,
                  ),
                ),
                const SizedBox(height: 6),
                Text.rich(
                  TextSpan(
                    style: TextStyle(
                      color: Colors.red[900],
                      fontSize: 14,
                      height: 1.4,
                    ),
                    children: [
                      const TextSpan(text: "This dish contains: "),
                      TextSpan(
                        text: allergenText,
                        style: TextStyle(
                          fontWeight: FontWeight.bold, // Bôi đậm
                          color:
                              _primaryRed, // Dùng màu đỏ chủ đạo cho nổi bần bật
                          decoration: TextDecoration
                              .underline, // Gạch chân thêm cho chắc chắn
                        ),
                      ),
                      const TextSpan(
                          text: ". Please consider carefully before ordering."),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildCategoriesAndRating() {
    final double avgRating = (_reviewStats?['avg_rating'] ?? 0.0).toDouble();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        //Text("Vietnam . Asian . Southeast Asian", style: TextStyle(fontSize: 14, fontWeight: FontWeight.bold, color: _textBrown)),
        Row(
          children: [
            // Thêm một icon location nhỏ màu đỏ thương hiệu để UI trông "Pro" hơn

            const SizedBox(width: 6),
            Expanded(
              child: Text(
                _locationString, // Giá trị "Europe . West Europe . France" đã fetch được
                style: TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.bold,
                  color: _textBrown,
                ),
                // Chống tràn chữ khi tên địa danh quá dài
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ],
        ),
        const SizedBox(height: 8),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
          decoration: BoxDecoration(
              color: _primaryRed.withValues(alpha: 0.8),
              borderRadius: BorderRadius.circular(6)),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(avgRating > 0 ? avgRating.toStringAsFixed(1) : "N/A",
                  style: const TextStyle(
                      color: Colors.white,
                      fontSize: 12,
                      fontWeight: FontWeight.bold)),
              const SizedBox(width: 4),
              const Icon(Icons.star, color: Colors.yellow, size: 12),
            ],
          ),
        ),
      ],
    );
  }

  // SKELETON: CHEF INFO
  Widget _buildChefInfo() {
    if (_isLoadingDetail) {
      return _buildSkeletonBox(width: 120, height: 16);
    }
    return Text("by ${_currentDish!.chefName ?? 'Unknown Chef'}",
        style: TextStyle(fontSize: 14, color: Colors.grey[700]));
  }

  Widget _buildDishImage() {
    final imageUrl =
        _currentDish!.imageUrl ?? "https://via.placeholder.com/300";
    return Center(
      child: ClipRRect(
        borderRadius: BorderRadius.circular(24),
        child: Image.network(
          imageUrl,
          height: 200,
          width: double.infinity,
          cacheWidth: 800,
          fit: BoxFit.cover,
          errorBuilder: (_, __, ___) => Container(
            height: 200,
            color: Colors.grey[200],
            child: const Center(
                child: Icon(Icons.broken_image, color: Colors.grey)),
          ),
        ),
      ),
    );
  }

  Widget _buildPriceAndStepper() {
    final price = _currentDish!.price;
    final totalPrice = price * _quantity; // Tính tổng tiền theo số lượng

    // Khởi tạo format tiền tệ chuẩn Việt Nam (Không lấy số thập phân)
    final currencyFormatter = NumberFormat.currency(
      locale: 'vi_VN',
      symbol: 'đ',
      decimalDigits: 0,
    );

    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(
          // 👇 Cập nhật chỗ này: Dùng currencyFormatter để format tổng tiền
          currencyFormatter.format(totalPrice),
          style: TextStyle(
              fontSize: 26, color: _primaryRed, fontWeight: FontWeight.bold),
        ),
        Row(
          children: [
            _buildQtyBtn(Icons.remove, _decrement, isMinus: true),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Text("$_quantity",
                  style: TextStyle(
                      fontSize: 20,
                      fontWeight: FontWeight.bold,
                      color: _textBrown)),
            ),
            _buildQtyBtn(Icons.add, _increment, isMinus: false),
          ],
        )
      ],
    );
  }

  // SKELETON: DISH DESCRIPTION
  Widget _buildDishDescription() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(_currentDish!.name,
            style: TextStyle(
                fontSize: 20, fontWeight: FontWeight.bold, color: _textBrown)),
        const SizedBox(height: 8),
        if (_isLoadingDetail) ...[
          _buildSkeletonBox(width: double.infinity, height: 14),
          const SizedBox(height: 6),
          _buildSkeletonBox(width: 250, height: 14),
          const SizedBox(height: 6),
          _buildSkeletonBox(width: 150, height: 14),
        ] else
          Text(
            _currentDish!.description ?? "No description available.",
            style:
                TextStyle(color: Colors.grey[600], height: 1.4, fontSize: 13),
          ),
      ],
    );
  }

  Widget _buildNutritionSection() {
    if (_isLoadingNutrition) {
      return const Padding(
        padding: const EdgeInsets.symmetric(vertical: 32),
        child:
            Center(child: CircularProgressIndicator(color: Color(0xFFE55866))),
      );
    }

    // Xử lý logic từ Backend: Nếu mảng ingredients rỗng hoặc không có nutrition_total
    if (_nutritionData == null ||
        _nutritionData!.nutritionTotal == null ||
        _nutritionData!.ingredients.isEmpty) {
      return Container(
        margin: const EdgeInsets.symmetric(vertical: 16),
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: Colors.grey.shade100,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          children: [
            Icon(Icons.info_outline, color: Colors.grey.shade500),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                _nutritionData?.note ??
                    "Chưa có thông tin dinh dưỡng cho món ăn này.",
                style: TextStyle(color: Colors.grey.shade600, fontSize: 14),
              ),
            ),
          ],
        ),
      );
    }

    // 👇 TECH LEAD FIX: Gọi UI xịn xò ngày hôm qua, truyền data thật vào!
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        DishNutritionSection(
          nutritionTotal: _nutritionData!.nutritionTotal!,
          ingredients: _nutritionData!.ingredients,
        ),

        // Thêm một dòng nhỏ hiển thị độ tin cậy (Confidence) nếu Backend có trả về
        if (_nutritionData!.confidenceText != null)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: Row(
              children: [
                Icon(Icons.verified_user_outlined,
                    size: 14, color: Colors.green.shade600),
                const SizedBox(width: 4),
                Text(
                  _nutritionData!.confidenceText!,
                  style: TextStyle(
                      fontSize: 12,
                      color: Colors.green.shade700,
                      fontWeight: FontWeight.w500),
                ),
              ],
            ),
          )
      ],
    );
  }

  Widget _buildBottomCartBar() {
    return Container(
      color: Colors.white,
      padding: const EdgeInsets.only(top: 12, bottom: 20, left: 24, right: 24),
      child: SizedBox(
        height: 50,
        width: double.infinity,
        child: ElevatedButton.icon(
          style: ElevatedButton.styleFrom(
            backgroundColor: _primaryRed,
            shape:
                RoundedRectangleBorder(borderRadius: BorderRadius.circular(25)),
            elevation: 0,
          ),
          onPressed: () => CartUtils.showAddToCartModal(
            context,
            _currentDish!,
            initialQuantity: _quantity,
          ),
          icon: const Icon(Icons.shopping_bag_outlined,
              color: Colors.white, size: 20),
          label: const Text("Add to Cart",
              style: TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.bold,
                  color: Colors.white)),
        ),
      ),
    );
  }

  // --- UI HELPERS ---

  Widget _buildSkeletonBox({required double width, required double height}) {
    return Container(
      width: width,
      height: height,
      decoration: BoxDecoration(
        color: Colors.grey[300],
        borderRadius: BorderRadius.circular(4),
      ),
    );
  }

  Widget _buildQtyBtn(IconData icon, VoidCallback onTap,
      {required bool isMinus}) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(20),
      child: Container(
        width: 32,
        height: 32,
        decoration: BoxDecoration(
          color: isMinus ? const Color(0xFFFCE4EC) : _primaryRed,
          shape: BoxShape.circle,
        ),
        child:
            Icon(icon, size: 18, color: isMinus ? _primaryRed : Colors.white),
      ),
    );
  }

  Widget _buildRatingsAndReviewsSection() {
    if (_isLoadingReviews) {
      return const Padding(
        padding: const EdgeInsets.symmetric(vertical: 40),
        child:
            Center(child: CircularProgressIndicator(color: Color(0xFFE84D67))),
      );
    }

    final stats = _reviewStats ?? {};
    final double avgRating = (stats['avg_rating'] ?? 0.0).toDouble();
    final int totalReviews = stats['total_reviews'] ?? 0;

    final ratingDist = stats['rating_distribution'] ?? {};

    double getPercent(String starKey) {
      if (totalReviews == 0) return 0.0;
      final count = ratingDist[starKey] ?? ratingDist[int.parse(starKey)] ?? 0;
      return count / totalReviews;
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            const Text("Ratings & Reviews",
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            if (totalReviews > 0)
              TextButton(
                onPressed: () {
                  // TODO: Navigate to full reviews page
                  Navigator.push(
                    context,
                    MaterialPageRoute(
                      builder: (context) => FullReviewsPage(
                        dishUid: _currentDish!.uid,
                      ),
                    ),
                  );
                },
                child: Text("See All",
                    style: TextStyle(color: _primaryRed, fontSize: 14)),
              )
          ],
        ),
        const SizedBox(height: 16),
        if (totalReviews == 0)
          const Padding(
            padding: const EdgeInsets.symmetric(vertical: 20),
            child: Center(
                child: Text("This dish has no reviews yet.",
                    style: TextStyle(color: Colors.grey))),
          )
        else ...[
          // Rating Summary
          Row(
            children: [
              Column(
                children: [
                  Text(avgRating.toStringAsFixed(1),
                      style: TextStyle(
                          fontSize: 40,
                          color: _primaryRed,
                          fontWeight: FontWeight.w500)),
                  Row(
                      children: List.generate(
                          5,
                          (index) => const Icon(Icons.star,
                              color: Colors.amber, size: 16))),
                  const SizedBox(height: 4),
                  Text("$totalReviews Reviews",
                      style: TextStyle(fontSize: 12, color: Colors.grey[600])),
                ],
              ),
              const SizedBox(width: 24),
              Expanded(
                child: Column(
                  children: [
                    _buildRatingBarRow("5", getPercent("5")),
                    _buildRatingBarRow("4", getPercent("4")),
                    _buildRatingBarRow("3", getPercent("3")),
                    _buildRatingBarRow("2", getPercent("2")),
                    _buildRatingBarRow("1", getPercent("1")),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 24),

          // Comments Section
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              const Text("Comments",
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
              Text("$_totalComments",
                  style: TextStyle(fontSize: 14, color: Colors.grey[600])),
            ],
          ),
          const SizedBox(height: 16),

          // Render Comments
          ..._reviewsList.map((review) {
            final owner = review['owner_info'] ?? {};
            final authorName =
                owner['full_name'] ?? owner['username'] ?? 'Customer';
            final star = review['rating'] ?? 5;
            final comment = review['comment'] ?? '';
            final reply = review['reply'];

            final createdAt = review['created_at'] ?? '';
            String displayDate = "Recent";
            if (createdAt.toString().length >= 10) {
              displayDate = createdAt.toString().substring(0, 10);
            }

            final String currentUsername = _currentUser?.username ?? '';
            final bool isMyReview = (owner['username'] == currentUsername);
            final bool canReply = _isChef &&
                !isMyReview; // Chef có thể reply vào review của người khác

            return Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: _buildCommentCard(
                reviewUid: review['uid'],
                authorName: authorName,
                date: displayDate,
                rating: star.toString(),
                comment: comment,
                reply: reply,
                isMyReview: isMyReview,
                canReply: canReply,
                onEditPressed: () => _showEditReviewSheet(review),
                onDeletePressed: () => _confirmDeleteReview(review['uid']),
                onReplyPressed: () => _showReplySheet(review),
                onEditReplyPressed: reply != null && _isChef
                    ? () => _showEditReplySheet(reply)
                    : null,
                onDeleteReplyPressed: reply != null && _isChef
                    ? () => _deleteReply(reply['uid'])
                    : null,
              ),
            );
          }),
        ],
        const SizedBox(height: 20),
      ],
    );
  }

  Widget _buildCommentCard({
    required String reviewUid,
    required String authorName,
    required String date,
    required String rating,
    required String comment,
    required Map<String, dynamic>? reply,
    required bool isMyReview,
    required bool canReply,
    required VoidCallback onEditPressed,
    required VoidCallback onDeletePressed,
    required VoidCallback onReplyPressed,
    VoidCallback? onEditReplyPressed,
    VoidCallback? onDeleteReplyPressed,
  }) {
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.grey[100],
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Header row
          Row(
            children: [
              const Icon(Icons.person_outline, size: 28),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(authorName,
                        style: const TextStyle(
                            fontWeight: FontWeight.bold, fontSize: 14)),
                    Text(date,
                        style:
                            TextStyle(fontSize: 10, color: Colors.grey[600])),
                  ],
                ),
              ),
              // Edit/Delete buttons - chỉ hiển thị nếu là review của mình
              if (isMyReview) ...[
                IconButton(
                  icon:
                      const Icon(Icons.edit_note, color: Colors.blue, size: 20),
                  constraints: const BoxConstraints(),
                  padding: const EdgeInsets.symmetric(horizontal: 4),
                  tooltip: "Edit",
                  onPressed: onEditPressed,
                ),
                IconButton(
                  icon: const Icon(Icons.delete_outline,
                      color: Colors.red, size: 20),
                  constraints: const BoxConstraints(),
                  padding: const EdgeInsets.symmetric(horizontal: 4),
                  tooltip: "Delete",
                  onPressed: onDeletePressed,
                ),
              ],
              const Icon(Icons.star, color: Colors.amber, size: 16),
              const SizedBox(width: 4),
              Text(rating,
                  style: const TextStyle(
                      fontWeight: FontWeight.bold,
                      color: Colors.amber,
                      fontSize: 14)),
            ],
          ),

          // Comment content
          if (comment.isNotEmpty) ...[
            const SizedBox(height: 12),
            Text(comment,
                style: TextStyle(
                    color: Colors.grey[800], fontSize: 13, height: 1.4)),
          ],

          // Reply section (nếu có)
          if (reply != null) ...[
            const SizedBox(height: 12),
            Container(
              margin: const EdgeInsets.only(left: 40),
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: const Color(0xFFFFF3E6),
                borderRadius: BorderRadius.circular(12),
                border:
                    Border.all(color: const Color(0xFFFFBB94).withOpacity(0.3)),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      const Icon(Icons.reply,
                          size: 14, color: Color(0xFFE55866)),
                      const SizedBox(width: 8),
                      const Text(
                        "Chef's reply:",
                        style: TextStyle(
                          fontWeight: FontWeight.bold,
                          fontSize: 12,
                          color: Color(0xFFE55866),
                        ),
                      ),
                      const Spacer(),
                      // Edit Reply button
                      if (onEditReplyPressed != null)
                        IconButton(
                          icon: const Icon(Icons.edit_note,
                              size: 16, color: Colors.blue),
                          constraints: const BoxConstraints(),
                          padding: EdgeInsets.zero,
                          tooltip: "Edit reply",
                          onPressed: onEditReplyPressed,
                        ),
                      // Delete Reply button
                      if (onDeleteReplyPressed != null)
                        IconButton(
                          icon: const Icon(Icons.delete_outline,
                              size: 16, color: Colors.red),
                          constraints: const BoxConstraints(),
                          padding: EdgeInsets.zero,
                          tooltip: "Delete reply",
                          onPressed: onDeleteReplyPressed,
                        ),
                    ],
                  ),
                  const SizedBox(height: 4),
                  Text(
                    reply['content'] ?? '',
                    style: const TextStyle(fontSize: 12, color: Colors.black87),
                  ),
                  if (reply['created_at'] != null)
                    Padding(
                      padding: const EdgeInsets.only(top: 4),
                      child: Text(
                        _formatDate(reply['created_at']),
                        style: TextStyle(fontSize: 10, color: Colors.grey[500]),
                      ),
                    ),
                ],
              ),
            ),
          ],

          // Reply button - chỉ hiển thị nếu là chef và chưa reply
          if (canReply && reply == null) ...[
            const SizedBox(height: 8),
            Align(
              alignment: Alignment.centerRight,
              child: TextButton.icon(
                onPressed: onReplyPressed,
                icon: const Icon(Icons.reply, size: 14),
                label: const Text("Reply", style: TextStyle(fontSize: 12)),
                style: TextButton.styleFrom(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                  minimumSize: Size.zero,
                  tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }

  String _formatDate(String dateTimeString) {
    try {
      final date = DateTime.parse(dateTimeString);
      return "${date.day}/${date.month}/${date.year}";
    } catch (e) {
      return dateTimeString;
    }
  }

  Widget _buildRatingBarRow(String star, double percent) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 4.0),
      child: Row(
        children: [
          Text(star,
              style: TextStyle(
                  fontSize: 12,
                  color: Colors.grey[700],
                  fontWeight: FontWeight.w500)),
          const SizedBox(width: 8),
          Expanded(
            child: ClipRRect(
              borderRadius: BorderRadius.circular(4),
              child: LinearProgressIndicator(
                value: percent,
                minHeight: 6,
                backgroundColor: Colors.grey[300],
                valueColor:
                    const AlwaysStoppedAnimation<Color>(Color(0xFFE84D67)),
              ),
            ),
          ),
          const SizedBox(width: 8),
          SizedBox(
            width: 35,
            child: Text("${(percent * 100).toInt()}%",
                textAlign: TextAlign.right,
                style: TextStyle(fontSize: 12, color: Colors.grey[600])),
          ),
        ],
      ),
    );
  }
}

// ===========================================================================
// REPLY BOTTOM SHEET
// ===========================================================================
class _ReplyBottomSheet extends StatefulWidget {
  final String dishName;
  final String? existingReply;

  const _ReplyBottomSheet({
    required this.dishName,
    this.existingReply,
  });

  @override
  State<_ReplyBottomSheet> createState() => _ReplyBottomSheetState();
}

class _ReplyBottomSheetState extends State<_ReplyBottomSheet> {
  final _replyController = TextEditingController();
  bool _isSubmitting = false;

  @override
  void initState() {
    super.initState();
    if (widget.existingReply != null) {
      _replyController.text = widget.existingReply!;
    }
  }

  @override
  void dispose() {
    _replyController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      padding: EdgeInsets.only(
        bottom: MediaQuery.of(context).viewInsets.bottom,
      ),
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Handle
            Center(
              child: Container(
                width: 40,
                height: 4,
                decoration: BoxDecoration(
                  color: Colors.grey[300],
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            const SizedBox(height: 20),
            Text(
              widget.existingReply != null ? "Edit Reply" : "Reply to Review",
              style: const TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.bold,
                color: Color(0xFFE55866),
              ),
            ),
            const SizedBox(height: 8),
            Text(
              "for ${widget.dishName}",
              style: TextStyle(fontSize: 14, color: Colors.grey[600]),
            ),
            const SizedBox(height: 20),
            TextField(
              controller: _replyController,
              maxLines: 4,
              decoration: InputDecoration(
                hintText: "Write your reply here...",
                filled: true,
                fillColor: Colors.grey[50],
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: BorderSide.none,
                ),
                focusedBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: const BorderSide(color: Color(0xFFE55866)),
                ),
              ),
            ),
            const SizedBox(height: 20),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed:
                        _isSubmitting ? null : () => Navigator.pop(context),
                    style: OutlinedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 12),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12),
                      ),
                    ),
                    child: const Text("Cancel"),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: ElevatedButton(
                    onPressed: _isSubmitting
                        ? null
                        : () {
                            final reply = _replyController.text.trim();
                            if (reply.isNotEmpty) {
                              Navigator.pop(context, reply);
                            }
                          },
                    style: ElevatedButton.styleFrom(
                      backgroundColor: const Color(0xFFE55866),
                      padding: const EdgeInsets.symmetric(vertical: 12),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12),
                      ),
                    ),
                    child: _isSubmitting
                        ? const SizedBox(
                            height: 20,
                            width: 20,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              valueColor:
                                  AlwaysStoppedAnimation<Color>(Colors.white),
                            ),
                          )
                        : Text(
                            widget.existingReply != null
                                ? "Update"
                                : "Post Reply",
                            style: const TextStyle(
                              fontWeight: FontWeight.bold,
                              color: Colors.white,
                            ),
                          ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 20),
          ],
        ),
      ),
    );
  }
}
