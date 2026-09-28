import 'package:flutter/material.dart';
import 'package:testing/features/auth/models/user_model.dart';
import 'package:testing/features/auth/repositories/auth_repository.dart';
import 'package:testing/features/checkout/repositories/review_repository.dart';
import 'package:testing/features/common/app_components.dart';

class FullReviewsPage extends StatefulWidget {
  final String dishUid;

  const FullReviewsPage({super.key, required this.dishUid});

  @override
  State<FullReviewsPage> createState() => _FullReviewsPageState();
}

class _FullReviewsPageState extends State<FullReviewsPage> {
  // --- MÀU SẮC THEME ---
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _textBrown = const Color(0xFF4A3225);

  bool _isLoadingInitial = true;
  bool _isLoadingMore = false;
  String _selectedFilter = 'Latest'; 
  
  // Data State
  Map<String, dynamic>? _statsData;
  List<dynamic> _reviewsList = [];
  
  // Pagination State
  int _currentPage = 1;
  int _totalPages = 1;
  final int _pageSize = 10;
  final ScrollController _scrollController = ScrollController();

  final ReviewRepository reviewRepo = ReviewRepository();
  final AuthRepository authRepo = AuthRepository();
  UserModel? _currentUser;
  bool _isChef = false;
  
  @override
  void initState() {
    super.initState();
    // TODO: Khi ghép API, gọi _fetchReviews() ở đây
    // _fetchReviews();
    _fetchInitialData();
    _loadCurrentUser();
    
    // Lắng nghe sự kiện cuộn để Load More
    _scrollController.addListener(_onScroll);
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  Future<void> _loadCurrentUser() async {
    final user = await authRepo.getUserFromCache();
    setState(() {
      _currentUser = user;
      _isChef = user?.isChef ?? false;
    });
  }

  void _onScroll() {
    // Nếu cuộn đến sát cuối màn hình và còn trang để tải
    if (_scrollController.position.pixels >= _scrollController.position.maxScrollExtent - 200) {
      if (!_isLoadingMore && _currentPage < _totalPages) {
        _loadMoreReviews();
      }
    }
  }


  Future<void> _fetchInitialData() async {
    setState(() => _isLoadingInitial = true);
    try {
      // Gọi song song 2 APIs để tối ưu tốc độ
      final results = await Future.wait([
        reviewRepo.getDishReviewStats(widget.dishUid),
        reviewRepo.getDishReviews(widget.dishUid, page: 1, pageSize: _pageSize),
      ]);

      if (mounted) {
        setState(() {
          // Lấy đúng cấu trúc trả về (tùy vào _apiClient của bạn có bọc trong 'data' hay không)
          _statsData = results[0]?['data'];
          
          final listData = results[1]?['data'];
          if (listData != null) {
            _reviewsList = listData['content'] ?? [];
            _currentPage = listData['current_page'] ?? 1;
            _totalPages = listData['total_pages'] ?? 1;
          }
          _isLoadingInitial = false;
        });
      }
    } catch (e) {
      debugPrint("Lỗi tải trang đánh giá: $e");
      if (mounted) setState(() => _isLoadingInitial = false);
    }
  }

  Future<void> _loadMoreReviews() async {
    setState(() => _isLoadingMore = true);
    try {
      final nextPage = _currentPage + 1;
      // final listData = await reviewRepo.getDishReviews(widget.dishUid, page: nextPage, pageSize: _pageSize);
      final listData = await reviewRepo.getDishReviews(widget.dishUid, page: nextPage, pageSize: _pageSize);
      
      if (mounted && listData != null) {
        setState(() {
          _reviewsList.addAll(listData['content'] ?? []);
          _currentPage = listData['current_page'] ?? nextPage;
          _totalPages = listData['total_pages'] ?? 1;
        });
      }
    } finally {
      if (mounted) setState(() => _isLoadingMore = false);
    }
  }

  String _formatDate(String? isoString) {
    if (isoString == null || isoString.isEmpty) return "Unknown date";
    try {
      final date = DateTime.parse(isoString).toLocal();
      // Nếu có thư viện intl: return DateFormat('dd/MM/yyyy HH:mm').format(date);
      return "${date.day.toString().padLeft(2, '0')}/${date.month.toString().padLeft(2, '0')}/${date.year}";
    } catch (e) {
      return "Invalid date";
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
      // Hiện vòng xoay Loading không cho bấm ra ngoài
      showDialog(
        context: context, 
        barrierDismissible: false, 
        builder: (c) => const Center(child: CircularProgressIndicator(color: Colors.red))
      );

      try {
        // TODO: Mở comment dòng này khi dùng Repo thật
        // await reviewRepo.deleteReview(reviewUid);
        
        await Future.delayed(const Duration(seconds: 1)); // Mock thời gian xóa API

        if (mounted) {
          Navigator.pop(context); // Đóng Dialog loading
          showAppSnackBar(
            context, 
            'Review deleted successfully!',
            type: SnackBarType.success,
          );
        }
        
        // Cập nhật lại UI sau khi xóa thành công (Khớp với tên hàm ở file cũ)
        _fetchInitialData(); 

      } catch (e) {
        if (mounted) {
          Navigator.pop(context); // Đóng Dialog loading
          showAppSnackBar(
            context, 
            'Error: $e',
            type: SnackBarType.error,
          );
        }
      }
    }
  }


  // ==========================================
  // CHEF REPLY ACTIONS
  // ==========================================
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
        dishName: "this dish", // TECH LEAD FIX: Trang này không gọi detail dish nên ta truyền string tĩnh hoặc bỏ biến này đi
        existingReply: hasReply ? currentContent : null,
      ),
    );

    if (replyContent != null && replyContent.trim().isNotEmpty) {
      showDialog(
        context: context,
        barrierDismissible: false,
        builder: (c) => const Center(child: CircularProgressIndicator(color: Color(0xFFE55866))),
      );

      try {
        if (hasReply) {
          // await reviewRepo.updateReviewReply(replyUid: existingReply['uid'], content: replyContent);
          await Future.delayed(const Duration(seconds: 1)); // Mock
          if (mounted) showAppSnackBar(context, 'Reply updated!', type: SnackBarType.success);
        } else {
          // await reviewRepo.createReviewReply(reviewUid: reviewUid, content: replyContent);
          await Future.delayed(const Duration(seconds: 1)); // Mock
          if (mounted) showAppSnackBar(context, 'Reply added!', type: SnackBarType.success);
        }
        
        if (mounted) Navigator.pop(context); // Đóng Loading
        _fetchInitialData(); // TECH LEAD FIX: Gọi hàm load lại data
      } catch (e) {
        if (mounted) {
          Navigator.pop(context);
          showAppSnackBar(context, 'Error: $e', type: SnackBarType.error);
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
        dishName: "this dish",
        existingReply: currentContent,
      ),
    );

    if (newContent != null && newContent.trim().isNotEmpty && newContent != currentContent) {
      showDialog(
        context: context,
        barrierDismissible: false,
        builder: (c) => const Center(child: CircularProgressIndicator(color: Color(0xFFE55866))),
      );

      try {
        // await reviewRepo.updateReviewReply(replyUid: replyUid, content: newContent);
        await Future.delayed(const Duration(seconds: 1)); // Mock
        
        if (mounted) {
          Navigator.pop(context);
          showAppSnackBar(context, 'Reply updated!', type: SnackBarType.success);
        }
        
        _fetchInitialData();
      } catch (e) {
        if (mounted) {
          Navigator.pop(context);
          showAppSnackBar(context, 'Error: $e', type: SnackBarType.error);
        }
      }
    }
  }

  Future<void> _deleteReply(String replyUid) async {
    final bool? confirm = await showDialog<bool>(
      context: context,
      builder: (BuildContext context) {
        return AlertDialog(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
          title: const Text("Delete Reply", style: TextStyle(fontWeight: FontWeight.bold)),
          content: const Text("Are you sure you want to delete this reply?"),
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
      showDialog(
        context: context,
        barrierDismissible: false,
        builder: (c) => const Center(child: CircularProgressIndicator(color: Colors.red)),
      );

      try {
        // await reviewRepo.deleteReviewReply(replyUid);
        await Future.delayed(const Duration(seconds: 1)); // Mock
        
        if (mounted) {
          Navigator.pop(context);
          showAppSnackBar(context, 'Reply deleted!', type: SnackBarType.success);
        }
        
        _fetchInitialData();
      } catch (e) {
        if (mounted) {
          Navigator.pop(context);
          showAppSnackBar(context, 'Error: $e', type: SnackBarType.error);
        }
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.grey[50],
      appBar: AppBar(
        backgroundColor: Colors.white,
        elevation: 0.5,
        iconTheme: const IconThemeData(color: Colors.black87),
        title: Text(
          "All Reviews",
          style: TextStyle(color: _textBrown, fontWeight: FontWeight.bold),
        ),
        centerTitle: true,
      ),
      body: _isLoadingInitial
          ? Center(child: CircularProgressIndicator(color: _primaryRed))
          : CustomScrollView(
              slivers: [
                // 1. HEADER: TỔNG QUAN ĐIỂM SỐ
                SliverToBoxAdapter(
                  child: _buildRatingSummary(),
                ),

                // 2. FILTER CHIPS: BỘ LỌC
                SliverToBoxAdapter(
                  child: _buildFilterChips(),
                ),

                // 3. DANH SÁCH REVIEW
                SliverPadding(
                  padding: const EdgeInsets.all(16),
                  sliver: SliverList(
                    delegate: SliverChildBuilderDelegate(
                      (context, index) {
                        return _buildReviewCard(_reviewsList[index]);
                      },
                      childCount: _reviewsList.length,
                    ),
                  ),
                ),
                if (_isLoadingMore)
                  SliverToBoxAdapter(
                    child: Padding(
                      padding: const EdgeInsets.all(16.0),
                      child: Center(child: CircularProgressIndicator(color: _primaryRed)),
                    ),
                  )
              ],
            ),
    );
  }

  // --- WIDGET TỔNG QUAN ---
  Widget _buildRatingSummary() {
    final double avg = (_statsData?['avg_rating'] ?? 0).toDouble();
    final int total = _statsData?['total_reviews'] ?? 0;

    return Container(
      color: Colors.white,
      padding: const EdgeInsets.symmetric(vertical: 24, horizontal: 16),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Text(avg.toStringAsFixed(1), style: const TextStyle(fontSize: 48, fontWeight: FontWeight.bold)),
          const SizedBox(width: 16),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: List.generate(
                  5,
                  (index) => const Icon(Icons.star, color: Colors.amber, size: 20),
                ),
              ),
              const SizedBox(height: 4),
              Text(
                "Based on $total reviews",
                style: TextStyle(color: Colors.grey[600], fontSize: 14),
              ),
            ],
          )
        ],
      ),
    );
  }

  // --- WIDGET BỘ LỌC ---
  Widget _buildFilterChips() {
    final filters = ['Latest', 'Highest Rated', 'Lowest Rated'];
    
    return Container(
      color: Colors.white,
      padding: const EdgeInsets.only(bottom: 12, left: 16, right: 16),
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: Row(
          children: filters.map((filter) {
            final isSelected = _selectedFilter == filter;
            return Padding(
              padding: const EdgeInsets.only(right: 8),
              child: ChoiceChip(
                label: Text(filter),
                selected: isSelected,
                selectedColor: _primaryRed.withOpacity(0.1),
                labelStyle: TextStyle(
                  color: isSelected ? _primaryRed : Colors.grey[700],
                  fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
                ),
                side: BorderSide(
                  color: isSelected ? _primaryRed : Colors.grey.shade300,
                ),
                onSelected: (selected) {
                  if (selected) setState(() => _selectedFilter = filter);
                },
              ),
            );
          }).toList(),
        ),
      ),
    );
  }

  // --- WIDGET THẺ ĐÁNH GIÁ ---
  Widget _buildReviewCard(Map<String, dynamic> review) {
    final ownerInfo = review['owner_info'] ?? {};
    final String name = ownerInfo['full_name'] ?? ownerInfo['username'] ?? 'Anonymous';
    final int rating = review['rating'] ?? 0;
    final String comment = review['comment'] ?? '';
    final String date = _formatDate(review['created_at']);
    final String reviewUid = review['uid'] ?? '';

    

    final bool isMyReview = true;
    
    // Kiểm tra Chef Reply có tồn tại và có nội dung không
    final replyObj = review['reply'];
    final String? chefReply = (replyObj != null && replyObj['content'] != null) 
        ? replyObj['content'] 
        : null;

    final String chefName = replyObj?['owner_info']?['full_name'] ?? "Chef";

    return Container(
      margin: const EdgeInsets.only(bottom: 16),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(color: Colors.black.withOpacity(0.03), blurRadius: 10, offset: const Offset(0, 4))
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // User Info & Date
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Row(
                children: [
                  CircleAvatar(
                    backgroundColor: _primaryRed.withOpacity(0.1),
                    child: Text(name.isNotEmpty ? name[0].toUpperCase() : '?', style: TextStyle(color: _primaryRed, fontWeight: FontWeight.bold)),
                  ),
                  const SizedBox(width: 12),
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(name, style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 15)),
                      const SizedBox(height: 2),
                      Row(
                        children: List.generate(
                          5,
                          (index) => Icon(
                            Icons.star,
                            color: index < review['rating'] ? Colors.amber : Colors.grey[300],
                            size: 14,
                          ),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
              Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Text(date, style: TextStyle(color: Colors.grey[500], fontSize: 12)),
                  if (isMyReview) ...[
                    const SizedBox(height: 4),
                    IconButton(
                      padding: EdgeInsets.zero,
                      constraints: const BoxConstraints(),
                      icon: const Icon(Icons.delete_outline, color: Colors.red, size: 20),
                      tooltip: "Delete review",
                      onPressed: () => _confirmDeleteReview(reviewUid),
                    ),
                  ]
                ],
              ),
            ],
          ),
          const SizedBox(height: 12),
          
          // User Comment
          Text(comment, style: const TextStyle(fontSize: 14, height: 1.4, color: Colors.black87)),
          
          // Chef's Reply (Nếu có)
          if (chefReply != null && chefReply.isNotEmpty) ...[
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(color: Colors.grey[100], borderRadius: BorderRadius.circular(8)),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Row(
                        children: [
                          Icon(Icons.storefront, size: 16, color: _primaryRed),
                          const SizedBox(width: 6),
                          Text("$chefName's Response", style: TextStyle(fontSize: 13, fontWeight: FontWeight.bold, color: _textBrown)),
                        ],
                      ),
                      
                      // 👇 NẾU LÀ CHEF: Hiển thị 2 nút Sửa / Xóa Reply
                      if (_isChef) 
                        Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            IconButton(
                              padding: EdgeInsets.zero,
                              constraints: const BoxConstraints(),
                              icon: const Icon(Icons.edit_note, color: Colors.grey, size: 20),
                              onPressed: () => _showEditReplySheet(replyObj!),
                            ),
                            const SizedBox(width: 12),
                            IconButton(
                              padding: EdgeInsets.zero,
                              constraints: const BoxConstraints(),
                              icon: const Icon(Icons.delete_outline, color: Colors.red, size: 18),
                              onPressed: () => _deleteReply(replyObj!['uid']),
                            ),
                          ],
                        )
                    ],
                  ),
                  const SizedBox(height: 6),
                  Text(chefReply, style: TextStyle(fontSize: 13, height: 1.4, color: Colors.grey[800])),
                ],
              ),
            ),
          ] else if (_isChef) ...[
            // 👇 NẾU LÀ CHEF VÀ CHƯA CÓ REPLY: Hiển thị nút bấm để trả lời
            const SizedBox(height: 12),
            Align(
              alignment: Alignment.centerLeft,
              child: OutlinedButton.icon(
                style: OutlinedButton.styleFrom(
                  foregroundColor: _primaryRed,
                  side: BorderSide(color: _primaryRed.withOpacity(0.5)),
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                  minimumSize: Size.zero,
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                ),
                icon: const Icon(Icons.reply_rounded, size: 16),
                label: const Text('Reply to this review', style: TextStyle(fontSize: 12, fontWeight: FontWeight.bold)),
                onPressed: () => _showReplySheet(review),
              ),
            ),
          ]
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
                    onPressed: _isSubmitting ? null : () => Navigator.pop(context),
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
                    onPressed: _isSubmitting ? null : () {
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
                              valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
                            ),
                          )
                        : Text(
                            widget.existingReply != null ? "Update" : "Post Reply",
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