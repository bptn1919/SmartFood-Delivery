import 'package:flutter/material.dart';
import 'package:testing/features/common/app_components.dart';
import 'package:testing/features/common/nutrition_recommend_card.dart';
import 'package:testing/features/common/recommend_dish_card.dart';
import 'package:testing/features/home/presentations/dish_detail_page.dart';
import 'package:testing/features/recommend/models/daily_meal_model.dart';
import 'package:testing/features/recommend/models/nutrition_profile_model.dart';
import 'package:testing/features/recommend/models/nutrition_recommended_dish_model.dart';
import 'package:testing/features/recommend/models/recommendation_feed_model.dart';
import 'package:testing/features/recommend/models/recommendation_model.dart';
import 'package:testing/features/recommend/presentations/edit_metrics_bottom_sheet.dart';
import 'package:testing/features/recommend/presentations/widgets/daily_nutrition_section.dart';
import 'package:testing/features/recommend/repositories/recommend_repository.dart';
class RecommendPage extends StatefulWidget {
  const RecommendPage({super.key});

  @override
  State<RecommendPage> createState() => _RecommendPageState();
}

class _RecommendPageState extends State<RecommendPage> {
  final RecommendRepository _recommendRepository = RecommendRepository();
  // --- MÀU SẮC THEME ---
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _inputFillColor = Colors.grey.shade200;
  final Color _textBrown = Colors.brown.shade900;

  // --- TAB 2 STATE ---
  bool _isTrackingNutrition = false;
  final TextEditingController _mealLogController = TextEditingController();

  bool _isParsingMeal = false;

  bool _isLoadingBalanced = false;
  BalancedRecommendationResponse? _balancedData;
  List<NutritionRecommendedDishModel> _balancedDishes = [];

  DailyMealResponse? _dailyMealFuture; // Biến lưu trữ Future của Daily Meal, khởi tạo bằng null
  bool _isLoadingDailyMeal = false;

  // --- TAB 1 STATE ---
  bool _isLoadingPreferences = true;
  List<RecommendedDishModel> _recommendedDishes = [];

  NutritionProfileModel? _userProfile;
  bool _isLoadingProfile = false;

  @override
  void initState() {
    super.initState();
    _fetchRecommendationFeed();
    _fetchDailyMeal();
    _fetchNutritionProfile();
  }

  @override
  void dispose() {
    _mealLogController.dispose(); 
    
    super.dispose(); // <-- Bắt buộc phải là super.dispose() và nằm ở cuối cùng
  }

  Future<void> _fetchNutritionProfile() async {
    setState(() => _isLoadingProfile = true);
    
    final profile = await _recommendRepository.getNutritionProfile();

    debugPrint("Fetched Nutrition Profile: {$profile.toString()}");
    
    if (mounted) {
      setState(() {
        _userProfile = profile;
        _isLoadingProfile = false;
      });
    }
  }

  Future<void> _fetchDailyMeal() async {
    setState(() => _isLoadingDailyMeal = true);
    
    final profile = await _recommendRepository.getDailyMeals();

    debugPrint("Fetched Daily Meals: {$profile.toString()}");
    
    if (mounted) {
      setState(() {
        _dailyMealFuture = profile;
        _isLoadingDailyMeal = false;
      });
    }
  }

  Future<void> _fetchRecommendationFeed() async {
    // 1. Bật Loading
    setState(() => _isLoadingPreferences = true);

    try {
      debugPrint("Fetching recommendation feed from API...");
      
      // 2. Gọi API
      final response = await _recommendRepository.getMyRecommendationFeed(
        limit: 20, 
        offset: 0, 
        includeExplain: true
      );
      
      debugPrint("API response received: $response");
      
      // 3. Xử lý dữ liệu nếu thành công
      if (response != null && response.items != null) {
        _recommendedDishes = response.items!;
      } else {
        // Trường hợp API trả về null (có thể do lỗi 404 hoặc lỗi server)
        _recommendedDishes = [];
      }

    } catch (e) {
      // 4. Bắt lỗi nếu app bị văng (Crash / Exception)
      debugPrint("🚨 Exception in _fetchRecommendationFeed: $e");
      
      // (Tùy chọn) Báo lỗi cho người dùng biết
      if (mounted) {
        showAppSnackBar(
          context,
          "Failed to load recommendations. Please try again later.",
          type: SnackBarType.error,
        );
      }
    } finally {
      // 5. Tắt Loading CHUẨN XÁC: Luôn luôn chạy vào đây dù try hay catch
      if (mounted) {
        setState(() => _isLoadingPreferences = false);
      }
    }
  }

