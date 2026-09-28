import 'dart:io';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:testing/features/common/app_components.dart';
import 'package:testing/features/home/models/dish_location_model.dart';
import '../repositories/chef_management_repository.dart';

class AddDishPage extends StatefulWidget {
  const AddDishPage({super.key});

  @override
  State<AddDishPage> createState() => _AddDishPageState();
}

class _AddDishPageState extends State<AddDishPage> {
  // Styling colors
  final Color _primaryOrange = const Color(0xFFFFB68C);
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _textBrown = const Color(0xFF4A3225);
  final Color _inputYellow = const Color(0xFFF9F0C2);
  
  final _repo = ChefManagementRepository();
  
  // Controllers
  final _nameCtrl = TextEditingController();
  final _descCtrl = TextEditingController();
  final _priceCtrl = TextEditingController();
  //final _locationCtrl = TextEditingController(text: "1"); // Mặc định là 1 cho dễ test
  List<DishLocationModel> _locations = [];
  int? _selectedLocationId;
  bool _isLoadingLocations = true;

  // MỚI: State cho Dropdowns
  String _selectedCategory = "FOOD";
  final List<String> _categoryOptions = ["FOOD", "BEVERAGES", "DESSERT"];
  
  String _selectedStatus = "AVAILABLE";
  final List<String> _statusOptions = ["AVAILABLE", "UNAVAILABLE"];

  File? _selectedImage;
  final ImagePicker _picker = ImagePicker();
  bool _isLoading = false;

  @override
  void initState() {
    super.initState();
    _fetchLocations(); // Gọi API ngay khi vào trang
  }

  Future<void> _fetchLocations() async {
    final locs = await _repo.getDishLocations();
    if (mounted) {
      setState(() {
        _locations = locs;
        if (locs.isNotEmpty) {
          _selectedLocationId = locs.first.id; // Tự động chọn cái đầu tiên
        }
        _isLoadingLocations = false;
      });
    }
  }

  Future<void> _pickImage() async {
    final XFile? picked = await _picker.pickImage(source: ImageSource.gallery);
    if (picked != null) {
      setState(() => _selectedImage = File(picked.path));
    }
  }

  Future<void> _takePhoto() async {
    final XFile? picked = await _picker.pickImage(source: ImageSource.camera);
    if (picked != null) {
      setState(() => _selectedImage = File(picked.path));
    }
  }

  void _submit() async {
    // Validate cơ bản
    if (_nameCtrl.text.isEmpty || _priceCtrl.text.isEmpty) {
      showAppSnackBar(context, "Name and Price are required!", type: SnackBarType.warning);
      return;
    }
    if (_selectedLocationId == null) {
      showAppSnackBar(context, "Please wait for locations to load.", type: SnackBarType.warning);
      return;
    }
    if (_selectedImage == null) {
      showAppSnackBar(context, "Please select an image!", type: SnackBarType.warning);
      return;
    }

    setState(() => _isLoading = true);

    final success = await _repo.createDishFullFlow(
      name: _nameCtrl.text.trim(),
      description: _descCtrl.text.trim(),
      price: double.tryParse(_priceCtrl.text) ?? 0,
      category: _selectedCategory,
      status: _selectedStatus,
      locationId: _selectedLocationId!,
      imageFile: _selectedImage!,
    );

    if (mounted) setState(() => _isLoading = false);

    if (success && mounted) {
      _showSuccessDialog();
    } else if (mounted) {
      showAppSnackBar(context, "Failed to upload dish. Please try again.", type: SnackBarType.error);
    }
  }

