import 'package:flutter/material.dart';
import 'dart:io';
import 'package:testing/features/profile/repository/customer_profile_repository.dart';
import 'package:testing/features/profile/repository/attachment_repository.dart';
import '../../common/app_components.dart'; // Đảm bảo bạn import đúng đường dẫn chứa showAppSnackBar
import 'package:image_picker/image_picker.dart';
// TODO: Import file chứa repository gọi API getMe, updateMe

class EditProfilePage extends StatefulWidget {
  final String token;
  const EditProfilePage({super.key, required this.token});

  @override
  State<EditProfilePage> createState() => _EditProfilePageState();
}

class _EditProfilePageState extends State<EditProfilePage> {
  // --- TONE MÀU HỆ THỐNG AMOMEAL ---
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _textBrown = const Color(0xFF4A3225);
  final Color _inputFillColor = const Color(0xFFEEEEEE);

  // --- CONTROLLERS ---
  final TextEditingController _nameController = TextEditingController();
  final TextEditingController _usernameController = TextEditingController();
  final TextEditingController _phoneController = TextEditingController();
  final TextEditingController _emailController = TextEditingController();
  final TextEditingController _bioController = TextEditingController();

  final TextEditingController _dietMode = TextEditingController();
  final TextEditingController _dietLevel = TextEditingController();
  final TextEditingController _allergyMode = TextEditingController();

  int _currentPoints = 0; // Lưu điểm hiện tại
  String? _avatarUrl;

  File? _newAvatarFile;
  final ImagePicker _picker = ImagePicker();

  // --- REPOSITORY & STATE ---
  final CustomerProfileRepository _customerRepo =
      CustomerProfileRepository(); // Repo để gọi API liên quan đến profile
  final AttachmentRepository _imageRepo =
      AttachmentRepository(); // Repo để gọi API upload ảnh
  bool _isLoading = true;
  bool _isSaving = false;

  @override
  void initState() {
    super.initState();
    _fetchUserData();
  }

  Future<void> _pickAvatar() async {
    final XFile? pickedFile =
        await _picker.pickImage(source: ImageSource.gallery);
    if (pickedFile != null) {
      setState(() {
        _newAvatarFile = File(pickedFile.path);
        // Có thể clear _avatarUrl (ảnh cũ từ mạng) để UI ưu tiên hiển thị _newAvatarFile
        _avatarUrl = null;
      });
    }
  }

  // GỌI API: LẤY THÔNG TIN USER KHI VÀO TRANG
  Future<void> _fetchUserData() async {
    try {
      final profileData = await _customerRepo.getCustomerProfile();
      if (!mounted) return;
      debugPrint("✅ API getCustomerProfile trả về: $profileData");
      if (profileData != null) {
        debugPrint("✅ Fetch User THÀNH CÔNG: Detail=${profileData.fullname}");
        _nameController.text = profileData.fullname ?? "";
        _phoneController.text = profileData.phone ?? "";
        _emailController.text = profileData.mail ?? "";
        _bioController.text = profileData.bio ?? "";
        _currentPoints = profileData.points ?? 0;
        _avatarUrl = profileData.avatar;

        _dietMode.text = profileData.dietMode ?? "";
        _dietLevel.text = profileData.dietLevel ?? "";
        _allergyMode.text = profileData.allergyMode ?? "";
      } else {
        debugPrint(
            "⚠️ Fetch User trả về null. Có thể do lỗi parsing hoặc API trả data khác cấu trúc mong đợi.");
        showAppSnackBar(context, "Cannot fetch User information!",
            type: SnackBarType.error);
      }

      if (mounted) setState(() => _isLoading = false);
    } catch (e) {
      debugPrint("Lỗi tải thông tin: $e");
      if (mounted) {
        setState(() => _isLoading = false);
        showAppSnackBar(context, "Cannot fetch information!",
            type: SnackBarType.error);
      }
    }
  }

