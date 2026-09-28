import 'package:flutter/material.dart';
import 'package:testing/features/chef_manager/presentations/chef_menu_detail_page.dart';
import 'package:testing/features/chef_manager/presentations/create_menu_bottom_sheet.dart';
import 'package:testing/features/home/models/menu_model.dart';
import '../repositories/chef_management_repository.dart';
import '../../home/models/dish_model.dart';
import 'widgets/chef_dish_card.dart';
import 'add_dish_page.dart';

class ChefComboCard extends StatelessWidget {
  final MenuModel menu;
  const ChefComboCard({super.key, required this.menu});

  @override
  Widget build(BuildContext context) {
    final bool isActive = menu.status == "ACTIVE"; 

    // 💡 TECH LEAD FIX: Bọc GestureDetector ở ngoài cùng để bắt sự kiện chạm
    return GestureDetector(
      onTap: () {
        // Thực hiện điều hướng sang trang Chi tiết Menu, truyền đúng object menu qua
        Navigator.push(
          context,
          MaterialPageRoute(
            builder: (context) => ChefMenuDetailPage(menu: menu),
          ),
        );
      },
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: isActive ? Colors.orange[50] : Colors.grey[100],
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: isActive ? Colors.orange[200]! : Colors.grey[300]!, width: 1.5),
        ),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.receipt_long, size: 40, color: isActive ? Colors.orange : Colors.grey),
            const SizedBox(height: 8),
            
            // Badge trạng thái
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
              decoration: BoxDecoration(
                color: isActive ? Colors.orange : Colors.grey, 
                borderRadius: BorderRadius.circular(4)
              ),
              child: Text(
                isActive ? "SET MENU" : "DRAFT", 
                style: const TextStyle(color: Colors.white, fontSize: 10, fontWeight: FontWeight.bold)
              ),
            ),
            const SizedBox(height: 8),
            
            // Tên menu
            Text(
              menu.name, 
              style: TextStyle(fontWeight: FontWeight.bold, color: isActive ? Colors.black87 : Colors.grey), 
              textAlign: TextAlign.center,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
            ),
            
            const SizedBox(height: 4),
            
            // Mô tả ngắn
            Text(
              menu.description ?? "No description", 
              style: const TextStyle(color: Colors.grey, fontSize: 12),
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }
}

// --- MAIN PAGE ---
enum ViewMode { menus, dishes } // 💡 TECH LEAD FIX: Phân định 2 chế độ rõ ràng

class ChefMenuPage extends StatefulWidget {
  const ChefMenuPage({super.key});

  @override
  State<ChefMenuPage> createState() => _ChefMenuPageState();
}

class _ChefMenuPageState extends State<ChefMenuPage> {
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _textBrown = const Color(0xFF4A3225);
  final Color _bgColor = const Color(0xFFF9F9F9); 
  
  final _repo = ChefManagementRepository();
  
  // --- STATE VARIABLES ---
  ViewMode _currentView = ViewMode.dishes; // Mặc định vào app là thấy list Món lẻ
  String _selectedDishCategory = "All";    // Filter riêng cho Món lẻ
  bool _isLoading = true;
  List<dynamic> _displayItems = []; 

  final List<String> _dishCategories = ["All", "Mains", "Desserts", "Beverages"];

  @override
  void initState() {
    super.initState();
    _loadData();
  }