  void _showSuccessDialog() {
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => Stack(
        alignment: Alignment.center,
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 40),
            margin: const EdgeInsets.symmetric(horizontal: 20),
            decoration: BoxDecoration(color: Colors.black87, borderRadius: BorderRadius.circular(12)),
            child: const Text(
              "Your Dish Has Been Added Successfully.",
              textAlign: TextAlign.center,
              style: TextStyle(color: Colors.white, fontSize: 14, decoration: TextDecoration.none),
            ),
          ),
        ],
      ),
    );
    Future.delayed(const Duration(seconds: 2), () {
      if (mounted) {
         Navigator.of(context).pop();
         Navigator.of(context).pop(); // Quay lại trang Menu
      }
    });
  }

  @override
  void dispose() {
    _nameCtrl.dispose();
    _descCtrl.dispose();
    _priceCtrl.dispose();
    _selectedLocationId = null;
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _primaryOrange,
      body: Column(
        children: [
          // ===== HEADER =====
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 60, 20, 20),
            child: Row(
              children: [
                IconButton(
                  icon: const Icon(Icons.chevron_left, color: Colors.black, size: 30),
                  onPressed: () => Navigator.of(context).pop(),
                ),
                const Expanded(
                  child: Text(
                    "Add New Dish",
                    textAlign: TextAlign.center,
                    style: TextStyle(color: Colors.white, fontSize: 28, fontWeight: FontWeight.bold),
                  ),
                ),
                const SizedBox(width: 48),
              ],
            ),
          ),

          // ===== BODY FORM =====
          Expanded(
            child: Container(
              width: double.infinity,
              decoration: const BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.only(topLeft: Radius.circular(30), topRight: Radius.circular(30)),
              ),
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(24),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // Name
                    _buildLabel("Name"),
                    const SizedBox(height: 8),
                    _buildInput(_nameCtrl, "Dish Name"),
                    const SizedBox(height: 20),
                    
                    // Description
                    _buildLabel("Description"),
                    const SizedBox(height: 8),
                    _buildInput(_descCtrl, "Describe your dish...", maxLines: 3),
                    const SizedBox(height: 20),
                    
                    // Row cho Price và Location ID
                    Row(
                      children: [
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              _buildLabel("Price"),
                              const SizedBox(height: 8),
                              _buildInput(_priceCtrl, "0", isNumber: true),
                            ],
                          ),
                        ),
                        const SizedBox(width: 16),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              _buildLabel("Location"),
                              const SizedBox(height: 8),
                              
                              // 💡 TECH LEAD FIX: Thay Input thành Dropdown Location từ API
                              _isLoadingLocations
                                ? const Center(child: SizedBox(height: 20, width: 20, child: CircularProgressIndicator()))
                                : Container(
                                    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
                                    decoration: BoxDecoration(color: _inputYellow, borderRadius: BorderRadius.circular(12)),
                                    child: DropdownButtonHideUnderline(
                                      child: DropdownButton<int>( // Kiểu dữ liệu value là int (chứa ID)
                                        value: _selectedLocationId,
                                        isExpanded: true,
                                        icon: const Icon(Icons.keyboard_arrow_down, color: Colors.black54),
                                        items: _locations.map((loc) {
                                          return DropdownMenuItem<int>(
                                            value: loc.id, // Ngầm giữ ID để gửi API
                                            child: Text(loc.name ?? "", style: const TextStyle(color: Colors.black87, fontSize: 14)), // Hiển thị tên cho Chef đọc
                                          );
                                        }).toList(),
                                        onChanged: (val) {
                                          if (val != null) setState(() => _selectedLocationId = val);
                                        },
                                      ),
                                    ),
                                  ),
                            ],
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 20),

                    // Row cho Category và Status
                    Row(
                      children: [
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              _buildLabel("Category"),
                              const SizedBox(height: 8),
                              _buildDropdown(_categoryOptions, _selectedCategory, (val) {
                                if (val != null) setState(() => _selectedCategory = val);
                              }),
                            ],
                          ),
                        ),
                        const SizedBox(width: 16),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              _buildLabel("Status"),
                              const SizedBox(height: 8),
                              _buildDropdown(_statusOptions, _selectedStatus, (val) {
                                if (val != null) setState(() => _selectedStatus = val);
                              }),
                            ],
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 20),
                    
                    // Image Section
                    _buildLabel("Image"),
                    const SizedBox(height: 10),
                    GestureDetector(
                      onTap: _pickImage,
                      child: Container(
                        width: double.infinity,
                        height: 150,
                        decoration: BoxDecoration(
                          color: const Color(0xFFFAFAFA),
                          borderRadius: BorderRadius.circular(16),
                          border: Border.all(color: Colors.grey.shade300),
                          image: _selectedImage != null 
                            ? DecorationImage(image: FileImage(_selectedImage!), fit: BoxFit.cover)
                            : null,
                        ),
                        child: _selectedImage == null 
                          ? Column(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                Icon(Icons.cloud_upload_outlined, size: 40, color: Colors.grey[400]),
                                const SizedBox(height: 8),
                                Text("Tap to upload photo", style: TextStyle(color: _primaryRed, fontWeight: FontWeight.bold)),
                                const SizedBox(height: 8),
                                GestureDetector(
                                  onTap: _takePhoto,
                                  child: Container(
                                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
                                    decoration: BoxDecoration(color: _textBrown, borderRadius: BorderRadius.circular(20)),
                                    child: const Text("Open camera", style: TextStyle(color: Colors.white, fontSize: 10)),
                                  ),
                                )
                              ],
                            )
                          : null,
                      ),
                    ),
                    
                    const SizedBox(height: 30),
                    
                    // Submit Button
                    SizedBox(
                      width: double.infinity,
                      height: 50,
                      child: ElevatedButton(
                        onPressed: _isLoading ? null : _submit,
                        style: ElevatedButton.styleFrom(
                          backgroundColor: _primaryRed,
                          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(25)),
                        ),
                        child: _isLoading 
                          ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2))
                          : const Text("Add Dish", style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Colors.white)),
                      ),
                    ),
                    const SizedBox(height: 30),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
  
  // Helper UI methods
  Widget _buildLabel(String text) {
    return Text(text, style: TextStyle(fontSize: 14, fontWeight: FontWeight.bold, color: _textBrown));
  }
  
  Widget _buildInput(TextEditingController ctrl, String hint, {int maxLines = 1, bool isNumber = false}) {
    return Container(
      padding: EdgeInsets.symmetric(horizontal: 16, vertical: maxLines > 1 ? 12 : 4),
      decoration: BoxDecoration(color: _inputYellow, borderRadius: BorderRadius.circular(12)),
      child: TextField(
        controller: ctrl,
        maxLines: maxLines,
        keyboardType: isNumber ? TextInputType.number : TextInputType.text,
        style: const TextStyle(color: Colors.black87),
        decoration: InputDecoration(
          border: InputBorder.none,
          hintText: hint,
          isDense: true,
          hintStyle: const TextStyle(color: Colors.black38),
        ),
      ),
    );
  }

  // Widget Dropdown Tái sử dụng
  Widget _buildDropdown(List<String> options, String currentValue, ValueChanged<String?> onChanged) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      decoration: BoxDecoration(color: _inputYellow, borderRadius: BorderRadius.circular(12)),
      child: DropdownButtonHideUnderline(
        child: DropdownButton<String>(
          value: currentValue,
          isExpanded: true,
          icon: const Icon(Icons.keyboard_arrow_down, color: Colors.black54),
          items: options.map((String value) {
            return DropdownMenuItem<String>(
              value: value,
              child: Text(value, style: const TextStyle(color: Colors.black87, fontSize: 14)),
            );
          }).toList(),
          onChanged: onChanged,
        ),
      ),
    );
  }
}