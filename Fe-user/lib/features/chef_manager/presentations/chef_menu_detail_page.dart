import 'package:flutter/material.dart';
import 'package:testing/features/chef_manager/repositories/chef_management_repository.dart';
import 'package:testing/features/common/custom_image.dart';
import 'package:testing/features/home/models/menu_model.dart';
import '../../common/app_components.dart'; // File chứa showAppSnackBar của bạn
import '../models/menu_dish_model.dart';
import '../../home/models/dish_model.dart'; // Đảm bảo đúng đường dẫn

class ChefMenuDetailPage extends StatefulWidget {
  final MenuModel menu; // Nhận menu từ trang trước truyền qua
  
  const ChefMenuDetailPage({super.key, required this.menu});

  @override
  State<ChefMenuDetailPage> createState() => _ChefMenuDetailPageState();
}

class _ChefMenuDetailPageState extends State<ChefMenuDetailPage> {
  final _repo = ChefManagementRepository();
  
  // Styling
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _textBrown = const Color(0xFF4A3225);

  // Form Controllers
  late TextEditingController _nameController;
  late TextEditingController _descController;
  late String _selectedStatus;

  // State Variables
  bool _isSavingInfo = false;
  bool _isLoadingDishes = true;
  List<MenuDishModel> _dishes = [];

  // Enum Status map cho Dropdown
  final List<String> _statusOptions = ["ACTIVE", "INACTIVE", "DRAFT"];

  @override
  void initState() {
    super.initState();
    // Khởi tạo form với data có sẵn
    _nameController = TextEditingController(text: widget.menu.name);
    _descController = TextEditingController(text: widget.menu.description);
    
    // Đảm bảo status lấy từ backend hợp lệ với mảng Option
    _selectedStatus = _statusOptions.contains(widget.menu.status) 
        ? widget.menu.status 
        : "DRAFT";

    _loadDishes();
  }

  @override
  void dispose() {
    _nameController.dispose();
    _descController.dispose();
    super.dispose();
  }

  // Lấy danh sách món ăn từ Backend
  Future<void> _loadDishes() async {
    setState(() => _isLoadingDishes = true);
    final dishes = await _repo.getDishesInMenu(widget.menu.uid);
    if (mounted) {
      setState(() {
        _dishes = dishes;
        _isLoadingDishes = false;
      });
    }
  }

