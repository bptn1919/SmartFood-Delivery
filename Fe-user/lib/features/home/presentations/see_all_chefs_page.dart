import 'dart:async';
import 'package:flutter/material.dart';
import 'package:testing/features/checkout/models/chef_model.dart';
import 'package:testing/features/checkout/repositories/chef_repository.dart';

class SeeAllChefsPage extends StatefulWidget {
  const SeeAllChefsPage({super.key});

  @override
  State<SeeAllChefsPage> createState() => _SeeAllChefsPageState();
}

enum SortOption { rating, orders, name }

class _SeeAllChefsPageState extends State<SeeAllChefsPage> {
  // --- BỘ MÀU CHUẨN THEME APP ---
  final Color _primaryOrange = const Color(0xFFFFBB94);
  final Color _textBrown = const Color(0xFF4A3225);
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _backgroundColor = const Color(0xFFF7F7F7);

  // --- REPOSITORY & STATE ---
  final ChefRepository _chefRepo = ChefRepository();

  final ScrollController _scrollController = ScrollController();
  int _currentPage = 1;
  bool _isFetchingMore = false; // Đang call API load trang tiếp theo?
  bool _hasMoreData = true;

  SortOption _currentSort = SortOption.rating;
  bool _isDescending = true;

  List<ChefModel> _allChefs = []; // Danh sách gốc từ API
  List<ChefModel> _filteredChefs = []; // Danh sách hiển thị sau khi lọc
  bool _isLoading = true;

  // --- FILTER & DEBOUNCE ---
  Timer? _searchDebounce;
  String _searchQuery = "";
  double _minRating = 0.0; // Slider lọc sao

  @override
  void initState() {
    super.initState();
    _loadAllChefs();

    _scrollController.addListener(() {
      // Nếu user cuộn gần tới đáy (cách 200px) và không trong trạng thái đang load
      if (_scrollController.position.pixels >=
          _scrollController.position.maxScrollExtent - 200) {
        if (!_isFetchingMore && _hasMoreData && !_isLoading) {
          _loadMoreChefs();
        }
      }
    });
  }

  @override
  void dispose() {
    _scrollController.dispose();
    _searchDebounce?.cancel(); // Tránh rò rỉ bộ nhớ khi đóng trang
    super.dispose();
  }

  void _applyFilters() {
    // 1. Lọc (Filter)
    var tempList = _allChefs.where((chef) {
      final name = (chef.fullname ?? "").toLowerCase();
      final specialty = (chef.specialty ?? "").toLowerCase();

      final matchesSearch =
          name.contains(_searchQuery) || specialty.contains(_searchQuery);
      final matchesRating = (chef.rating ?? 0) >= _minRating;

      return matchesSearch && matchesRating;
    }).toList();

    // 2. Sắp xếp (Sort) ngay trên RAM
    tempList.sort((a, b) {
      switch (_currentSort) {
        case SortOption.rating:
          // Đảo b.compareTo(a) để sắp xếp Giảm dần (Từ sao cao -> thấp)
          return (b.rating ?? 0).compareTo(a.rating ?? 0);
        case SortOption.orders:
          // Lượt đặt nhiều nhất lên đầu
          return (b.numberOfOrders ?? 0).compareTo(a.numberOfOrders ?? 0);
        case SortOption.name:
          // A.compareTo(B) để sắp xếp A-Z
          return (a.fullname ?? "").compareTo(b.fullname ?? "");
      }
    });

    // 3. Cập nhật UI
    setState(() {
      _filteredChefs = tempList;
    });
  }

  String _getSortParam() {
    final direction = _isDescending ? "desc" : "asc";
    switch (_currentSort) {
      case SortOption.orders:
        return "orders_$direction";
      case SortOption.name:
        return "name_$direction";
      case SortOption.rating:
      default:
        return "rating_$direction";
    }
  }

