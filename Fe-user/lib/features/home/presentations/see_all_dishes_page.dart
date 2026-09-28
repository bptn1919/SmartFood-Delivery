import 'dart:async';
import 'package:flutter/material.dart';
import 'package:testing/features/home/models/dish_model.dart';
import 'package:testing/features/home/presentations/dish_detail_page.dart';
import 'package:testing/features/home/repositories/dish_repository.dart';

// 👇 TECH LEAD: Enum Sort được tối ưu riêng cho Món Ăn
enum DishSortOption { rating, sold, price }

class SeeAllDishesPage extends StatefulWidget {
  const SeeAllDishesPage({super.key});

  @override
  State<SeeAllDishesPage> createState() => _SeeAllDishesPageState();
}

class _SeeAllDishesPageState extends State<SeeAllDishesPage> {
  // --- BỘ MÀU CHUẨN THEME APP ---
  final Color _primaryOrange = const Color(0xFFFFBB94);
  final Color _textBrown = const Color(0xFF4A3225);
  final Color _backgroundColor = const Color(0xFFF7F7F7);

  // --- REPOSITORY & STATE ---
  final DishRepository _dishRepo = DishRepository();

  final ScrollController _scrollController = ScrollController();
  int _currentPage = 1;
  bool _isFetchingMore = false;
  bool _hasMoreData = true;

  DishSortOption _currentSort = DishSortOption.rating;
  bool _isDescending = true;

  List<DishModel> _allDishes = [];
  List<DishModel> _filteredDishes = [];
  bool _isLoading = true;

  bool _isFavorite = false;

  // --- FILTER & DEBOUNCE ---
  Timer? _searchDebounce;
  String _searchQuery = "";
  double _minRating = 0.0;

  final List<String> _categories = ['All', 'Food', 'Beverages', 'Dessert'];
  String _selectedCategory = 'All';

  @override
  void initState() {
    super.initState();
    _loadAllDishes();

    _scrollController.addListener(() {
      if (_scrollController.position.pixels >=
          _scrollController.position.maxScrollExtent - 200) {
        if (!_isFetchingMore && _hasMoreData && !_isLoading) {
          _loadMoreDishes();
        }
      }
    });
  }

  @override
  void dispose() {
    _scrollController.dispose();
    _searchDebounce?.cancel();
    super.dispose();
  }

  void _onCategorySelected(String category) {
    if (_selectedCategory == category) return; // Bấm lại cái cũ thì bỏ qua
    setState(() {
      _selectedCategory = category;
    });
    _loadAllDishes(); // Gọi lại API với category mới
  }

  void _applyFilters() {
    // 1. CHỈ LỌC THEO TEXT VÀ RATING
    var tempList = _allDishes.where((dish) {
      final name = (dish.name ?? "").toLowerCase();
      final category = (dish.category ?? "").toLowerCase();

      final matchesSearch =
          name.contains(_searchQuery) || category.contains(_searchQuery);
      final matchesRating = (dish.avgRating ?? 0) >= _minRating;

      return matchesSearch && matchesRating;
    }).toList();

    setState(() {
      _filteredDishes = tempList;
    });
  }

  // 👇 Sinh Param truyền xuống Backend
  String _getSortParam() {
    final direction = _isDescending ? "DESC" : "ASC";
    switch (_currentSort) {
      case DishSortOption.sold:
        return "SOLD_$direction";
      case DishSortOption.price:
        return "PRICE_$direction";
      case DishSortOption.rating:
      default:
        return "RATING_$direction";
    }
  }

