import 'package:flutter/material.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';
import 'package:testing/features/map/google_map_picker_page.dart';
import 'package:testing/features/profile/repository/attachment_repository.dart';
import '../../common/app_components.dart';
import '../../chef_manager/repositories/chef_management_repository.dart';
import 'package:image_picker/image_picker.dart';
import 'dart:io';

class ChefRegistrationPage extends StatefulWidget {
  final String token;

  const ChefRegistrationPage({super.key, required this.token});

  @override
  State<ChefRegistrationPage> createState() => _ChefRegistrationPageState();
}

class _ChefRegistrationPageState extends State<ChefRegistrationPage> {
  // Controllers
  final _bioController = TextEditingController();
  final _specialtyController = TextEditingController();
  final _bankNumController = TextEditingController();
  final _bankOwnerController = TextEditingController();

  final TextEditingController _kitchenAddressController =
      TextEditingController();
  final TextEditingController _kitchenStreetController =
      TextEditingController();
  final TextEditingController _kitchenWardController = TextEditingController();
  final TextEditingController _kitchenDistrictController =
      TextEditingController();
  final TextEditingController _kitchenCityController = TextEditingController();

  // Biến hứng tọa độ từ Google Map
  LatLng? _selectedKitchenLocation;

  final _repo = ChefManagementRepository();
  final _imageRepo = AttachmentRepository();

  bool _isLoading = false;

  File? _avatarFile;
  final ImagePicker _picker = ImagePicker();

  // 👇 TECH LEAD THÊM: Danh sách các ngân hàng chuẩn khớp 100% với Backend Enum
  final List<Map<String, String>> _vietnamBanks = [
    {"name": "Vietcombank", "code": "970436"},
    {"name": "Techcombank", "code": "970422"},
    {"name": "VPBank", "code": "970415"},
    {"name": "BIDV", "code": "970405"},
    {"name": "Agribank", "code": "970402"},
    {"name": "MBBank", "code": "970401"},
    {"name": "ACB", "code": "970400"},
    {"name": "Sacombank", "code": "970403"},
    {"name": "VietinBank", "code": "970404"},
    {"name": "TPBank", "code": "970406"},
    {"name": "HDBank", "code": "970407"},
    {"name": "VIB", "code": "970408"},
  ];

  // Biến lưu trữ Ngân hàng được user chọn
  Map<String, String>? _selectedBank;

  // Styling colors matching the pattern
  final Color _primaryOrange = AppColors.primaryOrange;
  final Color _textBrown = AppColors.brownText;
  final Color _primaryRed = AppColors.primaryRed;
  final Color _inputFillColor = AppColors.inputSoftGrey;

  @override
  void dispose() {
    _bioController.dispose();
    _specialtyController.dispose();
    _bankNumController.dispose();
    _bankOwnerController.dispose();
    _kitchenAddressController.dispose();
    _kitchenStreetController.dispose();
    _kitchenWardController.dispose();
    _kitchenDistrictController.dispose();
    _kitchenCityController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    // Basic validation: Bắt buộc chọn Ngân hàng
    if (_avatarFile == null ||
        _bioController.text.isEmpty ||
        _bankNumController.text.isEmpty ||
        _bankOwnerController.text.isEmpty ||
        _selectedBank == null) {
      showAppSnackBar(
        context,
        'Please fill in all required information & select a bank',
        type: SnackBarType.warning,
      );
      return;
    }

    setState(() => _isLoading = true);

    try {
      String? avatarId = await _imageRepo.uploadAndGetAttachmentId(
        _avatarFile!,
        widget.token,
        'CHEF_AVATAR',
      );

      if (avatarId == null) {
        throw Exception("Failed to upload avatar");
      }

      if (!mounted) return;

      if (_selectedKitchenLocation == null) {
        // Báo lỗi: Vui lòng chọn vị trí bếp trên bản đồ!
        setState(() => _isLoading = false);
        showAppSnackBar(
            context, 'Please select your kitchen location on the map',
            type: SnackBarType.warning);
        return;
      }

      // 3. BƯỚC 2: Gọi API nâng cấp Chef với cái avatarId vừa lấy được
      final success = await _repo.upgradeToChef(
        bio: _bioController.text,
        specialty: _specialtyController.text,
        bankName: _selectedBank!['name']!,
        bankCode: _selectedBank!['code']!,
        bankAccount: _bankNumController.text,
        bankAccountName: _bankOwnerController.text,
        avatarId: avatarId,
        kitchenAddress: _kitchenAddressController.text,
        kitchenStreet: _kitchenStreetController.text,
        kitchenWard: _kitchenWardController.text,
        kitchenDistrict: _kitchenDistrictController.text,
        kitchenCity: _kitchenCityController.text,
        kitchenLat: _selectedKitchenLocation!.latitude,
        kitchenLng: _selectedKitchenLocation!.longitude,
      );

      if (!mounted) return;
      setState(() => _isLoading = false);

      if (success) {
        Navigator.pop(context, true);
        showAppSnackBar(context, 'Registration successful!',
            type: SnackBarType.success);
      } else {
        showAppSnackBar(context, 'Registration failed. Please try again.',
            type: SnackBarType.error);
      }
    } catch (e) {
      if (!mounted) return;
      setState(() => _isLoading = false);
      showAppSnackBar(context, 'Error: ${e.toString()}',
          type: SnackBarType.error);
    }
  }