  // GỌI API: LƯU THÔNG TIN (Chỉ update Name theo thiết kế API hiện tại)
  Future<void> _saveProfile() async {
    // Validate cơ bản
    if (_nameController.text.trim().isEmpty) {
      showAppSnackBar(context, "Name cannot be empty!",
          type: SnackBarType.warning);
      return;
    }

    setState(() => _isSaving = true);

    try {
      String? attachmentUid;

      // BƯỚC 1: Nếu user có chọn ảnh mới -> Bắn API upload ảnh trước
      if (_newAvatarFile != null) {
        // Gọi hàm upload của bạn (nhớ thay bằng tham số token chuẩn của hệ thống)
        attachmentUid = await _imageRepo.uploadAndGetAttachmentId(
          _newAvatarFile!,
          widget.token,
          'CUSTOMER_AVATAR',
        );

        if (attachmentUid == null) {
          throw Exception("Failed to upload avatar image");
        }
      }

      // BƯỚC 2: Gọi API Update Profile với dữ liệu text + UID của ảnh (nếu có)
      final success = await _customerRepo.updateCustomerProfile(
        bio: _bioController.text.trim(),
        phone: _phoneController.text.trim(),
        attachmentUid:
            attachmentUid, // Có thể truyền null nếu user không đổi ảnh
        dietMode: _dietMode.text.trim(),
        dietLevel: _dietLevel.text.trim(),
        allergyMode: _allergyMode.text.trim(),
      );

      if (!mounted) return;

      if (success) {
        showAppSnackBar(context, "Update successfully!",
            type: SnackBarType.success);
        Navigator.pop(context, true);
      } else {
        showAppSnackBar(context, "Error when updating information!",
            type: SnackBarType.error);
      }
    } catch (e) {
      debugPrint("Error saving information: $e");
      if (mounted) {
        showAppSnackBar(context, 'System error: ${e.toString()}',
            type: SnackBarType.error);
      }
    } finally {
      if (mounted) setState(() => _isSaving = false);
    }
  }