  Future<void> _loadAllDishes() async {
    setState(() => _isLoading = true);
    try {
      _currentPage = 1;

      // 👇 Chuyển đổi 'All' thành null, và các giá trị khác thành UPPERCASE theo chuẩn BE
      final String? categoryParam =
          _selectedCategory == 'All' ? null : _selectedCategory.toUpperCase();

      final list = await _dishRepo.getAllDishes(
        page: _currentPage,
        sortBy: _getSortParam(),
        search: _searchQuery,
        category: categoryParam, // Truyền xuống BE
      );

      debugPrint("🚀 [Load Dishes] Loaded ${list.length} items");
      if (mounted) {
        setState(() {
          _allDishes = list;
          _filteredDishes = list; // Bỏ filter local, tin tưởng tuyệt đối vào BE
          _hasMoreData = list.length == 20;
          _isLoading = false;
        });
      }
    } catch (e) {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  Future<void> _loadMoreDishes() async {
    setState(() => _isFetchingMore = true);
    try {
      _currentPage++;

      final String? categoryParam =
          _selectedCategory == 'All' ? null : _selectedCategory.toUpperCase();

      final newList = await _dishRepo.getAllDishes(
        page: _currentPage,
        sortBy: _getSortParam(),
        search: _searchQuery,
        category: categoryParam, // Truyền xuống BE
      );

      if (mounted) {
        setState(() {
          if (newList.isEmpty) {
            _hasMoreData = false;
          } else {
            _allDishes.addAll(newList);
            _filteredDishes = _allDishes; // Đồng bộ trực tiếp
            if (newList.length < 20) _hasMoreData = false;
          }
          _isFetchingMore = false;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _isFetchingMore = false;
          _currentPage--;
        });
      }
    }
  }

  void _onSearchChanged(String value) {
    if (_searchDebounce?.isActive ?? false) _searchDebounce!.cancel();
    _searchDebounce = Timer(const Duration(milliseconds: 500), () {
      setState(() {
        _searchQuery = value.trim().toLowerCase();
        // Nếu Backend hỗ trợ search, gọi lại API luôn thay vì chỉ filter nội bộ
        _loadAllDishes();
      });
    });
  }

  void _onRatingChanged(double value) {
    setState(() {
      _minRating = value;
      _applyFilters();
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _primaryOrange,
      body: SafeArea(
        bottom: false,
        child: Column(
          children: [
            // --- HEADER & SEARCH ---
            Padding(
              padding:
                  const EdgeInsets.symmetric(horizontal: 20.0, vertical: 12.0),
              child: Row(
                children: [
                  GestureDetector(
                    onTap: () => Navigator.pop(context),
                    child: const Icon(Icons.arrow_back_ios,
                        color: Colors.white, size: 22),
                  ),
                  const SizedBox(width: 8),
                  // const Text(
                  //   "Delicious Dishes",
                  //   style: TextStyle(color: Colors.white, fontSize: 20, fontWeight: FontWeight.bold),
                  // )
                ],
              ),
            ),

            Padding(
              padding:
                  const EdgeInsets.symmetric(horizontal: 20.0, vertical: 8.0),
              child: Container(
                decoration: BoxDecoration(
                  color: Colors.white,
                  borderRadius: BorderRadius.circular(30),
                ),
                child: TextField(
                  onChanged: _onSearchChanged,
                  decoration: InputDecoration(
                    hintText: "Search dishes, categories...",
                    hintStyle:
                        TextStyle(color: Colors.grey.shade400, fontSize: 15),
                    prefixIcon: const Icon(Icons.search, color: Colors.grey),
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(30),
                      borderSide: BorderSide.none,
                    ),
                    contentPadding: const EdgeInsets.symmetric(vertical: 14),
                  ),
                ),
              ),
            ),
            const SizedBox(height: 16),

            // --- MAIN CONTENT AREA ---
            Expanded(
              child: Container(
                width: double.infinity,
                decoration: BoxDecoration(
                  color: _backgroundColor,
                  borderRadius: const BorderRadius.only(
                    topLeft: Radius.circular(30),
                    topRight: Radius.circular(30),
                  ),
                ),
                child: Column(
                  children: [
                    const SizedBox(height: 16),
                    _buildCategoryTabs(),
                    // --- RESULT COUNTER & SORTING ---
                    Padding(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 20.0, vertical: 4.0),
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Text(
                            "Found ${_filteredDishes.length} " +
                                (_filteredDishes.length > 1
                                    ? "dishes"
                                    : "dish"),
                            style: TextStyle(
                              fontSize: 15,
                              fontWeight: FontWeight.bold,
                              color: _textBrown,
                            ),
                          ),
                          PopupMenuButton<DishSortOption>(
                            shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(12)),
                            color: Colors.white,
                            elevation: 5,
                            onSelected: (DishSortOption result) {
                              setState(() {
                                if (_currentSort == result) {
                                  _isDescending = !_isDescending;
                                } else {
                                  _currentSort = result;
                                  // Giá mặc định Tăng dần, các cái khác mặc định Giảm dần
                                  _isDescending =
                                      (result != DishSortOption.price);
                                }
                              });
                              _loadAllDishes();
                            },
                            itemBuilder: (BuildContext context) =>
                                <PopupMenuEntry<DishSortOption>>[
                              PopupMenuItem<DishSortOption>(
                                value: DishSortOption.rating,
                                child: _buildSortText(
                                    'Rating', DishSortOption.rating),
                              ),
                              PopupMenuItem<DishSortOption>(
                                value: DishSortOption.price,
                                child: _buildSortText(
                                    'Price', DishSortOption.price),
                              ),
                            ],
                            child: Row(
                              children: [
                                Text("Sort",
                                    style: TextStyle(
                                        color: Colors.grey.shade600,
                                        fontSize: 13,
                                        fontWeight: FontWeight.w500)),
                                const SizedBox(width: 4),
                                Icon(Icons.sort,
                                    color: Colors.grey.shade600, size: 18),
                              ],
                            ),
                          )
                        ],
                      ),
                    ),
                    const SizedBox(height: 8),

                    // --- DANH SÁCH MÓN ĂN (GRID VIEW) ---
                    Expanded(
                      child: _isLoading
                          ? Center(
                              child: CircularProgressIndicator(
                                  color: _primaryOrange))
                          : _filteredDishes.isEmpty
                              ? const Center(
                                  child: Text("Couldn't find any dishes!"))
                              : GridView.builder(
                                  controller: _scrollController,
                                  physics: const BouncingScrollPhysics(),
                                  padding:
                                      const EdgeInsets.fromLTRB(20, 0, 20, 20),
                                  // 👇 TECH LEAD: Chuyển sang Grid 2 cột
                                  gridDelegate:
                                      const SliverGridDelegateWithFixedCrossAxisCount(
                                    crossAxisCount: 2,
                                    childAspectRatio:
                                        0.72, // Tỉ lệ thẻ DishCard
                                    crossAxisSpacing: 16,
                                    mainAxisSpacing: 16,
                                  ),
                                  itemCount: _filteredDishes.length,
                                  itemBuilder: (context, index) {
                                    return DishCard2(
                                      dish: _filteredDishes[index],
                                      onToggleFavorite: (String uid,
                                          bool isNowFavorite) async {
                                        try {
                                          if (isNowFavorite) {
                                            debugPrint(
                                                "❤️ Adding to favorites: $uid");
                                            return await _dishRepo
                                                .addFavoriteDish(uid);
                                          } else {
                                            debugPrint(
                                                "💔 Removing from favorites: $uid");
                                            return await _dishRepo
                                                .removeFavoriteDish(uid);
                                          }
                                        } catch (e) {
                                          // (Tùy chọn) Báo lỗi SnackBar ngay tại Page cha
                                          return false;
                                        }
                                      },
                                    );
                                  },
                                ),
                    ),

                    // 👇 VÒNG XOAY LOADING Ở ĐÁY MÀN HÌNH KHI CUỘN
                    if (_isFetchingMore)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 24.0, top: 8.0),
                        child: CircularProgressIndicator(
                            color: _primaryOrange, strokeWidth: 3),
                      )
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildCategoryTabs() {
    return SizedBox(
      height: 40,
      child: ListView.builder(
        scrollDirection: Axis.horizontal,
        physics: const BouncingScrollPhysics(),
        padding: const EdgeInsets.symmetric(horizontal: 20),
        itemCount: _categories.length,
        itemBuilder: (context, index) {
          final cat = _categories[index];
          final isSelected = _selectedCategory == cat;

          return GestureDetector(
            onTap: () => _onCategorySelected(cat),
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 200),
              margin: const EdgeInsets.only(right: 12),
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
              decoration: BoxDecoration(
                color: isSelected ? _primaryOrange : Colors.white,
                borderRadius: BorderRadius.circular(20),
                border: Border.all(
                  color: isSelected ? _primaryOrange : Colors.grey.shade300,
                  width: 1.5,
                ),
                boxShadow: isSelected
                    ? [
                        BoxShadow(
                          color: _primaryOrange.withOpacity(0.3),
                          blurRadius: 6,
                          offset: const Offset(0, 3),
                        )
                      ]
                    : [],
              ),
              child: Center(
                child: Text(
                  cat,
                  style: TextStyle(
                    color: isSelected ? Colors.white : Colors.grey.shade600,
                    fontWeight: isSelected ? FontWeight.bold : FontWeight.w500,
                    fontSize: 14,
                  ),
                ),
              ),
            ),
          );
        },
      ),
    );
  }

  // Hàm phụ trợ bôi đậm chữ Sort đang chọn
  Widget _buildSortText(String text, DishSortOption option) {
    return Text(
      text,
      style: TextStyle(
        color: _currentSort == option ? _primaryOrange : _textBrown,
        fontWeight:
            _currentSort == option ? FontWeight.bold : FontWeight.normal,
      ),
    );
  }
}

// --------------------------------------------------------
// THẺ DISH CARD ĐÃ TỐI ƯU UX
// --------------------------------------------------------

class DishCard2 extends StatefulWidget {
  final DishModel dish;
  // 👇 TECH LEAD FIX: Thêm một callback function để Page cha truyền logic vào
  final Future<bool> Function(String uid, bool currentStatus)? onToggleFavorite;

  const DishCard2({
    super.key,
    required this.dish,
    this.onToggleFavorite, // Không bắt buộc (phòng khi có màn hình không cho bấm tim)
  });

  @override
  State<DishCard2> createState() => _DishCard2State();
}

class _DishCard2State extends State<DishCard2> {
  bool _isFavorite = false;

  @override
  void initState() {
    super.initState();
    // 1. Mở khóa: Nhận giá trị ban đầu từ Model (Nhớ update DishModel của bạn nhé)
    _isFavorite = widget.dish.isFavorite ?? false;
  }

  // 👇 TECH LEAD FIX: Đảm bảo UI luôn đồng bộ với Data mới khi danh sách bị cuộn/reload
  @override
  void didUpdateWidget(covariant DishCard2 oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.dish.isFavorite != widget.dish.isFavorite) {
      setState(() {
        _isFavorite = widget.dish.isFavorite ?? false;
      });
    }
  }

  Future<void> _handleToggleFavorite() async {
    final String? uid = widget.dish.uid;
    if (uid == null || widget.onToggleFavorite == null) return;

    // OPTIMISTIC UI: Đổi màu tim ngay lập tức
    setState(() => _isFavorite = !_isFavorite);

    // Giao việc gọi API cho Page cha
    final success = await widget.onToggleFavorite!(uid, _isFavorite);

    // ROLLBACK: Nếu lỗi, thu hồi lại trái tim
    if (!success && mounted) {
      setState(() => _isFavorite = !_isFavorite);
    }
  }

  @override
  Widget build(BuildContext context) {
    final dish = widget.dish;
    // Fix lỗi lặp biến imageUrl
    final String finalImageUrl =
        dish.imageUrl ?? "https://via.placeholder.com/150";

    // 👇 TECH LEAD: Trích xuất cờ dị ứng từ Model
    final bool hasAllergy = dish.allergyWarning ?? false;

    return Card(
      elevation: 3,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () {
          // Xử lý điều hướng
          Navigator.push(
              context,
              MaterialPageRoute(
                  builder: (context) => DishDetailPage(dishId: dish.uid)));
        },
        // 👇 TECH LEAD FIX: Dùng Stack để đè Tag cảnh báo lên trên cùng
        child: Stack(
          fit: StackFit.expand, // Đảm bảo Stack chiếm trọn không gian của Card
          children: [
            // ==========================================
            // LỚP 1: NỘI DUNG CHÍNH (Bị làm mờ nếu có dị ứng)
            // ==========================================
            Opacity(
              opacity: hasAllergy ? 0.5 : 1.0, // Làm mờ 50% nếu dị ứng
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // 1. ẢNH MÓN ĂN & NÚT TIM
                  Stack(
                    children: [
                      ClipRRect(
                        borderRadius: const BorderRadius.vertical(
                          top: Radius.circular(12),
                        ),
                        child: SizedBox(
                          height: 120,
                          width: double.infinity,
                          child: Image.network(
                            finalImageUrl,
                            cacheWidth: 300,
                            fit: BoxFit.cover,
                            errorBuilder: (context, error, stackTrace) =>
                                Container(
                              color: Colors.grey[200],
                              child: const Icon(Icons.broken_image,
                                  color: Colors.grey),
                            ),
                          ),
                        ),
                      ),
                      // Nút trái tim thanh lịch góc phải
                      Positioned(
                        top: 8,
                        right: 8,
                        child: GestureDetector(
                          onTap: _handleToggleFavorite,
                          child: Container(
                            padding: const EdgeInsets.all(6),
                            decoration: BoxDecoration(
                                color: Colors.white.withOpacity(0.9),
                                shape: BoxShape.circle,
                                boxShadow: [
                                  BoxShadow(
                                    color: Colors.black.withOpacity(0.1),
                                    blurRadius: 4,
                                    offset: const Offset(0, 2),
                                  )
                                ]),
                            child: Icon(
                              _isFavorite
                                  ? Icons.favorite_rounded
                                  : Icons.favorite_border_rounded,
                              color: _isFavorite
                                  ? const Color(0xFFE84D67)
                                  : Colors.grey,
                              size: 16,
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),

                  // 2. PHẦN THÔNG TIN CHI TIẾT
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.all(8.0),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                dish.name ?? "Tên món ăn",
                                style: const TextStyle(
                                    fontWeight: FontWeight.bold, fontSize: 14),
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                              ),
                              const SizedBox(height: 2),
                              Text(
                                "by ${dish.chefName ?? 'Đầu bếp'}",
                                style: TextStyle(
                                    fontSize: 11,
                                    fontStyle: FontStyle.italic,
                                    color: Colors.grey[600]),
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                              ),
                              const SizedBox(height: 6),
                              Row(
                                children: [
                                  const Icon(Icons.star,
                                      color: Colors.amber, size: 12),
                                  const SizedBox(width: 2),
                                  Text(
                                    "${dish.avgRating ?? 0.0}",
                                    style: const TextStyle(
                                        fontSize: 11,
                                        fontWeight: FontWeight.bold),
                                  ),
                                  Text(
                                    " (${dish.reviewCount ?? 0})",
                                    style: TextStyle(
                                        fontSize: 10, color: Colors.grey[500]),
                                  ),
                                  const Spacer(),
                                  Text(
                                    "Sold ${dish.soldCount ?? 0}",
                                    style: TextStyle(
                                        fontSize: 10, color: Colors.grey[600]),
                                  ),
                                ],
                              ),
                            ],
                          ),

                          // Giá tiền + Nút Add
                          Row(
                            mainAxisAlignment: MainAxisAlignment.spaceBetween,
                            children: [
                              Text(
                                "${(dish.price ?? 0).toStringAsFixed(0)}đ",
                                style: const TextStyle(
                                    color: Color(0xFFE84D67),
                                    fontWeight: FontWeight.bold,
                                    fontSize: 13),
                              ),
                              Container(
                                padding: const EdgeInsets.all(4),
                                decoration: BoxDecoration(
                                  color: const Color(0xFFE84D67),
                                  borderRadius: BorderRadius.circular(6),
                                ),
                                child: const Icon(Icons.add,
                                    color: Colors.white, size: 14),
                              )
                            ],
                          )
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),

            // ==========================================
            // LỚP 2: TAG CẢNH BÁO DỊ ỨNG (Nằm đè lên trên cùng)
            // ==========================================
            if (hasAllergy)
              Positioned(
                top: 8,
                left: 8,
                child: Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                  decoration: BoxDecoration(
                      color: Colors.redAccent, // Đỏ tươi để nổi bật
                      borderRadius: BorderRadius.circular(6),
                      boxShadow: [
                        BoxShadow(
                            color: Colors.black.withOpacity(0.2),
                            blurRadius: 4,
                            offset: const Offset(0, 2))
                      ]),
                  child: const Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.warning_amber_rounded,
                          color: Colors.white, size: 14),
                      SizedBox(width: 4),
                      Text(
                        "Allergens",
                        style: TextStyle(
                            color: Colors.white,
                            fontSize: 10,
                            fontWeight: FontWeight.bold),
                      )
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