  // Gọi API Update thông tin cơ bản
  Future<void> _onSaveInfoTapped() async {
    final name = _nameController.text.trim();
    if (name.isEmpty) {
      showAppSnackBar(context, "Menu name is required", type: SnackBarType.warning);
      return;
    }

    setState(() => _isSavingInfo = true);
    try {
      await _repo.updateMenuInfo(
        widget.menu.uid,
        name,
        _descController.text.trim(),
        _selectedStatus,
      );
      if (mounted) {
        showAppSnackBar(context, "Menu updated successfully!", type: SnackBarType.success);
        // Note: Ở hệ thống thực tế, bạn có thể trigger event để trang trước load lại data
      }
    } catch (e) {
      if (mounted) {
        showAppSnackBar(context, "Failed to update menu: $e", type: SnackBarType.error);
      }
    } finally {
      if (mounted) setState(() => _isSavingInfo = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.grey[50],
      appBar: AppBar(
        title: const Text("Menu Details", style: TextStyle(fontWeight: FontWeight.bold)),
        backgroundColor: Colors.white,
        foregroundColor: _textBrown,
        elevation: 0,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16.0),
        physics: const BouncingScrollPhysics(),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _buildBasicInfoSection(),
            const SizedBox(height: 24),
            _buildDishesSection(),
          ],
        ),
      ),
    );
  }

  // --- SECTION 1: CẬP NHẬT THÔNG TIN MENU ---
  Widget _buildBasicInfoSection() {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [BoxShadow(color: Colors.black.withOpacity(0.04), blurRadius: 10, offset: const Offset(0, 4))],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text("Basic Information", style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: _textBrown)),
              if (_selectedStatus == "ACTIVE")
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                  decoration: BoxDecoration(color: Colors.green[50], borderRadius: BorderRadius.circular(4)),
                  child: const Text("ACTIVE", style: TextStyle(color: Colors.green, fontSize: 12, fontWeight: FontWeight.bold)),
                ),
            ],
          ),
          const SizedBox(height: 16),
          
          TextField(
            controller: _nameController,
            decoration: InputDecoration(
              labelText: "Menu Name *",
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
            ),
          ),
          const SizedBox(height: 16),
          
          TextField(
            controller: _descController,
            maxLines: 3,
            decoration: InputDecoration(
              labelText: "Description (Optional)",
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
            ),
          ),
          const SizedBox(height: 16),
          
          DropdownButtonFormField<String>(
            value: _selectedStatus,
            decoration: InputDecoration(
              labelText: "Status",
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
            ),
            items: _statusOptions.map((status) {
              return DropdownMenuItem(value: status, child: Text(status));
            }).toList(),
            onChanged: (val) {
              if (val != null) setState(() => _selectedStatus = val);
            },
          ),
          const SizedBox(height: 20),
          
          SizedBox(
            width: double.infinity,
            height: 50,
            child: ElevatedButton(
              onPressed: _isSavingInfo ? null : _onSaveInfoTapped,
              style: ElevatedButton.styleFrom(
                backgroundColor: _primaryRed,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
              ),
              child: _isSavingInfo
                  ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2))
                  : const Text("Save Changes", style: TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.bold)),
            ),
          ),
        ],
      ),
    );
  }

  // --- SECTION 2: DANH SÁCH MÓN ĂN TRONG MENU ---
  Widget _buildDishesSection() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text("Dishes in Menu", style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: _textBrown)),
            TextButton.icon(
              onPressed: () {
                // 💡 TECH LEAD FIX: Mở BottomSheet chọn món
                showModalBottomSheet(
                  context: context,
                  isScrollControlled: true, // Cho phép set height tuỳ ý
                  backgroundColor: Colors.transparent, // Làm nền trong suốt để thấy viền bo góc
                  builder: (context) {
                    return _AddDishBottomSheet(
                      menuUid: widget.menu.uid,
                      onDishAdded: () {
                        // Gọi lại hàm load danh sách món của trang cha khi add xong
                        _loadDishes(); 
                      },
                    );
                  },
                );
              },
              icon: Icon(Icons.add_circle, color: _primaryRed),
              label: Text("Add Dish", style: TextStyle(color: _primaryRed, fontWeight: FontWeight.bold)),
            ),
          ],
        ),
        const SizedBox(height: 12),

        if (_isLoadingDishes)
          const Center(child: Padding(padding: const EdgeInsets.all(20.0), child: CircularProgressIndicator()))
        else if (_dishes.isEmpty)
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(30),
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: Colors.grey[200]!, style: BorderStyle.solid),
            ),
            child: Column(
              children: [
                Icon(Icons.ramen_dining, size: 50, color: Colors.grey[300]),
                const SizedBox(height: 16),
                const Text("This menu is empty.", style: TextStyle(color: Colors.grey)),
              ],
            ),
          )
        else
          // Dùng ListView.separated để hiển thị danh sách dạng hàng ngang dọc cực gọn
          ListView.separated(
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(), // Để scroll cha quản lý cuộn
            itemCount: _dishes.length,
            separatorBuilder: (context, index) => const SizedBox(height: 12),
            itemBuilder: (context, index) {
              final dish = _dishes[index];
              return _buildDishListItem(dish);
            },
          ),
      ],
    );
  }

  // Widget hiển thị từng món ăn dưới dạng hàng
  Widget _buildDishListItem(MenuDishModel dish) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Colors.grey[200]!),
      ),
      child: Row(
        children: [
          // Hiển thị ảnh (nếu có)
          Container(
            width: 70,
            height: 70,
            decoration: BoxDecoration(
              color: Colors.grey[100],
              borderRadius: BorderRadius.circular(8),
              image: dish.publicUrl != null && dish.publicUrl!.isNotEmpty
                  ? DecorationImage(image: NetworkImage(dish.publicUrl!), fit: BoxFit.cover)
                  : null,
            ),
            child: dish.publicUrl == null || dish.publicUrl!.isEmpty
                ? const Icon(Icons.fastfood, color: Colors.grey)
                : null,
          ),
          const SizedBox(width: 16),
          // Thông tin món
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(dish.name, style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16, color: _textBrown)),
                const SizedBox(height: 4),
                Text(dish.category.toUpperCase(), style: TextStyle(color: Colors.grey[500], fontSize: 12, fontWeight: FontWeight.w600)),
                const SizedBox(height: 4),
                Text("${dish.price.toInt()} ₫", style: TextStyle(color: _primaryRed, fontWeight: FontWeight.bold)),
              ],
            ),
          ),
          // Nút xoá khỏi Menu (Option UX)
          IconButton(
            onPressed: () {
               // Tương lai: Thêm API xoá món khỏi Menu
            },
            icon: const Icon(Icons.remove_circle_outline, color: Colors.grey),
          )
        ],
      ),
    );
  }
}