  Future<void> _fetchBalancedRecommendations() async {
    setState(() => _isLoadingBalanced = true);
    
    try {
      final response = await _recommendRepository.getDailyBalancedRecommendations(limit: 10);
      
      if (mounted && response != null) {
        setState(() {
          _balancedData = response;
          // 👇 TECH LEAD FIX 3: Gán thẳng luôn vì Model đã lo việc parse rồi!
          _balancedDishes = response.items ?? []; 
        });
      }
    } catch (e) {
      debugPrint("🚨 Error _fetchBalancedRecommendations: $e");
    } finally {
      if (mounted) {
        setState(() => _isLoadingBalanced = false);
      }
    }
  }

  Future<void> _handleParseMeal() async {
    final text = _mealLogController.text.trim();
    if (text.isEmpty) return;

    // Hạ bàn phím ngay lập tức để UX mượt mà
    FocusScope.of(context).unfocus(); 

    setState(() => _isParsingMeal = true);

    try {
      // Gọi API AI
      final response = await _recommendRepository.parseDailyMeal(text: text);

      if (!mounted) return;

      if (response == null) {
        showAppSnackBar(
          context,
          "Server AI is currently busy. Please try again later!",
          type: SnackBarType.error,
        );
        return;
      }

      // Xóa trắng ô input khi gửi thành công
      _mealLogController.clear();

      // Cảnh báo nếu AI không nhận diện được món nào đó
      if (response.unresolvedMeals != null && response.unresolvedMeals!.isNotEmpty) {
        final unresolved = response.unresolvedMeals!.join(", ");
        showAppSnackBar(
          context,
          "Confirmed but unresolved meals: $unresolved",
          type: SnackBarType.warning,
        );
      } else {
        showAppSnackBar(
          context,
          "Confirmed!",
          type: SnackBarType.success,
        );
      }

      if (_isTrackingNutrition) {
        _fetchBalancedRecommendations();
      }

    } catch (e) {
      debugPrint("Lỗi khi parse meal: $e");
    } finally {
      if (mounted) setState(() => _isParsingMeal = false);
    }
  }

