import 'package:flutter/material.dart';
import 'package:testing/features/common/app_components.dart';
import 'package:testing/features/profile/models/customer_profile_model.dart';
import 'package:testing/features/profile/repository/customer_profile_repository.dart';
import 'package:testing/features/recommend/models/ingredient_model.dart';
import 'package:testing/features/recommend/repositories/ingredient_repository.dart';
import 'dart:async';

class AllergyProfilePage extends StatefulWidget {
  const AllergyProfilePage({Key? key}) : super(key: key);

  @override
  State<AllergyProfilePage> createState() => _AllergyProfilePageState();
}

class _AllergyProfilePageState extends State<AllergyProfilePage> {
  bool _isLoading = true;

  final IngredientRepository _ingredientRepo = IngredientRepository();
  final CustomerProfileRepository _profileRepo = CustomerProfileRepository();

  String _selectedMode = "WARN";
  List<IngredientModel> _myAllergies = [];
  List<IngredientModel> _myFavourites = [];
  List<IngredientModel> _allIngredients = [];
  String _searchQuery = "";

  bool _isSaving = false;
  // Bảng màu custom theo hình ảnh
  final Color _peachColor = const Color(0xFFFFB894);
  final Color _darkBrownText = const Color(0xFF3E2723);
  final Color _lightGreyCard = const Color(0xFFF7F7F7);

  final Color _primaryRed = const Color(0xFFE55866);

  final ScrollController _scrollController = ScrollController();
  int _currentPage = 1;
  bool _isFetchingMore = false;
  bool _hasMoreData = true;

  Timer? _debounce;

  @override
  void initState() {
    super.initState();
    _fetchInitialData();
    _scrollController.addListener(_onScroll);
  }

  @override
  void dispose() {
    _scrollController.dispose(); // Nhớ dọn dẹp để tránh memory leak
    _debounce?.cancel();
    super.dispose();
  }

  // --- HÀM 1: LẮNG NGHE LÚC GÕ PHÍM (CÓ DEBOUNCE) ---
  void _onSearchChanged(String query) {
    // Nếu đang có 1 timer đếm ngược cũ thì hủy nó đi
    if (_debounce?.isActive ?? false) _debounce!.cancel();

    // Đặt 1 timer mới. Đợi user dừng gõ đúng 500ms mới bắt đầu gọi API
    _debounce = Timer(const Duration(milliseconds: 500), () {
      if (_searchQuery != query.trim()) {
        setState(() {
          _searchQuery = query.trim();
        });
        _searchIngredientsFromScratch(); // Gọi hàm lấy data mới
      }
    });
  }

  // --- HÀM 2: RESET VÀ GỌI API TRANG 1 ---
  Future<void> _searchIngredientsFromScratch() async {
    setState(() => _isLoading = true); // Hiện xoay tròn che danh sách lại

    try {
      _currentPage = 1; // Reset về trang 1
      _hasMoreData = true;

      // Gọi Backend tìm kiếm
      final newItems = await _ingredientRepo.getIngredients(
        page: _currentPage,
        search: _searchQuery.isNotEmpty ? _searchQuery : null,
      );

      if (mounted) {
        setState(() {
          _allIngredients =
              newItems; // Xóa danh sách cũ, thay bằng kết quả search
          if (newItems.length < 50) {
            _hasMoreData = false;
          }
          _isLoading = false;
        });
      }
    } catch (e) {
      debugPrint("Lỗi Search: $e");
      if (mounted) setState(() => _isLoading = false);
    }
  }

  void _onScroll() {
    // Nếu chưa load xong Initial Data, hoặc đang tải thêm, hoặc đã hết data thì bỏ qua
    if (_isLoading || _isFetchingMore || !_hasMoreData) return;

    // Kéo xuống cách đáy 200px là bắt đầu gọi API load thêm
    if (_scrollController.position.pixels >=
        _scrollController.position.maxScrollExtent - 200) {
      _fetchMoreIngredients();
    }
  }