class _AddDishBottomSheet extends StatefulWidget {
  final String menuUid;
  final VoidCallback onDishAdded; // Callback để load lại trang Detail khi add xong

  const _AddDishBottomSheet({required this.menuUid, required this.onDishAdded});

  @override
  State<_AddDishBottomSheet> createState() => _AddDishBottomSheetState();
}

class _AddDishBottomSheetState extends State<_AddDishBottomSheet> {
  final _repo = ChefManagementRepository();
  bool _isLoading = true;
  bool _isAdding = false;
  List<DishModel> _myDishes = [];

  @override
  void initState() {
    super.initState();
    _fetchMyDishes();
  }

  Future<void> _fetchMyDishes() async {
    final dishes = await _repo.getAllMyDishesForMenu();
    if (mounted) {
      setState(() {
        _myDishes = dishes;
        _isLoading = false;
      });
    }
  }

  Future<void> _onDishTapped(String dishUid) async {
    if (_isAdding) return; // Chặn bấm đúp
    
    setState(() => _isAdding = true);
    try {
      await _repo.addDishToMenu(widget.menuUid, dishUid);
      
      if (mounted) {
        Navigator.pop(context); // Tắt BottomSheet
        showAppSnackBar(context, "Dish added successfully!", type: SnackBarType.success);
        widget.onDishAdded(); // Trigger load lại danh sách món ở trang ngoài
      }
    } catch (e) {
      if (mounted) {
        showAppSnackBar(context, "Failed to add dish: $e", type: SnackBarType.error);
        setState(() => _isAdding = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      height: MediaQuery.of(context).size.height * 0.65, // Chiếm 65% màn hình
      padding: const EdgeInsets.symmetric(vertical: 20),
      decoration: const BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Header của BottomSheet
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                const Text("Select Dish to Add", style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                IconButton(
                  icon: const Icon(Icons.close),
                  onPressed: () => Navigator.pop(context),
                )
              ],
            ),
          ),
          const Divider(),
          
          // Danh sách món ăn
          Expanded(
            child: _isLoading
                ? const Center(child: CircularProgressIndicator())
                : _myDishes.isEmpty
                    ? const Center(child: Text("You don't have any dishes yet."))
                    : ListView.builder(
                        physics: const BouncingScrollPhysics(),
                        itemCount: _myDishes.length,
                        itemBuilder: (context, index) {
                          final dish = _myDishes[index];
                          
                          // Lấy url ảnh (Lưu ý: Đổi dish.publicUrl thành dish.imageUrl nếu Model DishModel của bạn dùng tên đó)
                          final String? imgUrl = dish.imageUrl; // hoặc dish.imageUrl

                          return ListTile(
                            contentPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 4),
                            // 💡 TECH LEAD FIX: Tái sử dụng CustomNetworkImage an toàn, không gây crash UI
                            leading: SizedBox(
                              width: 55, 
                              height: 55,
                              child: ClipRRect(
                                borderRadius: BorderRadius.circular(10), // Bo góc cho mềm mại
                                child: (imgUrl != null && imgUrl.isNotEmpty)
                                    ? CustomNetworkImage(
                                        imageUrl: imgUrl,
                                        fit: BoxFit.cover,
                                        width: 55,
                                        height: 55,
                                      )
                                    : Container(
                                        color: Colors.grey[200],
                                        child: const Icon(Icons.fastfood, color: Colors.grey),
                                      ), // Fallback nếu món ăn chưa có ảnh
                              ),
                            ),
                            title: Text(
                              dish.name, 
                              style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                            subtitle: Text(
                              "${dish.price.toInt()} ₫", // Ép kiểu int cho đẹp nếu giá là số nguyên
                              style: const TextStyle(color: Color(0xFFE55866), fontWeight: FontWeight.w600),
                            ),
                            trailing: _isAdding
                                ? const SizedBox(width: 24, height: 24, child: CircularProgressIndicator(strokeWidth: 2))
                                : const Icon(Icons.add_circle, color: Color(0xFFE55866), size: 28),
                            onTap: () => _onDishTapped(dish.uid),
                          );
                        },
                      ),
          ),
        ],
      ),
    );
  }
}