  Future<void> _loadAllChefs() async {
    setState(() => _isLoading = true);
    try {
      _currentPage = 1; // Reset về trang 1
      final list = await _chefRepo.getAllChefs2(
          page: _currentPage, sortBy: _getSortParam());
      if (mounted) {
        setState(() {
          _allChefs = list;
          _filteredChefs = list;
          _hasMoreData =
              list.length == 20; // Nếu API trả đúng 20 -> Có thể còn trang sau
          _isLoading = false;
        });
      }
    } catch (e) {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  // Fetch các trang tiếp theo
  Future<void> _loadMoreChefs() async {
    setState(() => _isFetchingMore = true); // Hiện spinner nhỏ ở đáy

    try {
      _currentPage++; // Tăng số trang lên
      final newList = await _chefRepo.getAllChefs2(
          page: _currentPage, sortBy: _getSortParam());

      if (mounted) {
        setState(() {
          if (newList.isEmpty) {
            _hasMoreData = false; // Đã hết dữ liệu
          } else {
            _allChefs.addAll(newList); // CỘNG DỒN dữ liệu cũ và mới
            _applyFilters(); // Chạy lại bộ lọc tìm kiếm nội bộ

            // Nếu trả về ít hơn 20 item (page_size) -> Đã là trang cuối
            if (newList.length < 20) _hasMoreData = false;
          }
          _isFetchingMore = false; // Ẩn spinner
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _isFetchingMore = false;
          _currentPage--; // Lỗi thì lùi trang lại để user cuộn xuống có thể thử lại
        });
      }
    }
  }

  // 2. Logic xử lý Debounce khi gõ tìm kiếm
  void _onSearchChanged(String value) {
    if (_searchDebounce?.isActive ?? false) _searchDebounce!.cancel();
    _searchDebounce = Timer(const Duration(milliseconds: 500), () {
      setState(() {
        _searchQuery = value.trim().toLowerCase();
        _applyFilters();
      });
    });
  }

  // 3. Logic xử lý khi kéo Slider Rating
  void _onRatingChanged(double value) {
    setState(() {
      _minRating = value;
      _applyFilters();
    });
  }

  // 👇 TECH LEAD FIX: LÀM LẠI HÀM BUILD ĐÚNG THEO THEME MỚI
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _primaryOrange, // Nền toàn trang màu Cam theo hình
      body: SafeArea(
        bottom: false, // Để content tràn xuống đáy màn hình xịn hơn
        child: Column(
          children: [
            // --- HEADER AREA (Nền cam, Nút Back, Title, Icons) ---
            Padding(
              padding:
                  const EdgeInsets.symmetric(horizontal: 20.0, vertical: 12.0),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Row(
                    children: [
                      // Nút Back dành cho trang phụ
                      GestureDetector(
                        onTap: () => Navigator.pop(context),
                        child: const Icon(Icons.arrow_back_ios,
                            color: Colors.white, size: 22),
                      ),
                    ],
                  ),
                ],
              ),
            ),

            // --- THANH TÌM KIẾM (Nằm trên nền cam) ---
            Padding(
              padding:
                  const EdgeInsets.symmetric(horizontal: 20.0, vertical: 8.0),
              child: Container(
                decoration: BoxDecoration(
                  color: Colors.white,
                  borderRadius:
                      BorderRadius.circular(30), // Bo tròn mạnh giống hình
                ),
                child: TextField(
                  onChanged: _onSearchChanged,
                  decoration: InputDecoration(
                    hintText: "Search chefs, dishes...",
                    hintStyle:
                        TextStyle(color: Colors.grey.shade400, fontSize: 15),
                    prefixIcon: const Icon(Icons.search, color: Colors.grey),
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(30),
                      borderSide: BorderSide.none,
                    ),
                    contentPadding: const EdgeInsets.symmetric(
                        vertical: 14), // Cân đối độ cao
                  ),
                ),
              ),
            ),
            const SizedBox(
                height: 16), // Khoảng cách trước khi tới Content trắng

            // --- MAIN CONTENT AREA (Thẻ xám bo tròn góc lên) ---
            // Expanded(
            //   child: Container(
            //     width: double.infinity,
            //     decoration: BoxDecoration(
            //       color: _backgroundColor,
            //       borderRadius: const BorderRadius.only(
            //         topLeft: Radius.circular(30),
            //         topRight: Radius.circular(30),
            //       ),
            //     ),
            //     child: Column(
            //       children: [

            //         // --- SLIDER LỌC RATING (Được thu gọn tinh tế vào bên trong thẻ trắng) ---
            //         Padding(
            //           padding: const EdgeInsets.fromLTRB(20, 20, 20, 0),
            //           child: Row(
            //             children: [
            //               Icon(Icons.star, color: _primaryOrange, size: 20),
            //               const SizedBox(width: 8),
            //               Text(
            //                 "Rating > ${_minRating.toStringAsFixed(1)}",
            //                 style: TextStyle(color: _textBrown, fontWeight: FontWeight.w600),
            //               ),
            //               Expanded(
            //                 child: Slider(
            //                   value: _minRating,
            //                   min: 0.0,
            //                   max: 5.0,
            //                   divisions: 10,
            //                   activeColor: _primaryOrange,
            //                   inactiveColor: Colors.grey.shade300,
            //                   onChanged: _onRatingChanged,
            //                 ),
            //               ),
            //             ],
            //           ),
            //         ),

            //         // --- DANH SÁCH CHEF ---
            //         Expanded(
            //           child: _isLoading
            //               ? Center(child: CircularProgressIndicator(color: _primaryOrange))
            //               : _filteredChefs.isEmpty
            //                   ? Center(
            //                       child: Text(
            //                         "Không tìm thấy đầu bếp nào!",
            //                         style: TextStyle(color: Colors.grey.shade600, fontSize: 16),
            //                       ),
            //                     )
            //                   : ListView.separated(
            //                       physics: const BouncingScrollPhysics(),
            //                       padding: const EdgeInsets.fromLTRB(20, 10, 20, 20),
            //                       itemCount: _filteredChefs.length,
            //                       separatorBuilder: (context, index) => const SizedBox(height: 16),
            //                       itemBuilder: (context, index) {
            //                         return _buildChefCard(_filteredChefs[index]);
            //                       },
            //                     ),
            //         ),
            //       ],
            //     ),
            //   ),
            // ),
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
                    // --- SLIDER LỌC RATING ---
                    // Padding(
                    //   padding: const EdgeInsets.fromLTRB(20, 20, 20, 10), // Đổi bottom padding thành 10 cho cân đối
                    //   child: Row(
                    //     children: [
                    //       Icon(Icons.star, color: _primaryOrange, size: 20),
                    //       const SizedBox(width: 8),
                    //       Text(
                    //         "Rating > ${_minRating.toStringAsFixed(1)}",
                    //         style: TextStyle(color: _textBrown, fontWeight: FontWeight.w600),
                    //       ),
                    //       Expanded(
                    //         child: Slider(
                    //           value: _minRating,
                    //           min: 0.0,
                    //           max: 5.0,
                    //           divisions: 10,
                    //           activeColor: _primaryOrange,
                    //           inactiveColor: Colors.grey.shade300,
                    //           onChanged: _onRatingChanged,
                    //         ),
                    //       ),
                    //     ],
                    //   ),
                    // ),

                    // 👇 TECH LEAD THÊM: CÁCH 1 - QUICK FILTER CHIPS ---
                    // SizedBox(
                    //   height: 36, // Chiều cao vừa đủ cho các nút bấm
                    //   child: ListView(
                    //     scrollDirection: Axis.horizontal,
                    //     physics: const BouncingScrollPhysics(),
                    //     padding: const EdgeInsets.symmetric(horizontal: 20),
                    //     children: [
                    //       _buildFilterChip("🔥 Nổi bật", isSelected: true),
                    //       const SizedBox(width: 10),
                    //       _buildFilterChip("🍣 Món Á", isSelected: false),
                    //       const SizedBox(width: 10),
                    //       _buildFilterChip("🍕 Món Âu", isSelected: false),
                    //       const SizedBox(width: 10),
                    //       _buildFilterChip("🥗 Healthy", isSelected: false),
                    //     ],
                    //   ),
                    // ),
                    // const SizedBox(height: 16),

                    // 👇 TECH LEAD THÊM: CÁCH 2 - RESULT COUNTER ---
                    Padding(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 20.0, vertical: 4.0),
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Text(
                            "Found ${_filteredChefs.length} " +
                                (_filteredChefs.length > 1 ? "chefs" : "chef"),
                            style: TextStyle(
                              fontSize: 15,
                              fontWeight: FontWeight.bold,
                              color: _textBrown,
                            ),
                          ),
                          // Icon sắp xếp tạo cảm giác chuyên nghiệp
                          PopupMenuButton<SortOption>(
                            // Giao diện khi menu bật lên
                            shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(12)),
                            color: Colors.white,
                            elevation: 5,

                            // Sự kiện khi người dùng chọn 1 mục
                            onSelected: (SortOption result) {
                              setState(() {
                                if (_currentSort == result) {
                                  // Nếu user bấm TRÙNG vào mục đang chọn -> Đảo chiều
                                  _isDescending = !_isDescending;
                                } else {
                                  // Nếu chọn mục MỚI -> Gán mục mới và reset về chiều mặc định hợp lý
                                  _currentSort = result;
                                  // Logic UX: Tên thì mặc định A-Z (asc), còn Rating/Orders thì mặc định Cao->Thấp (desc)
                                  _isDescending = (result != SortOption.name);
                                }
                              });

                              // Gọi lại API từ trang 1 với tham số đã được đảo chiều
                              _loadAllChefs();
                            },

                            // Khai báo các lựa chọn
                            itemBuilder: (BuildContext context) =>
                                <PopupMenuEntry<SortOption>>[
                              PopupMenuItem<SortOption>(
                                value: SortOption.rating,
                                child: Text(
                                  '⭐ Rating (Highest)',
                                  style: TextStyle(
                                    color: _currentSort == SortOption.rating
                                        ? _primaryOrange
                                        : _textBrown,
                                    fontWeight:
                                        _currentSort == SortOption.rating
                                            ? FontWeight.bold
                                            : FontWeight.normal,
                                  ),
                                ),
                              ),
                              PopupMenuItem<SortOption>(
                                value: SortOption.orders,
                                child: Text(
                                  '🔥 Orders (Highest)',
                                  style: TextStyle(
                                    color: _currentSort == SortOption.orders
                                        ? _primaryOrange
                                        : _textBrown,
                                    fontWeight:
                                        _currentSort == SortOption.orders
                                            ? FontWeight.bold
                                            : FontWeight.normal,
                                  ),
                                ),
                              ),
                              PopupMenuItem<SortOption>(
                                value: SortOption.name,
                                child: Text(
                                  '🔤 Name (A -> Z)',
                                  style: TextStyle(
                                    color: _currentSort == SortOption.name
                                        ? _primaryOrange
                                        : _textBrown,
                                    fontWeight: _currentSort == SortOption.name
                                        ? FontWeight.bold
                                        : FontWeight.normal,
                                  ),
                                ),
                              ),
                            ],

                            // CÁI NÚT HIỂN THỊ TRÊN MÀN HÌNH (Chính là code cũ của bạn)
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

                    // --- DANH SÁCH CHEF ---
                    Expanded(
                      child: _isLoading
                          ? Center(
                              child: CircularProgressIndicator(
                                  color: _primaryOrange))
                          : _filteredChefs.isEmpty
                              ? Center(child: Text("Couldn't find any chefs!"))
                              : ListView.separated(
                                  controller:
                                      _scrollController, // 👈 GẮN CONTROLLER VÀO ĐÂY
                                  physics: const BouncingScrollPhysics(),
                                  padding:
                                      const EdgeInsets.fromLTRB(20, 0, 20, 20),

                                  // 👈 CỘNG THÊM 1 ITEM VÀO CUỐI ĐỂ VẼ VÒNG XOAY LOADING
                                  itemCount: _filteredChefs.length +
                                      (_isFetchingMore ? 1 : 0),
                                  separatorBuilder: (context, index) =>
                                      const SizedBox(height: 16),
                                  itemBuilder: (context, index) {
                                    // Nếu cuộn đến phần tử cuối cùng được cộng thêm -> Vẽ loading xoay xoay
                                    if (index == _filteredChefs.length) {
                                      return Padding(
                                        padding: const EdgeInsets.symmetric(
                                            vertical: 16.0),
                                        child: Center(
                                          child: CircularProgressIndicator(
                                              color: _primaryOrange,
                                              strokeWidth: 3),
                                        ),
                                      );
                                    }

                                    // Vẽ Card bình thường
                                    return _buildChefCard(
                                        _filteredChefs[index]);
                                  },
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

  // Hàm phụ trợ vẽ các nút Quick Filter
  Widget _buildFilterChip(String label, {required bool isSelected}) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      decoration: BoxDecoration(
        color: isSelected ? _primaryOrange : Colors.white,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(
          color: isSelected ? _primaryOrange : Colors.grey.shade300,
          width: 1,
        ),
      ),
      alignment: Alignment.center,
      child: Text(
        label,
        style: TextStyle(
          color: isSelected ? Colors.white : Colors.grey.shade700,
          fontWeight: FontWeight.bold,
          fontSize: 13,
        ),
      ),
    );
  }

  // 👇 TECH LEAD: THẺ CHEF ĐƯỢC GIỮ NGUYÊN 100% KHÔNG THAY ĐỔI
  Widget _buildChefCard(ChefModel chef) {
    // Xử lý logic hiển thị số (vì number_of_orders có thể null hoặc kiểu int)
    int orders = chef.numberOfOrders ?? 0;
    String formattedOrders = orders > 1000
        ? "${(orders / 1000).toStringAsFixed(1)}K"
        : orders.toString();

    // 👇 TECH LEAD: Bọc GestureDetector ở ngoài cùng để bắt sự kiện chạm
    return GestureDetector(
      onTap: () {
        Navigator.pushNamed(context, '/detail-chef',
            arguments: chef.userId.toString());
      },
      child: Container(
        height: 140,
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(16),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withOpacity(0.04),
              blurRadius: 10,
              offset: const Offset(0, 4),
            ),
          ],
        ),
        child: Row(
          children: [
            // 1. ẢNH MÓN ĂN PLACEHOLDER
            ClipRRect(
              borderRadius: const BorderRadius.only(
                topLeft: Radius.circular(16),
                bottomLeft: Radius.circular(16),
              ),
              child: SizedBox(
                width: 130,
                height: double.infinity,
                child: Image.network(
                  "https://images.unsplash.com/photo-1546069901-ba9599a7e63c?q=80&w=600&auto=format&fit=crop",
                  cacheWidth: 260,
                  cacheHeight: 280,
                  fit: BoxFit.cover,
                ),
              ),
            ),

            // 2. THÔNG TIN CHEF
            Expanded(
              child: Padding(
                padding: const EdgeInsets.all(12.0),
                child: Stack(
                  children: [
                    Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Padding(
                          padding: const EdgeInsets.only(right: 50),
                          child: Text(
                            chef.fullname,
                            style: TextStyle(
                              fontSize: 18,
                              fontWeight: FontWeight.w900,
                              color: _textBrown,
                            ),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          chef.specialty,
                          style: TextStyle(
                            fontSize: 14,
                            color: _textBrown.withOpacity(0.7),
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                        const Spacer(),
                        Container(
                          padding: const EdgeInsets.symmetric(
                              horizontal: 8, vertical: 4),
                          decoration: BoxDecoration(
                            color: _primaryOrange.withOpacity(0.2),
                            borderRadius: BorderRadius.circular(6),
                          ),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Icon(Icons.star, color: _primaryRed, size: 16),
                              const SizedBox(width: 4),
                              Text(
                                "${chef.rating.toStringAsFixed(1)} ($formattedOrders orders)",
                                style: TextStyle(
                                  fontWeight: FontWeight.bold,
                                  fontSize: 13,
                                  color: _textBrown,
                                ),
                              ),
                            ],
                          ),
                        ),
                        const SizedBox(height: 8),
                      ],
                    ),

                    // 3. AVATAR LƠ LỬNG
                    Positioned(
                      top: 0,
                      right: 0,
                      child: Container(
                        decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            border: Border.all(color: Colors.white, width: 2),
                            boxShadow: [
                              BoxShadow(
                                color: _primaryOrange.withOpacity(0.3),
                                blurRadius: 6,
                                offset: const Offset(0, 3),
                              )
                            ]),
                        child: CircleAvatar(
                          radius: 24,
                          backgroundColor: _backgroundColor,
                          backgroundImage: (chef.imageUrl != null &&
                                  chef.imageUrl!.isNotEmpty)
                              ? NetworkImage(chef.imageUrl!)
                              : null,
                          child:
                              (chef.imageUrl == null || chef.imageUrl!.isEmpty)
                                  ? Icon(Icons.person, color: _textBrown)
                                  : null,
                        ),
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