  Future<void> _fetchMoreIngredients() async {
    setState(() => _isFetchingMore = true);

    try {
      _currentPage++; // Tăng trang lên (trang 2, 3,...)

      // 💡 TECH LEAD FIX: Truyền _currentPage vào hàm getIngredients
      final newItems = await _ingredientRepo.getIngredients(
        page: _currentPage,
        search: _searchQuery.isNotEmpty ? _searchQuery : null,
      );

      if (mounted) {
        setState(() {
          if (newItems.isEmpty) {
            _hasMoreData = false; // Đánh dấu đã hết hàng
          } else {
            // Nối list mới vào list cũ
            _allIngredients.addAll(newItems);

            // Nếu list mới trả về ít hơn 50 items (pageSize mặc định),
            // nghĩa là đây là trang cuối cùng rồi.
            if (newItems.length < 50) {
              _hasMoreData = false;
            }
          }
          _isFetchingMore = false;
        });
      }
    } catch (e) {
      debugPrint("Lỗi tải thêm data: $e");
      // Nếu lỗi, phải lùi _currentPage lại để user cuộn xuống nó load lại trang đó
      _currentPage--;
      if (mounted) setState(() => _isFetchingMore = false);
    }
  }

  Future<void> _fetchInitialData() async {
    try {
      _currentPage = 1;
      _hasMoreData = true;
      final results = await Future.wait([
        _ingredientRepo.getIngredients(page: _currentPage),
        _profileRepo.getCustomerProfile(),
        _ingredientRepo.getMyAllergies(),
        _ingredientRepo.getMyFavorites(),
      ]);

      final allIngredientsData = results[0] as List<IngredientModel>;
      final customerProfileData = results[1] as CustomerProfileModel?;
      final myAllergiesData = results[2] as List<IngredientModel>;
      final myFavouritesData = results[3] as List<IngredientModel>;

      if (mounted) {
        setState(() {
          _allIngredients = allIngredientsData;
          _selectedMode = customerProfileData?.allergyMode ?? "WARN";
          _myAllergies = myAllergiesData;
          _myFavourites = myFavouritesData;
          if (allIngredientsData.length < 50) {
            _hasMoreData = false;
          }
          _isLoading = false;
        });
      }
    } catch (e) {
      debugPrint("Lỗi tải data Allergy: $e");
      if (mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  // Hàm xử lý logic khi bấm nút Add
  Future<void> _handleAddAllergy(IngredientModel item) async {
    if (item.uid == null) {
      showAppSnackBar(context, "Error in adding allergy!",
          type: SnackBarType.error);
      return;
    }

    final success = await _ingredientRepo.addAllergyIngredient(item.uid!);

    // 3. Xử lý UI sau khi có kết quả
    if (mounted) {
      if (success) {
        // Cập nhật State để đẩy nguyên liệu từ List Gợi ý lên List Đã chọn
        setState(() {
          _myAllergies.add(item);
        });
        showAppSnackBar(
            context, "Successfully added ${item.name} to allergy list",
            type: SnackBarType.success);
      } else {
        showAppSnackBar(context, "Failed to add ingredient. Please try again!",
            type: SnackBarType.error);
      }
    }
  }

  // Hàm xử lý logic khi bấm dấu X để xóa nguyên liệu
  Future<void> _handleRemoveAllergy(IngredientModel item) async {
    if (item.uid == null) return;
    final success = await _ingredientRepo.removeAllergyIngredient(item.uid!);

    if (mounted) {
      if (success) {
        setState(() {
          _myAllergies.remove(item);
        });
        showAppSnackBar(context, "Removed ${item.name} from allergy list",
            type: SnackBarType.success);
      } else {
        showAppSnackBar(
            context, "Failed to remove ingredient. Please try again!",
            type: SnackBarType.error);
      }
    }
  }

  // Hàm xử lý logic khi bấm nút Add
  Future<void> _handleAddFavourites(IngredientModel item) async {
    if (item.uid == null) {
      showAppSnackBar(context, "Error in adding favourite!",
          type: SnackBarType.error);
      return;
    }

    final success = await _ingredientRepo.addFavoriteIngredient(item.uid!);

    // 3. Xử lý UI sau khi có kết quả
    if (mounted) {
      if (success) {
        // Cập nhật State để đẩy nguyên liệu từ List Gợi ý lên List Đã chọn
        setState(() {
          _myFavourites.add(item);
        });
        showAppSnackBar(
            context, "Successfully added ${item.name} to favourites list",
            type: SnackBarType.success);
      } else {
        showAppSnackBar(context, "Failed to add ingredient. Please try again!",
            type: SnackBarType.error);
      }
    }
  }

  // Hàm xử lý logic khi bấm dấu X để xóa nguyên liệu
  Future<void> _handleRemoveFavourite(IngredientModel item) async {
    if (item.uid == null) return;
    final success = await _ingredientRepo.removeFavoriteIngredient(item.uid!);

    if (mounted) {
      if (success) {
        setState(() {
          _myFavourites.remove(item);
        });
        showAppSnackBar(context, "Removed ${item.name} from favourites list",
            type: SnackBarType.success);
      } else {
        showAppSnackBar(
            context, "Failed to remove ingredient. Please try again!",
            type: SnackBarType.error);
      }
    }
  }

  Future<void> _handleSave() async {
    // 1. Bật trạng thái loading
    setState(() => _isSaving = true);

    try {
      debugPrint("Saving... Mode: $_selectedMode");

      final success = await _profileRepo.updateCustomerProfile(
        allergyMode: _selectedMode,
      );

      if (!mounted) return;

      // 3. Xử lý kết quả trả về
      if (success) {
        showAppSnackBar(context, "Update Allergy Mode Successfully!",
            type: SnackBarType.success);
        //Navigator.pop(context, true); // Trả về true báo cho màn trước biết đã update
      } else {
        showAppSnackBar(context, "Error in saving allergy mode!",
            type: SnackBarType.error);
      }
    } catch (e) {
      debugPrint("Error: $e");
      if (mounted) {
        showAppSnackBar(context, 'Error: ${e.toString()}',
            type: SnackBarType.error);
      }
    } finally {
      // 4. Tắt loading
      if (mounted) setState(() => _isSaving = false);
    }
  }

  // TECH LEAD FIX: Widget đường phân cách siêu mỏng màu cam
  Widget _buildThinDivider() {
    return Padding(
      padding: const EdgeInsets.symmetric(
          vertical: 20.0), // Tạo khoảng trống trên/dưới đường kẻ
      child: Divider(
        color: Colors.orange.withOpacity(0.3), // Màu cam nhẹ nhàng
        thickness: 0.5, // "Siêu mỏng" theo đúng ý bạn
        height: 1,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white, // Nền trắng chuẩn theo hình
      body: Container(
        color: _peachColor,
        child: Column(
          children: [
            _buildCustomHeader(),
            Expanded(
              child: Container(
                width: double.infinity,
                decoration: const BoxDecoration(
                  color: Colors.white, // Nền phần nội dung là màu trắng
                  // 👇 TECH LEAD FIX: Chuyển bo góc lên phần top của Body
                  borderRadius: const BorderRadius.vertical(top: Radius.circular(30)),
                ),
                // Cắt phần nội dung (như danh sách cuộn) không cho tràn ra ngoài góc bo
                clipBehavior: Clip.antiAlias,
                child: _isLoading
                    ? Center(
                        child: CircularProgressIndicator(color: _peachColor))
                    : _buildBodyContent(),
              ),
            ),
          ],
        ),
      ),

      bottomNavigationBar: _isLoading
          ? null
          : SafeArea(
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: ElevatedButton(
                  style: ElevatedButton.styleFrom(
                    backgroundColor: _primaryRed, // Đổi màu nút theo theme
                    minimumSize: const Size(double.infinity, 50),
                    elevation: 0,
                    shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(25)),
                  ),
                  onPressed: _handleSave,
                  child: const Text("Save changes",
                      style: TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.bold,
                          color: Colors.white)),
                ),
              ),
            ),
    );
  }

  // --- UI: Custom Header ---
  Widget _buildCustomHeader() {
    return Container(
      padding: EdgeInsets.only(
          top: MediaQuery.of(context).padding.top + 10,
          left: 8,
          right: 16,
          bottom: 12),
      decoration: BoxDecoration(
        color: _peachColor,
      ),
      child: Stack(
        alignment: Alignment.center,
        children: [
          // Nút Back đính sang bên trái
          Align(
            alignment: Alignment.centerLeft,
            child: IconButton(
              icon: const Icon(Icons.arrow_back_ios_new,
                  color: Colors.black87, size: 20),
              onPressed: () => Navigator.pop(context),
            ),
          ),

          Text(
            "Allergy Profile",
            style: TextStyle(
              fontSize: 24,
              fontWeight: FontWeight.w800,
              color: _darkBrownText,
            ),
          ),
        ],
      ),
    );
  }

  // --- UI: Nội dung Body ---
  Widget _buildBodyContent() {
    final displayIngredients = _allIngredients.where((item) {
      return !_myAllergies.contains(item);
    }).toList();

    return CustomScrollView(
      controller: _scrollController,
      slivers: [
        SliverPadding(
          padding: const EdgeInsets.symmetric(horizontal: 20.0),
          sliver: SliverToBoxAdapter(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const SizedBox(height: 20),
                // --- SECTION 1: ALLERGY MODE ---
                Text("Allergy Mode",
                    style: TextStyle(
                        fontWeight: FontWeight.bold,
                        fontSize: 16,
                        color: _darkBrownText)),
                const SizedBox(height: 8),
                DropdownButtonFormField<String>(
                  value: _selectedMode,
                  decoration: InputDecoration(
                    filled: true,
                    fillColor: _lightGreyCard,
                    border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(16),
                        borderSide: BorderSide.none),
                    contentPadding: const EdgeInsets.symmetric(
                        horizontal: 16, vertical: 14),
                  ),
                  items: const [
                    DropdownMenuItem(
                        value: "WARN", child: Text("Warning (Red Label)")),
                    DropdownMenuItem(
                        value: "HIDE", child: Text("Hide completely")),
                  ],
                  onChanged: (val) => setState(() => _selectedMode = val!),
                ),
                const SizedBox(height: 20),

                _buildThinDivider(),

                // --- SECTION 2: MY ALLERGIES ---
                Text("My Allergies",
                    style: TextStyle(
                        fontWeight: FontWeight.bold,
                        fontSize: 16,
                        color: _darkBrownText)),
                const SizedBox(height: 8),
                _myAllergies.isEmpty
                    ? const Text("No ingredients selected.",
                        style: TextStyle(
                            color: Colors.grey, fontStyle: FontStyle.italic))
                    : Wrap(
                        spacing: 8,
                        runSpacing: 4,
                        children: _myAllergies.map((item) {
                          return Chip(
                            label: Text(item.name ?? "Unnamed",
                                style: const TextStyle(color: Colors.white)),
                            backgroundColor:
                                const Color(0xFFF28B64), // Cam đậm hơn cho chip
                            deleteIcon: const Icon(Icons.close,
                                color: Colors.white, size: 16),
                            shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(20),
                                side: BorderSide.none),
                            onDeleted: () => _handleRemoveAllergy(item),
                          );
                        }).toList(),
                      ),
                const SizedBox(height: 20),

                _buildThinDivider(),

                Text("My Favourites",
                    style: TextStyle(
                        fontWeight: FontWeight.bold,
                        fontSize: 16,
                        color: _darkBrownText)),
                const SizedBox(height: 8),
                _myFavourites.isEmpty
                    ? const Text("No ingredients selected.",
                        style: TextStyle(
                            color: Colors.grey, fontStyle: FontStyle.italic))
                    : Wrap(
                        spacing: 8,
                        runSpacing: 4,
                        children: _myFavourites.map((item) {
                          return Chip(
                            label: Text(item.name ?? "Unnamed",
                                style: const TextStyle(color: Colors.white)),
                            backgroundColor:
                                const Color(0xFFF28B64), // Cam đậm hơn cho chip
                            deleteIcon: const Icon(Icons.close,
                                color: Colors.white, size: 16),
                            shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(20),
                                side: BorderSide.none),
                            onDeleted: () => _handleRemoveFavourite(item),
                          );
                        }).toList(),
                      ),
                const SizedBox(height: 20),

                _buildThinDivider(),

                Text("All Ingredients",
                    style: TextStyle(
                        fontWeight: FontWeight.bold,
                        fontSize: 16,
                        color: _darkBrownText)),
                const SizedBox(height: 10),

                Container(
                  decoration: BoxDecoration(
                    color: _lightGreyCard,
                    borderRadius: BorderRadius.circular(16),
                  ),
                  child: TextField(
                    decoration: const InputDecoration(
                      hintText: "Search ingredients...",
                      hintStyle: TextStyle(color: Colors.grey, fontSize: 14),
                      prefixIcon: Icon(Icons.search, color: Colors.grey),
                      border: InputBorder.none,
                      contentPadding: const EdgeInsets.symmetric(vertical: 14),
                    ),
                    onChanged: _onSearchChanged,
                  ),
                ),
                const SizedBox(height: 16),
              ],
            ),
          ),
        ),

        // --- SECTION 3: DANH SÁCH (CARD XÁM BO GÓC) ---
        SliverPadding(
          padding: const EdgeInsets.symmetric(horizontal: 20.0),
          sliver: SliverList(
            delegate: SliverChildBuilderDelegate(
              (context, index) {
                final item = displayIngredients[index];
                return Container(
                  margin: const EdgeInsets.only(bottom: 12),
                  decoration: BoxDecoration(
                    color: _lightGreyCard,
                    borderRadius: BorderRadius.circular(16),
                  ),
                  child: ListTile(
                    contentPadding:
                        const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
                    leading: CircleAvatar(
                      backgroundColor: Colors.grey[300],
                      child: const Icon(Icons.fastfood, color: Colors.grey),
                    ),
                    title: Text(
                      item.name ?? "Unnamed Ingredient",
                      style: TextStyle(
                          fontWeight: FontWeight.w600, color: _darkBrownText),
                    ),
                    trailing: Row(
                      mainAxisSize: MainAxisSize
                          .min, // Cực kỳ quan trọng để Row không bung tràn màn hình
                      children: [
                        // --- NÚT 1: ADD TO FAVORITE (TRÁI TIM) ---
                        Tooltip(
                          message: "Add to Favorites",
                          child: Container(
                            margin: const EdgeInsets.only(
                                right:
                                    12), // Tạo khoảng cách an toàn chống chạm nhầm
                            decoration: BoxDecoration(
                              color: Colors.white,
                              shape: BoxShape.circle,
                              boxShadow: [
                                BoxShadow(
                                    color: Colors.black.withOpacity(0.05),
                                    blurRadius: 4)
                              ],
                            ),
                            child: IconButton(
                              // Dùng màu đỏ hồng cho action yêu thích
                              icon: const Icon(Icons.favorite_border_rounded,
                                  color: Color(0xFFE84D67), size: 20),
                              onPressed: () {
                                // TODO: Gọi hàm xử lý thêm vào Favorite
                                _handleAddFavourites(item);
                              },
                            ),
                          ),
                        ),

                        // --- NÚT 2: ADD TO ALLERGY (DẤU CỘNG) ---
                        Tooltip(
                          message: "Add to Allergies",
                          child: Container(
                            decoration: BoxDecoration(
                              color: Colors.white,
                              shape: BoxShape.circle,
                              boxShadow: [
                                BoxShadow(
                                    color: Colors.black.withOpacity(0.05),
                                    blurRadius: 4)
                              ],
                            ),
                            child: IconButton(
                              // Giữ nguyên màu cam đào cho action Dị ứng như theme cũ
                              icon: const Icon(Icons.add,
                                  color: Color(0xFFF28B64), size: 20),
                              onPressed: () => _handleAddAllergy(item),
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                );
              },
              childCount: displayIngredients.length,
            ),
          ),
        ),

        if (_isFetchingMore)
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 20.0),
              child: Center(
                child: CircularProgressIndicator(color: _peachColor),
              ),
            ),
          ),

        const SliverToBoxAdapter(child: SizedBox(height: 20)),
      ],
    );
  }
}
