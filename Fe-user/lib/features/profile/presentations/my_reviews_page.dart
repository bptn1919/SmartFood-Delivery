import 'package:flutter/material.dart';
import 'package:testing/features/home/presentations/dish_detail_page.dart';
import 'package:testing/features/recommend/presentations/widgets/better_alternatives_sheet.dart';
import '../../common/app_components.dart';
import 'package:testing/features/checkout/repositories/review_repository.dart';
import 'package:testing/features/checkout/widgets/review_bottom_sheet.dart';

class MyReviewsPage extends StatefulWidget {
  const MyReviewsPage({super.key});

  @override
  State<MyReviewsPage> createState() => _MyReviewsPageState();
}

class _MyReviewsPageState extends State<MyReviewsPage> {
  final _repo = ReviewRepository();
  bool _isLoading = true;
  List<dynamic> _myReviews = [];

  // Styling colors matching the pattern
  final Color _primaryOrange = const Color(0xFFFFB68C);
  final Color _textBrown = const Color(0xFF4A3225);
  final Color _primaryRed = const Color(0xFFE55866);

  @override
  void initState() {
    super.initState();
    _fetchMyReviews();
  }

  Future<void> _fetchMyReviews() async {
    setState(() => _isLoading = true);
    
    final result = await _repo.getMyReviews(page: 1, pageSize: 50);

    if (mounted) {
      setState(() {
        if (result != null && result['data'] != null && result['data']['content'] != null) {
          _myReviews = result['data']['content'];
        }
        _isLoading = false;
      });
    }
  }

  Future<void> _showEditReviewSheet(Map<String, dynamic> currentReview) async {
    final reviewUid = currentReview['uid'];
    final dishName = currentReview['dish_info']?['name'] ?? 'Dish';
    final currentRating = currentReview['rating'] ?? 5;
    final currentComment = currentReview['comment'] ?? '';

    final reviewData = await showModalBottomSheet<Map<String, dynamic>>(
      context: context,
      isScrollControlled: true, 
      backgroundColor: Colors.transparent,
      builder: (context) => ReviewBottomSheet(
        dishName: dishName,
        initialRating: currentRating.toDouble(),
        initialComment: currentComment,
        isEditMode: true, 
      ),
    );

    if (reviewData != null) {
      final int newRating = reviewData['rating'];
      final String newComment = reviewData['comment'];
      
      showDialog(context: context, barrierDismissible: false, builder: (c) => const Center(child: CircularProgressIndicator(color: Color(0xFFE84D67))));

      try {
        await _repo.updateReview(reviewUid: reviewUid, rating: newRating, comment: newComment);

        if (mounted) Navigator.pop(context);
        if (mounted) showAppSnackBar(context, 'Update successfully!', type: SnackBarType.success,);
        
        _fetchMyReviews(); 
      } catch (e) {
        if (mounted) Navigator.pop(context);
        if (mounted) showAppSnackBar(context, 'Error: $e', type: SnackBarType.error,);
      }
    }
  }

