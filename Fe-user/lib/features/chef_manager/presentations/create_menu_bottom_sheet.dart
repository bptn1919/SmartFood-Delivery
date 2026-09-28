// 📂 features/menu/widgets/create_menu_bottom_sheet.dart
import 'package:flutter/material.dart';
import 'package:testing/features/chef_manager/presentations/chef_menu_detail_page.dart';
import 'package:testing/features/chef_manager/repositories/chef_management_repository.dart';
import 'package:testing/features/home/models/menu_model.dart';
import '../../common/app_components.dart';


class CreateMenuBottomSheet extends StatefulWidget {
  final VoidCallback onMenuCreated; // Dùng để load lại list bên ngoài nếu cần

  const CreateMenuBottomSheet({super.key, required this.onMenuCreated});

  @override
  State<CreateMenuBottomSheet> createState() => _CreateMenuBottomSheetState();
}

class _CreateMenuBottomSheetState extends State<CreateMenuBottomSheet> {
  final _repo = ChefManagementRepository();
  
  final TextEditingController _nameController = TextEditingController();
  final TextEditingController _descController = TextEditingController();
  
  String _selectedStatus = "DRAFT"; // Mặc định tạo ra cứ để DRAFT cho an toàn
  final List<String> _statusOptions = ["ACTIVE", "INACTIVE", "DRAFT"];
  
  bool _isSubmitting = false;

  Future<void> _onCreateTapped() async {
    final name = _nameController.text.trim();
    if (name.isEmpty) {
      showAppSnackBar(context, "Please enter a menu name", type: SnackBarType.warning);
      return;
    }

    setState(() => _isSubmitting = true);

    try {
      // 1. Gọi API tạo Menu
      MenuModel newMenu = await _repo.createMenu(
        name,
        _descController.text.trim(),
        _selectedStatus,
      );

      if (mounted) {
        // 2. Đóng Bottom Sheet
        Navigator.pop(context);
        showAppSnackBar(context, "Menu created successfully!", type: SnackBarType.success);
        
        // 3. Kích hoạt load lại danh sách ở trang cha
        widget.onMenuCreated();

        // 4. UX SIÊU MƯỢT: Tự động điều hướng thẳng vào trang Chi tiết Menu để Chef thêm món
        Navigator.push(
          context,
          MaterialPageRoute(
            builder: (context) => ChefMenuDetailPage(menu: newMenu),
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        showAppSnackBar(context, "Failed to create menu: $e", type: SnackBarType.error);
        setState(() => _isSubmitting = false);
      }
    }
  }

  @override
  void dispose() {
    _nameController.dispose();
    _descController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    // Tính toán chiều cao bàn phím để form không bị che
    final bottomInset = MediaQuery.of(context).viewInsets.bottom;

    return Container(
      padding: EdgeInsets.fromLTRB(20, 20, 20, 20 + bottomInset),
      decoration: const BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      child: SingleChildScrollView(
        physics: const BouncingScrollPhysics(),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Header
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                const Text("Create New Set Menu", style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                IconButton(
                  icon: const Icon(Icons.close),
                  onPressed: () => Navigator.pop(context),
                )
              ],
            ),
            const Divider(),
            const SizedBox(height: 12),

            // Form Name
            TextField(
              controller: _nameController,
              autofocus: true, // Tự động bật bàn phím
              decoration: InputDecoration(
                labelText: "Menu Name *",
                hintText: "E.g. Family Weekend Combo",
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
              ),
            ),
            const SizedBox(height: 16),

            // Form Description
            TextField(
              controller: _descController,
              maxLines: 3,
              decoration: InputDecoration(
                labelText: "Description (Optional)",
                hintText: "Briefly describe what's inside this menu...",
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
              ),
            ),
            const SizedBox(height: 16),

            // Dropdown Status
            DropdownButtonFormField<String>(
              value: _selectedStatus,
              decoration: InputDecoration(
                labelText: "Initial Status",
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
              ),
              items: _statusOptions.map((status) {
                return DropdownMenuItem(value: status, child: Text(status));
              }).toList(),
              onChanged: (val) {
                if (val != null) setState(() => _selectedStatus = val);
              },
            ),
            const SizedBox(height: 24),

            // Submit Button
            SizedBox(
              width: double.infinity,
              height: 50,
              child: ElevatedButton(
                onPressed: _isSubmitting ? null : _onCreateTapped,
                style: ElevatedButton.styleFrom(
                  backgroundColor: const Color(0xFFE55866), // _primaryRed
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                ),
                child: _isSubmitting
                    ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2))
                    : const Text("Create Menu", style: TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.bold)),
              ),
            ),
          ],
        ),
      ),
    );
  }
}