  @override
  void dispose() {
    _nameController.dispose();
    _usernameController.dispose();
    _phoneController.dispose();
    _emailController.dispose();
    _bioController.dispose();
    _dietMode.dispose();
    _dietLevel.dispose();
    _allergyMode.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        backgroundColor: Colors.white,
        elevation: 0,
        leading: IconButton(
          icon: Container(
            padding: const EdgeInsets.all(4),
            decoration: BoxDecoration(
              color: _primaryRed.withValues(alpha: 0.1),
              borderRadius: BorderRadius.circular(8),
            ),
            child: Icon(Icons.arrow_back_ios_new, color: _primaryRed, size: 18),
          ),
          onPressed: () => Navigator.pop(context),
        ),
        centerTitle: true,
        title: Text(
          "Edit Profile",
          style: TextStyle(
              color: _textBrown, fontSize: 20, fontWeight: FontWeight.bold),
        ),
      ),
      body: _isLoading
          ? Center(child: CircularProgressIndicator(color: _primaryRed))
          : SingleChildScrollView(
              padding: const EdgeInsets.all(24.0),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Center(
                    child: Column(
                      children: [
                        // 👇 TECH LEAD FIX: Bọc CircleAvatar bằng Stack để nhét thêm nút Camera
                        Stack(
                          children: [
                            CircleAvatar(
                              radius:
                                  40, // Kích thước này đường kính là 80, hơi nhỏ hơn màn Chef (100) một chút
                              backgroundColor: _inputFillColor,
                              // Ưu tiên hiển thị file vừa chọn, nếu không có thì hiển thị URL từ API
                              backgroundImage: _newAvatarFile != null
                                  ? FileImage(_newAvatarFile!) as ImageProvider
                                  : (_avatarUrl != null &&
                                          _avatarUrl!.isNotEmpty
                                      ? NetworkImage(_avatarUrl!)
                                      : null),
                              child: _newAvatarFile == null &&
                                      (_avatarUrl == null ||
                                          _avatarUrl!.isEmpty)
                                  ? const Icon(Icons.person,
                                      size: 40, color: Colors.grey)
                                  : null,
                            ),

                            // 👇 TECH LEAD FIX: Gắn nút Camera vào góc dưới cùng bên phải
                            Positioned(
                              bottom: 0,
                              right: 0,
                              child: GestureDetector(
                                onTap:
                                    _pickAvatar, // ⚠️ Đảm bảo bạn đã có hàm _pickAvatar ở file này nhé
                                child: Container(
                                  padding: const EdgeInsets.all(
                                      6), // Thu nhỏ padding một xíu cho cân đối với radius 40
                                  decoration: const BoxDecoration(
                                    color: Color(0xFFE55866),
                                    shape: BoxShape.circle,
                                  ),
                                  child: const Icon(Icons.camera_alt,
                                      color: Colors.white, size: 16),
                                ),
                              ),
                            ),
                          ],
                        ),

                        const SizedBox(height: 8),
                        Text(
                          "Points: $_currentPoints",
                          style: TextStyle(
                              color: _primaryRed, fontWeight: FontWeight.bold),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 30),

                  // 1. TÊN ĐẦY ĐỦ (Được phép sửa)
                  _buildLabel("Name"),
                  _buildTextField(
                      controller: _nameController,
                      isReadOnly:
                          true), // Hiện tại API chưa hỗ trợ update name, nên tạm thời để readOnly
                  const SizedBox(height: 20),

                  _buildLabel("Bio"),
                  _buildTextField(controller: _bioController, maxLines: 3),
                  const SizedBox(height: 20),

                  // 2. USERNAME (Hiện mờ vì API chưa hỗ trợ update)
                  _buildLabel("Phone Number"),
                  _buildTextField(controller: _phoneController),
                  const SizedBox(height: 20),

                  // 5. EMAIL (Hiện mờ)
                  _buildLabel("Email"),
                  _buildTextField(
                      controller: _emailController, isReadOnly: true),

                  _buildLabel("Diet Mode"),
                  _buildTextField(controller: _dietMode),
                  const SizedBox(height: 20),

                  _buildLabel("Diet Level"),
                  _buildTextField(controller: _dietLevel),
                  const SizedBox(height: 20),

                  _buildLabel("Allergy Mode"),
                  DropdownButtonFormField<String>(
                    initialValue:
                        _allergyMode.text.isNotEmpty ? _allergyMode.text : null,
                    decoration: InputDecoration(
                      filled: true,
                      fillColor: Colors.grey[
                          200], // Thay bằng màu nền giống _buildTextField của bạn
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(10),
                        borderSide: BorderSide.none,
                      ),
                      contentPadding: const EdgeInsets.symmetric(
                          horizontal: 16, vertical: 14),
                    ),
                    items: const [
                      DropdownMenuItem(
                        value: "WARN",
                        child: Text("WARN - Warning but allow"),
                      ),
                      DropdownMenuItem(
                        value: "HIDE",
                        child:
                            Text("HIDE - Hide dishes that contain allergens"),
                      ),
                    ],
                    onChanged: (String? newValue) {
                      if (newValue != null) {
                        setState(() {
                          _allergyMode.text = newValue;
                        });
                      }
                    },
                  ),
                  const SizedBox(height: 20),

                  const SizedBox(height: 40),

                  // 6. NÚT SAVE
                  SizedBox(
                    width: double.infinity,
                    height: 50,
                    child: ElevatedButton(
                      style: ElevatedButton.styleFrom(
                        backgroundColor: _primaryRed,
                        shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(10)),
                        elevation: 0,
                      ),
                      onPressed: _isSaving ? null : _saveProfile,
                      child: _isSaving
                          ? const SizedBox(
                              width: 20,
                              height: 20,
                              child: CircularProgressIndicator(
                                  color: Colors.white, strokeWidth: 2),
                            )
                          : const Text(
                              "Save",
                              style: TextStyle(
                                  fontSize: 16,
                                  fontWeight: FontWeight.bold,
                                  color: Colors.white),
                            ),
                    ),
                  ),
                  const SizedBox(height: 20),
                ],
              ),
            ),
    );
  }

  // --- WIDGET HELPER METHODS ---

  Widget _buildLabel(String text) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8.0),
      child: Text(text,
          style: TextStyle(
              fontSize: 14, fontWeight: FontWeight.bold, color: _textBrown)),
    );
  }

  Widget _buildTextField(
      {required TextEditingController controller,
      bool isReadOnly = false,
      int maxLines = 1}) {
    return Container(
      decoration: BoxDecoration(
          color: _inputFillColor, borderRadius: BorderRadius.circular(8)),
      child: TextField(
        controller: controller,
        readOnly: isReadOnly,
        maxLines: maxLines,
        style: TextStyle(
          color: isReadOnly
              ? Colors.grey
              : Colors.black87, // Nếu chỉ đọc thì mờ đi một chút
          fontSize: 15,
        ),
        decoration: const InputDecoration(
          border: InputBorder.none,
          contentPadding: EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        ),
      ),
    );
  }
}