  Future<void> _confirmDeleteReview(String reviewUid) async {
    final bool? confirm = await showDialog<bool>(
      context: context,
      builder: (BuildContext context) {
        return AlertDialog(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
          title: const Text("Delete Review", style: TextStyle(fontWeight: FontWeight.bold)),
          content: const Text("Are you sure you want to delete this review? This action cannot be undone."),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text("Cancel", style: TextStyle(color: Colors.grey)),
            ),
            ElevatedButton(
              style: ElevatedButton.styleFrom(backgroundColor: Colors.red),
              onPressed: () => Navigator.pop(context, true),
              child: const Text("Delete", style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold)),
            ),
          ],
        );
      },
    );

    if (confirm == true) {
      showDialog(context: context, barrierDismissible: false, builder: (c) => const Center(child: CircularProgressIndicator(color: Colors.red)));

      try {
        await _repo.deleteReview(reviewUid);

        if (mounted) Navigator.pop(context);
        if (mounted) showAppSnackBar(context, 'Review deleted successfully!', type: SnackBarType.success,);
        
        _fetchMyReviews(); 
      } catch (e) {
        if (mounted) Navigator.pop(context); 
        if (mounted) showAppSnackBar(context, 'Error: $e', type: SnackBarType.error,);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
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
                Expanded(
                  child: Text(
                    "My Reviews",
                    textAlign: TextAlign.center,
                    style: const TextStyle(
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
              child: _buildBody(),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildBody() {
    if (_isLoading) {
      return const Center(child: CircularProgressIndicator(color: Color(0xFFE84D67)));
    }

    if (_myReviews.isEmpty) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.rate_review_outlined, size: 64, color: Colors.grey[300]),
            const SizedBox(height: 16),
            const Text("You haven't written any reviews yet.", style: TextStyle(color: Colors.grey, fontSize: 16)),
          ],
        ),
      );
    }

    return RefreshIndicator(
      color: const Color(0xFFE84D67),
      onRefresh: _fetchMyReviews,
      child: ListView.separated(
        padding: const EdgeInsets.all(16),
        physics: const AlwaysScrollableScrollPhysics(),
        itemCount: _myReviews.length,
        separatorBuilder: (_, __) => const SizedBox(height: 16),
        itemBuilder: (context, index) {
          final review = _myReviews[index];
          return _buildReviewCard(review);
        },
      ),
    );
  }

  Widget _buildReviewCard(Map<String, dynamic> review) {
    final dishInfo = review['dish_info'] ?? {};
    final dishName = dishInfo['name'] ?? 'Unknown dish';
    final price = (dishInfo['price'] ?? 0).toDouble();
    
    final star = review['rating'] ?? 5;
    final comment = review['comment'] ?? '';
    final reply = review['reply'];
    
    final createdAt = review['created_at'] ?? '';
    String displayDate = "Recent";
    if (createdAt.toString().length >= 10) displayDate = createdAt.toString().substring(0, 10);

    return GestureDetector(
    // 👇 TECH LEAD FIX: Thêm sự kiện onTap để Navigate
    onTap: () {
      debugPrint("Navigating to Dish Detail: $dishName");
      Navigator.push(
        context,
        MaterialPageRoute(
          builder: (context) => DishDetailPage(dishId: dishInfo['uid'] ?? ''), // Truyền dishId để mở đúng món ăn
        ),
      );
    },
    child: Container(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(12),
        boxShadow: [BoxShadow(color: Colors.black.withOpacity(0.03), blurRadius: 10, offset: const Offset(0, 4))],
      ),
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // --- HEADER: DISH INFORMATION ---
          Row(
            children: [
              Container(
                width: 50, height: 50,
                decoration: BoxDecoration(color: Colors.grey[200], borderRadius: BorderRadius.circular(8)),
                child: const Icon(Icons.fastfood, color: Colors.grey),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(dishName, style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 15), maxLines: 1, overflow: TextOverflow.ellipsis),
                    const SizedBox(height: 4),
                    Text("\$${price.toStringAsFixed(2)}", style: TextStyle(color: _primaryRed, fontWeight: FontWeight.bold, fontSize: 13)),
                  ],
                ),
              ),

              IconButton(
                icon: const Icon(Icons.edit_note, color: Colors.grey),
                tooltip: "Edit review",
                onPressed: () => _showEditReviewSheet(review),
              ),

              IconButton(
                icon: const Icon(Icons.delete_outline, color: Colors.red, size: 22),
                constraints: const BoxConstraints(), padding: const EdgeInsets.symmetric(horizontal: 4),
                tooltip: "Delete review",
                onPressed: () => _confirmDeleteReview(review['uid']),
              ),

              
              
              
            ],
          ),
          
          const Padding(padding: const EdgeInsets.symmetric(vertical: 12), child: Divider(height: 1)),
          
          // --- RATING & DATE ---
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Row(
                children: List.generate(5, (index) => Icon(Icons.star, color: index < star ? Colors.amber : Colors.grey[300], size: 16)),
              ),
              Text(displayDate, style: TextStyle(fontSize: 12, color: Colors.grey[500])),
            ],
          ),
          const SizedBox(height: 8),

          // --- USER COMMENT ---
          if (comment.isNotEmpty)
            Text(comment, style: const TextStyle(fontSize: 14, height: 1.4, color: Colors.black87)),

          if (star <= 3) 
        OutlinedButton.icon(
          //icon: const Icon(Icons.auto_awesome, color: Color(0xFFE84D67)),
          label: const Text("Find Better Alternatives", style: TextStyle(color: Color(0xFFE84D67))),
          style: OutlinedButton.styleFrom(
            side: const BorderSide(color: Color(0xFFE84D67)),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
          ),
          onPressed: () {
            BetterAlternativesSheet.show(
              context, 
              dishUid: dishInfo['uid'] ?? '', 
              dishName: dishName, 
            );
          },
        ),
            
          // --- CHEF REPLY (IF EXISTS) ---
          if (reply != null && reply['comment'] != null) ...[
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(color: Colors.grey[100], borderRadius: BorderRadius.circular(8)),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Row(
                    children: [
                      Icon(Icons.storefront, size: 14, color: Colors.black54),
                      SizedBox(width: 6),
                      Text("Chef's Response", style: TextStyle(fontSize: 12, fontWeight: FontWeight.bold, color: Colors.black87)),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Text(reply['comment'], style: TextStyle(fontSize: 13, height: 1.4, color: Colors.grey[800])),
                ],
              ),
            ),
          ]


        ],
      ),
    ),
    );
  }
}