  Future<void> _pickAvatar() async {
    final XFile? pickedFile =
        await _picker.pickImage(source: ImageSource.gallery);
    if (pickedFile != null) {
      setState(() {
        _avatarFile = File(pickedFile.path);
      });
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
                IconButton(
                  icon: const Icon(Icons.chevron_left,
                      color: Colors.black, size: 30),
                  onPressed: () => Navigator.of(context).pop(),
                ),
                Expanded(
                  child: Text(
                    "Chef Registration",
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 28,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
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
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(24),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      "Chef Information",
                      style: TextStyle(
                          fontSize: 18,
                          fontWeight: FontWeight.bold,
                          color: _textBrown),
                    ),
                    const SizedBox(height: 15),
                    // Kitchen Information Section
                    Center(
                      child: Stack(
                        children: [
                          Container(
                            width: 100,
                            height: 100,
                            decoration: BoxDecoration(
                              color: const Color(0xFFEEEEEE),
                              shape: BoxShape.circle,
                              image: _avatarFile != null
                                  ? DecorationImage(
                                      image: FileImage(_avatarFile!),
                                      fit: BoxFit.cover,
                                    )
                                  : null,
                            ),
                            child: _avatarFile == null
                                ? const Icon(Icons.person,
                                    size: 50, color: Colors.grey)
                                : null,
                          ),
                          Positioned(
                            bottom: 0,
                            right: 0,
                            child: GestureDetector(
                              onTap: _pickAvatar,
                              child: Container(
                                padding: const EdgeInsets.all(8),
                                decoration: const BoxDecoration(
                                  color: Color(0xFFE55866),
                                  shape: BoxShape.circle,
                                ),
                                child: const Icon(Icons.camera_alt,
                                    color: Colors.white, size: 20),
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 10),
                    const Center(
                      child: Text(
                        "Upload Chef Avatar*",
                        style: TextStyle(
                            fontSize: 14,
                            fontWeight: FontWeight.w500,
                            color: Colors.grey),
                      ),
                    ),
                    const SizedBox(height: 30),

                    _buildTextField(
                        label: "Bio",
                        controller: _bioController,
                        maxLines: 3,
                        hintText:
                            "Tell us about yourself and your cooking experience..."),
                    const SizedBox(height: 15),

                    _buildTextField(
                        label: "Specialty",
                        controller: _specialtyController,
                        hintText: "e.g., Vietnamese, Italian, Desserts..."),

                    const SizedBox(height: 30),

                    Text(
                      "Kitchen Address",
                      style: TextStyle(
                          fontSize: 18,
                          fontWeight: FontWeight.bold,
                          color: _textBrown),
                    ),
                    const SizedBox(height: 15),

                    // Nút mở Google Map Picker
                    SizedBox(
                      width: double.infinity,
                      height: 50,
                      child: OutlinedButton.icon(
                        style: OutlinedButton.styleFrom(
                          side: BorderSide(
                              color: _selectedKitchenLocation == null
                                  ? Colors.redAccent
                                  : Colors.green,
                              width: 1.5),
                          shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(12)),
                        ),
                        onPressed: () async {
                          // Tái sử dụng màn hình Map lúc nãy
                          final LatLng? pickedLocation = await Navigator.push(
                            context,
                            MaterialPageRoute(
                                builder: (context) =>
                                    const GoogleMapPickerPage()),
                          );
                          if (pickedLocation != null) {
                            setState(() {
                              _selectedKitchenLocation = pickedLocation;
                            });
                          }
                        },
                        icon: Icon(
                            _selectedKitchenLocation == null
                                ? Icons.map
                                : Icons.check_circle,
                            color: _selectedKitchenLocation == null
                                ? Colors.redAccent
                                : Colors.green),
                        label: Text(
                          _selectedKitchenLocation == null
                              ? "Pin Kitchen on Map (Required)"
                              : "Location Selected Successfully!",
                          style: TextStyle(
                              color: _selectedKitchenLocation == null
                                  ? Colors.redAccent
                                  : Colors.green,
                              fontWeight: FontWeight.bold),
                        ),
                      ),
                    ),
                    const SizedBox(height: 15),

                    // Các trường nhập liệu chi tiết
                    _buildTextField(
                        label: "House No., Building Name",
                        controller: _kitchenAddressController,
                        hintText: "e.g., 123 Apartment, Room 4A"),
                    const SizedBox(height: 15),
                    _buildTextField(
                        label: "Street", controller: _kitchenStreetController),
                    const SizedBox(height: 15),
                    _buildTextField(
                        label: "Ward", controller: _kitchenWardController),
                    const SizedBox(height: 15),
                    _buildTextField(
                        label: "District",
                        controller: _kitchenDistrictController),
                    const SizedBox(height: 15),
                    _buildTextField(
                        label: "City", controller: _kitchenCityController),

                    const SizedBox(height: 30),

                    // Payment Information Section
                    Text(
                      "Payment Information",
                      style: TextStyle(
                          fontSize: 18,
                          fontWeight: FontWeight.bold,
                          color: _textBrown),
                    ),
                    const SizedBox(height: 15),

                    // 👇 THAY THẾ TEXTFIELD BẰNG DROPDOWN TẠI ĐÂY
                    _buildBankDropdown(),
                    const SizedBox(height: 15),

                    _buildTextField(
                        label: "Account Number",
                        controller: _bankNumController,
                        keyboardType: TextInputType.number,
                        hintText: "Enter your bank account number"),
                    const SizedBox(height: 15), // Thêm khoảng cách cho thoáng

                    _buildTextField(
                        label: "Account Holder Name",
                        controller: _bankOwnerController,
                        hintText: "NGUYEN VAN A (Uppercase, no accents)"),

                    const SizedBox(height: 40),

                    // Submit Button
                    SizedBox(
                      width: double.infinity,
                      height: 50,
                      child: ElevatedButton(
                        style: ElevatedButton.styleFrom(
                          backgroundColor: _primaryRed,
                          foregroundColor: Colors.white,
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(25),
                          ),
                        ),
                        onPressed: _isLoading ? null : _submit,
                        child: _isLoading
                            ? const CircularProgressIndicator(
                                color: Colors.white)
                            : const Text("Submit Registration",
                                style: TextStyle(
                                    fontSize: 16, fontWeight: FontWeight.bold)),
                      ),
                    ),

                    const SizedBox(height: 20),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  // 👇 WIDGET MỚI: Xây dựng Dropdown chọn Ngân hàng đẹp mắt
  Widget _buildBankDropdown() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          "Bank Name",
          style: TextStyle(
              fontSize: 14, fontWeight: FontWeight.w500, color: _textBrown),
        ),
        const SizedBox(height: 8),
        Container(
          padding: const EdgeInsets.symmetric(
              horizontal: 16,
              vertical: 2), // Căn chỉnh padding cho cân đối với TextField
          decoration: BoxDecoration(
            color: _inputFillColor,
            borderRadius: BorderRadius.circular(8),
          ),
          child: DropdownButtonHideUnderline(
            child: DropdownButton<Map<String, String>>(
              isExpanded: true,
              hint: const Text(
                "Select your bank",
                style: TextStyle(color: Colors.black54),
              ),
              value: _selectedBank,
              icon: const Icon(Icons.arrow_drop_down, color: Colors.black54),
              items: _vietnamBanks.map((bank) {
                return DropdownMenuItem<Map<String, String>>(
                  value: bank,
                  child: Text(
                    "${bank['name']} (${bank['code']})",
                    style: const TextStyle(color: Colors.black87),
                  ),
                );
              }).toList(),
              onChanged: (val) {
                setState(() {
                  _selectedBank = val;
                });
              },
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildTextField({
    required String label,
    required TextEditingController controller,
    int maxLines = 1,
    TextInputType? keyboardType,
    String? hintText,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: TextStyle(
              fontSize: 14, fontWeight: FontWeight.w500, color: _textBrown),
        ),
        const SizedBox(height: 8),
        Container(
          decoration: BoxDecoration(
            color: _inputFillColor,
            borderRadius: BorderRadius.circular(8),
          ),
          child: TextField(
            controller: controller,
            maxLines: maxLines,
            keyboardType: keyboardType,
            style: const TextStyle(color: Colors.black87),
            decoration: InputDecoration(
              hintText: hintText,
              hintStyle: const TextStyle(color: Colors.black54),
              border: InputBorder.none,
              contentPadding:
                  const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
            ),
          ),
        ),
      ],
    );
  }
}