  // Luồng tải dữ liệu tách biệt rõ ràng
  Future<void> _loadData() async {
    setState(() => _isLoading = true);
    
    try {
      if (_currentView == ViewMode.menus) {
        _displayItems = await _repo.getMyMenus();
      } else {
        // 🍲 CHẾ ĐỘ XEM MÓN LẺ
        String apiCategory = "All";
        if (_selectedDishCategory == "Mains") apiCategory = "meals";
        if (_selectedDishCategory == "Desserts") apiCategory = "dessert";
        if (_selectedDishCategory == "Beverages") apiCategory = "beverage";

        _displayItems = await _repo.getMyDishes(category: apiCategory);
      }
    } catch (e) {
      debugPrint("Error loading data: $e");
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  void _switchViewMode(ViewMode mode) {
    if (_currentView == mode) return;
    setState(() {
      _currentView = mode;
      _displayItems = []; // Xoá data cũ trong lúc chờ load data mới
    });
    _loadData();
  }

  void _changeDishCategory(String tab) {
    if (_selectedDishCategory == tab) return; 
    setState(() => _selectedDishCategory = tab);
    _loadData();
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      color: _bgColor,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Padding(
          //   padding: const EdgeInsets.fromLTRB(20, 20, 20, 16),
          //   child: Text("My Kitchen", style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold, color: _textBrown)),
          // ),
          // 👇 TECH LEAD: Thay header tĩnh bằng công tắc chuyển đổi giữa 2 chế độ Menu & Dish
          const SizedBox(height: 16),
          // 1. TẦNG 1: CHUYỂN ĐỔI ENTITY (Menus vs Dishes)
          _buildSegmentedControl(),
          const SizedBox(height: 16),

          // 2. TẦNG 2: BỘ LỌC CATEGORY (Chỉ hiện khi ở chế độ Dishes)
          if (_currentView == ViewMode.dishes) _buildCategoryTabs(),
          if (_currentView == ViewMode.dishes) const SizedBox(height: 8),

          // 3. GRID VIEW 
          Expanded(
            child: _isLoading 
              ? Center(child: CircularProgressIndicator(color: _primaryRed)) 
              : RefreshIndicator(
                  color: _primaryRed,
                  onRefresh: () async { _loadData(); },
                  child: GridView.builder(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
                    itemCount: _displayItems.length + 1,
                    gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                      crossAxisCount: 2,
                      childAspectRatio: 0.75,
                      crossAxisSpacing: 16,
                      mainAxisSpacing: 16,
                    ),
                    itemBuilder: (context, index) {
                      // 💡 NÚT ADD ĐỘNG: Tự đổi tên theo ngữ cảnh
                      if (index == 0) {
                        return AddNewDishCard(
                          onTap: () async {
                            if (_currentView == ViewMode.menus) {
                              
                              // 💡 TECH LEAD FIX: Gọi BottomSheet Tạo Menu
                              showModalBottomSheet(
                                context: context,
                                isScrollControlled: true,
                                backgroundColor: Colors.transparent,
                                builder: (context) {
                                  return CreateMenuBottomSheet(
                                    onMenuCreated: () {
                                      _loadData(); // Load lại mảng Combo ở trang ngoài
                                    },
                                  );
                                },
                              );

                            } else {
                              await Navigator.push(context, MaterialPageRoute(builder: (_) => const AddDishPage()));
                              _loadData();
                            }
                          },
                        );
                      }
                      
                      final item = _displayItems[index - 1];

                      if (item is MenuModel) {
                        return ChefComboCard(menu: item);
                      } 
                      else if (item is DishModel) {
                        return ChefDishCard(
                          dish: item,
                          onToggle: (val) async {
                            await _repo.toggleDishStatus(item.uid, val);
                            _loadData();
                          },
                        );
                      }
                      return const SizedBox(); 
                    },
                  ),
                ),
          ),
        ],
      ),
    );
  }

  // --- UI COMPONENTS ---

  // Công tắc lớn ở trên cùng
  Widget _buildSegmentedControl() {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 20),
      child: Container(
        height: 48,
        decoration: BoxDecoration(
          color: Colors.grey[200],
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          children: [
            Expanded(
              child: GestureDetector(
                onTap: () => _switchViewMode(ViewMode.dishes),
                child: Container(
                  decoration: BoxDecoration(
                    color: _currentView == ViewMode.dishes ? Colors.white : Colors.transparent,
                    borderRadius: BorderRadius.circular(10),
                    boxShadow: _currentView == ViewMode.dishes ? [const BoxShadow(color: Colors.black12, blurRadius: 4)] : [],
                  ),
                  margin: const EdgeInsets.all(4),
                  alignment: Alignment.center,
                  child: Text("Dishes", style: TextStyle(fontWeight: FontWeight.bold, color: _currentView == ViewMode.dishes ? _primaryRed : Colors.grey[600])),
                ),
              ),
            ),
            Expanded(
              child: GestureDetector(
                onTap: () => _switchViewMode(ViewMode.menus),
                child: Container(
                  decoration: BoxDecoration(
                    color: _currentView == ViewMode.menus ? Colors.white : Colors.transparent,
                    borderRadius: BorderRadius.circular(10),
                    boxShadow: _currentView == ViewMode.menus ? [const BoxShadow(color: Colors.black12, blurRadius: 4)] : [],
                  ),
                  margin: const EdgeInsets.all(4),
                  alignment: Alignment.center,
                  child: Text("Menus", style: TextStyle(fontWeight: FontWeight.bold, color: _currentView == ViewMode.menus ? Colors.orange[700] : Colors.grey[600])),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  // Thanh tab lọc loại món ăn (Chỉ hiện khi ở tab Dishes)
  Widget _buildCategoryTabs() {
    return SizedBox(
      height: 36,
      child: ListView.builder(
        scrollDirection: Axis.horizontal,
        physics: const BouncingScrollPhysics(),
        padding: const EdgeInsets.symmetric(horizontal: 16),
        itemCount: _dishCategories.length,
        itemBuilder: (context, index) {
          final tab = _dishCategories[index];
          final isSelected = _selectedDishCategory == tab;

          return GestureDetector(
            onTap: () => _changeDishCategory(tab),
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 200),
              margin: const EdgeInsets.symmetric(horizontal: 4),
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              decoration: BoxDecoration(
                color: isSelected ? _primaryRed : Colors.white,
                borderRadius: BorderRadius.circular(20),
                border: Border.all(color: isSelected ? _primaryRed : Colors.grey[300]!, width: 1),
              ),
              child: Center(
                child: Text(
                  tab,
                  style: TextStyle(
                    color: isSelected ? Colors.white : Colors.grey[600],
                    fontWeight: isSelected ? FontWeight.bold : FontWeight.w500,
                    fontSize: 13,
                  ),
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}