  void _showEditMealSheet(BuildContext context, MealItem item) {
    final nameCtrl = TextEditingController(text: item.dishName);
    String selectedTime = item.mealTime.isNotEmpty ? item.mealTime : 'BREAKFAST';
    final quantityCtrl = TextEditingController(text: item.quantityMultiplier.toString());
    bool isUpdating = false;

    // Danh sách các bữa ăn chuẩn
    final List<String> mealTimes = ['BREAKFAST', 'LUNCH', 'DINNER', 'SNACK'];

    showModalBottomSheet(
      context: context,
      isScrollControlled: true, 
      backgroundColor: Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) {
        return StatefulBuilder(
          builder: (BuildContext context, StateSetter setModalState) {
            return Padding(
              padding: EdgeInsets.only(
                bottom: MediaQuery.of(ctx).viewInsets.bottom,
                left: 24, right: 24, top: 24,
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text("Edit Meal Log", style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                  const SizedBox(height: 20),

                  // Sửa Tên Món
                  const Text("Meal Name", style: TextStyle(fontWeight: FontWeight.w600, fontSize: 14)),
                  const SizedBox(height: 8),
                  TextField(
                    controller: nameCtrl,
                    decoration: InputDecoration(
                      filled: true,
                      fillColor: Colors.grey.shade100,
                      border: OutlineInputBorder(borderRadius: BorderRadius.circular(10), borderSide: BorderSide.none),
                      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
                    ),
                  ),
                  const SizedBox(height: 16),

                  const Text("Quantity (Multiplier)", style: TextStyle(fontWeight: FontWeight.w600, fontSize: 14)),
                  const SizedBox(height: 8),
                  TextField(
                    controller: quantityCtrl,
                    keyboardType: const TextInputType.numberWithOptions(decimal: true), // Gọi bàn phím số
                    decoration: InputDecoration(
                      filled: true,
                      fillColor: Colors.grey.shade100,
                      border: OutlineInputBorder(borderRadius: BorderRadius.circular(10), borderSide: BorderSide.none),
                      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
                    ),
                  ),
                  const SizedBox(height: 16),

                  // Sửa Bữa Ăn (Dropdown)
                  const Text("Meal Time", style: TextStyle(fontWeight: FontWeight.w600, fontSize: 14)),
                  const SizedBox(height: 8),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    decoration: BoxDecoration(color: Colors.grey.shade100, borderRadius: BorderRadius.circular(10)),
                    child: DropdownButtonHideUnderline(
                      child: DropdownButton<String>(
                        value: mealTimes.contains(selectedTime) ? selectedTime : mealTimes.first,
                        isExpanded: true,
                        items: mealTimes.map((time) => DropdownMenuItem(value: time, child: Text(time))).toList(),
                        onChanged: (val) => setModalState(() => selectedTime = val!),
                      ),
                    ),
                  ),
                  const SizedBox(height: 30),

                  // Nút Save
                  SizedBox(
                    width: double.infinity,
                    height: 50,
                    child: ElevatedButton(
                      style: ElevatedButton.styleFrom(
                        backgroundColor: const Color(0xFFE84D67),
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                      ),
                      onPressed: isUpdating ? null : () async {
                        setModalState(() => isUpdating = true);

                        // GỌI API PATCH
                        final success = await _recommendRepository.updateDailyMeal(
                          mealUid: item.uid,
                          mealName: nameCtrl.text.trim(),
                          mealTime: selectedTime,
                        );

                        if (success) {
                          Navigator.pop(ctx); // Đóng BottomSheet
                          _fetchDailyMeal();  // 👇 Gọi lại hàm fetch để làm mới UI
                          showAppSnackBar(context, "Meal updated!", type: SnackBarType.success);
                        } else {
                          setModalState(() => isUpdating = false);
                          ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(content: Text("Failed to update"), backgroundColor: Colors.red),
                          );
                        }
                      },
                      child: isUpdating
                          ? const CircularProgressIndicator(color: Colors.white)
                          : const Text("Save Changes", style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold)),
                    ),
                  ),
                  const SizedBox(height: 24),
                ],
              ),
            );
          },
        );
      },
    ).whenComplete(() {
      nameCtrl.dispose();
      quantityCtrl.dispose();
    });
  }

  @override
  Widget build(BuildContext context) {
    // Bọc toàn bộ bằng DefaultTabController cho 2 Tabs
    return DefaultTabController(
      length: 2,
      child: Scaffold(
        backgroundColor: Colors.grey[50], // Nền xám thật nhạt để nổi bật các Card trắng
        appBar: AppBar(
          backgroundColor: Colors.white,
          elevation: 0,
          title: Text("Recommendations", style: TextStyle(color: _textBrown, fontWeight: FontWeight.bold)),
          centerTitle: true,
          // CẤU HÌNH TAB BAR
          bottom: TabBar(
            labelColor: _primaryRed,
            unselectedLabelColor: Colors.grey,
            indicatorColor: _primaryRed,
            indicatorWeight: 3,
            labelStyle: const TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
            tabs: const [
              Tab(text: "By Preferences"),
              Tab(text: "Track Nutrition"),
            ],
          ),
        ),
        body: TabBarView(
          children: [
            _buildPreferenceTab(),
            _buildNutritionTab(),
          ],
        ),
      ),
    );
  }

  // ==========================================
  // TAB 1: GỢI Ý THEO SỞ THÍCH
  // ==========================================
  Widget _buildPreferenceTab() {
    if (_isLoadingPreferences) {
      return Center(child: CircularProgressIndicator(color: _primaryRed));
    }

    if (_recommendedDishes.isEmpty) {
      return Center(
        child: Text("No recommendations found.\nTry updating your preferences!", 
          textAlign: TextAlign.center, style: TextStyle(color: Colors.grey)),
      );
    }

    return RefreshIndicator(
      color: _primaryRed,
      onRefresh: _fetchRecommendationFeed, 
      child: ListView.builder(
        padding: const EdgeInsets.all(16),
        itemCount: _recommendedDishes.length,
        itemBuilder: (context, index) {
          final dish = _recommendedDishes[index];
          
          return Padding(
            padding: const EdgeInsets.only(bottom: 16),
            child: SizedBox(
              height: 220, 
              child: RecommendedDishListCard(dish: dish)
            ),
          );
        },
      ),
    );
  }

  // ==========================================
  // TAB 2: GỢI Ý THEO DINH DƯỠNG
  // ==========================================
  Widget _buildNutritionTab() {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 1. TOGGLE TRACKING NUTRITION
          Card(
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
            elevation: 2,
            child: SwitchListTile(
              activeColor: _primaryRed,
              contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              title: Text("Track Nutrition Today", style: TextStyle(fontWeight: FontWeight.bold, color: _textBrown)),
              subtitle: const Text("Receive AI-powered recommendations based on your daily targets.", style: TextStyle(fontSize: 12)),
              value: _isTrackingNutrition,
              onChanged: (val) {
                setState(() => _isTrackingNutrition = val);
                if (val && _balancedData == null) {
                  _fetchBalancedRecommendations();
                }
              },
            ),
          ),
          const SizedBox(height: 24),

          // 2. KHU VỰC CHỈ SỐ CƠ THỂ & CHỈNH SỬA
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text("Your Body Metrics", style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: _textBrown)),
              OutlinedButton.icon(
                onPressed: _showEditMetricsBottomSheet,
                icon: const Icon(Icons.edit, size: 16),
                label: const Text("Edit"),
                style: OutlinedButton.styleFrom(
                  foregroundColor: _primaryRed,
                  side: BorderSide(color: _primaryRed),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          
          // Row hiển thị nhanh 3 chỉ số cơ bản
          Row(
            children: [
              _buildMetricCard("Age", _userProfile?.age != null ? "${_userProfile!.age} yrs" : "N/A"),
              const SizedBox(width: 12),
              _buildMetricCard("Weight", _userProfile?.weightKg != null ? "${_userProfile!.weightKg} kg" : "N/A"),
              const SizedBox(width: 12),
              _buildMetricCard("Height", _userProfile?.heightCm != null ? "${_userProfile!.heightCm} cm" : "N/A"),
            ],
          ),
          const SizedBox(height: 24),

          

          // 3. INPUT GHI NHẬN BỮA ĂN NGOÀI (FREE TEXT)
          Text("Log Outside Meals", style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: _textBrown)),
          const SizedBox(height: 8),
          Container(
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: Colors.grey.shade300),
              boxShadow: [
                BoxShadow(color: Colors.black.withOpacity(0.02), blurRadius: 8, offset: const Offset(0, 2))
              ]
            ),
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _mealLogController,
                    maxLines: 2,
                    minLines: 1, // Tự động dãn dòng
                    enabled: !_isParsingMeal,
                    decoration: InputDecoration(
                      hintText: "E.g., I ate a salad for lunch...",
                      hintStyle: TextStyle(color: Colors.grey.shade400, fontSize: 14),
                      border: InputBorder.none,
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                Container(
                  width: 40,
                  height: 40,
                  decoration: BoxDecoration(
                    // Chuyển màu xám nếu đang loading
                    color: _isParsingMeal ? Colors.grey.shade400 : _primaryRed, 
                    shape: BoxShape.circle
                  ),
                  child: _isParsingMeal
                      ? const Padding(
                          padding: const EdgeInsets.all(10.0),
                          child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2.5),
                        )
                      : IconButton(
                          icon: const Icon(Icons.send_rounded, color: Colors.white, size: 18),
                          onPressed: _handleParseMeal, // Gắn hàm vào đây
                        ),
                ),
              ],
            ),
          ),
          DailyNutritionSection(
            data: _dailyMealFuture, // Dữ liệu đã lấy xong
            isLoading: _isLoadingDailyMeal, // Trạng thái để hiện vòng xoay
            onMealTap: (MealItem item) {
              _showEditMealSheet(context, item);
            },
          ),
          
          if (_isTrackingNutrition) ...[
            Text("Suggested for You", style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: _textBrown)),
            const SizedBox(height: 12),
            
            // 👇 TECH LEAD FIX: Đã xóa lệnh if bị lặp ở đây
            
            // 4.1 BẢNG TÓM TẮT NĂNG LƯỢNG (SUMMARY)
            if (_balancedData?.summary != null) ...[
              _buildNutritionSummaryBoard(_balancedData!.summary!),
              const SizedBox(height: 24),
            ],

            // 4.2 DANH SÁCH MÓN ĂN CÂN BẰNG
            Text("Suggested to balance your macros", style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: _textBrown)),
            const SizedBox(height: 12),
            
            if (_isLoadingBalanced)
              const Center(child: Padding(
                padding: const EdgeInsets.all(20.0),
                child: CircularProgressIndicator(color: Color(0xFFE55866)),
              ))
            else if (_balancedDishes.isEmpty)
              const Center(child: Padding(
                padding: const EdgeInsets.all(20.0),
                child: Text("No recommendations available right now.", style: TextStyle(color: Colors.grey)),
              ))
            else
              // Bỏ SizedBox giới hạn chiều cao đi để thẻ tự động nở theo nội dung
              ..._balancedDishes.map((dish) => Padding(
                padding: const EdgeInsets.only(bottom: 16),
                child: NutritionRecommendationCard(
                  dish: dish, // Tham số này giờ đã hoàn toàn khớp kiểu dữ liệu
                  onTap: () {
                    if (dish.dishUid == null) return;
                    Navigator.push(
                      context,
                      MaterialPageRoute(
                        builder: (context) => DishDetailPage(dishId: dish.dishUid!),
                      ),
                    );
                  },
                ),
              )),
          ]
          ]
      ),
    );
  }

  // Helper Widget cho thẻ chỉ số
  Widget _buildMetricCard(String label, String value) {
    return Expanded(
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 16),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: Colors.grey.shade200),
        ),
        child: Column(
          children: [
            Text(label, style: const TextStyle(color: Colors.grey, fontSize: 12)),
            const SizedBox(height: 4),
            Text(value, style: TextStyle(color: _textBrown, fontWeight: FontWeight.bold, fontSize: 16)),
          ],
        ),
      ),
    );
  }

  Widget _buildNutritionSummaryBoard(NutritionSummary summary) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [BoxShadow(color: Colors.black.withOpacity(0.04), blurRadius: 10, offset: const Offset(0, 4))],
        border: Border.all(color: Colors.green.shade100, width: 2),
      ),
      child: Column(
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              const Text("Target (TDEE)", style: TextStyle(color: Colors.grey, fontWeight: FontWeight.w600)),
              Text("${summary.tdeeKcal.toStringAsFixed(0)} kcal", style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
            ],
          ),
          const Padding(
            padding: const EdgeInsets.symmetric(vertical: 16),
            child: Divider(height: 1),
          ),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text("Consumed", style: TextStyle(color: Colors.grey, fontSize: 13)),
                  const SizedBox(height: 4),
                  Text("${summary.consumed.energy.toStringAsFixed(0)} kcal", 
                      style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 18, color: Colors.blue)),
                ],
              ),
              Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  const Text("Remaining", style: TextStyle(color: Colors.grey, fontSize: 13)),
                  const SizedBox(height: 4),
                  Text("${summary.remaining.energy.toStringAsFixed(0)} kcal", 
                      style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 18, color: Colors.green)),
                ],
              )
            ],
          )
        ],
      ),
    );
  }

  // BOTTOM SHEET ĐỂ SỬA CHỈ SỐ NHANH
  void _showEditMetricsBottomSheet() {
    // Tránh việc bấm Edit khi dữ liệu GET chưa load xong
    if (_isLoadingProfile) return; 

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (context) {
        return EditProfileBottomSheet(
          currentProfile: _userProfile, // Đổ data GET vào
          onSuccess: () {
            // Đóng popup xong thì tự động gọi lại hàm GET
            // để lấy dữ liệu BMR/TDEE/Target mới tính toán từ Backend!
            _fetchNutritionProfile();
          },
        );
      },
    );
  }
}
