import 'package:flutter/material.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';
import 'dart:io';
import 'package:image_picker/image_picker.dart';
import 'package:testing/features/common/app_components.dart';
import 'package:testing/features/map/google_map_picker_page.dart';
import 'package:testing/features/profile/models/chef_profile_model.dart';
import 'package:testing/features/profile/repository/chef_profile_repository.dart';
import 'package:testing/features/profile/repository/attachment_repository.dart';

class EditChefProfilePage extends StatefulWidget {
  final String token;
  final int chefId; // Cần ID của Chef để fetch & update đúng người

  const EditChefProfilePage({
    super.key,
    required this.token,
    required this.chefId,
  });

  @override
  State<EditChefProfilePage> createState() => _EditChefProfilePageState();
}

class _EditChefProfilePageState extends State<EditChefProfilePage> {
  // --- TONE MÀU HỆ THỐNG AMOMEAL ---
  final Color _primaryRed = AppColors.primaryRed;
  final Color _textBrown = AppColors.brownText;
  final Color _inputFillColor = AppColors.inputSoftGrey;

  // --- CONTROLLERS ---
  final TextEditingController _nameController = TextEditingController();
  final TextEditingController _phoneController = TextEditingController();
  final TextEditingController _emailController = TextEditingController();
  final TextEditingController _bioController = TextEditingController();
  final TextEditingController _specialtyController =
      TextEditingController(); // Bổ sung cho Chef

  final TextEditingController _kitchenAddressController =
      TextEditingController();
  final TextEditingController _kitchenStreetController =
      TextEditingController();
  final TextEditingController _kitchenWardController = TextEditingController();
  final TextEditingController _kitchenDistrictController =
      TextEditingController();
  final TextEditingController _kitchenCityController = TextEditingController();

  LatLng? _selectedKitchenLocation;

  // --- STATE VARIABLES ---
  double _rating = 0.0;
  int _orders = 0;
  String? _avatarUrl;

  BankModel? _bankInfo;

  File? _newAvatarFile;
  final ImagePicker _picker = ImagePicker();

  // --- REPOSITORY & STATE ---
  final ChefProfileRepository _chefRepo = ChefProfileRepository();
  final AttachmentRepository _imageRepo = AttachmentRepository();
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
        _avatarUrl = null;
      });
    }
  }

  // GỌI API: LẤY THÔNG TIN CHEF KHI VÀO TRANG
  Future<void> _fetchUserData() async {
    try {
      final profileData = await _chefRepo.getChefProfile(widget.chefId);
      if (!mounted) return;
      // MOCK DATA: Tạm thời giả lập để UI không báo lỗi

      debugPrint("✅ API getChefProfile trả về: $profileData");

      if (profileData != null) {
        // Gán dữ liệu thật từ ChefProfileModel
        _nameController.text = profileData.fullname ?? "";
        _phoneController.text = profileData.phone ?? "";
        _emailController.text = profileData.mail ?? "";
        _bioController.text = profileData.bio ?? "";
        _specialtyController.text = profileData.specialty ?? "";

        _kitchenAddressController.text = profileData.kitchenAddress ?? "";
        _kitchenStreetController.text = profileData.kitchenStreet ?? "";
        _kitchenWardController.text = profileData.kitchenWard ?? "";
        _kitchenDistrictController.text = profileData.kitchenDistrict ?? "";
        _kitchenCityController.text = profileData.kitchenCity ?? "";

        if (profileData.kitchenLatitude != null &&
            profileData.kitchenLongitude != null) {
          _selectedKitchenLocation = LatLng(
              profileData.kitchenLatitude!, profileData.kitchenLongitude!);
        }

        _rating = profileData.rating;
        _orders = profileData.numberOfOrders;
        _avatarUrl = profileData.avatar;

        _bankInfo = profileData.bank;
      } else {
        showAppSnackBar(context, "Cannot fetch Chef information!",
            type: SnackBarType.error);
      }

      if (mounted) setState(() => _isLoading = false);
    } catch (e) {
      debugPrint("Error loading Chef information: $e");
      if (mounted) {
        setState(() => _isLoading = false);
        showAppSnackBar(context, "Cannot load Chef information!",
            type: SnackBarType.error);
      }
    }
  }

  // GỌI API: LƯU THÔNG TIN CHEF
  Future<void> _saveProfile() async {
    setState(() => _isSaving = true);

    try {
      String? attachmentUid;

      // BƯỚC 1: Upload ảnh nếu có thay đổi
      if (_newAvatarFile != null) {
        attachmentUid = await _imageRepo.uploadAndGetAttachmentId(
          _newAvatarFile!,
          widget.token,
          'CHEF_AVATAR',
        );
        if (attachmentUid == null) {
          throw Exception("Failed to upload avatar image");
        }
      }

      // BƯỚC 2: Gọi API Update Chef Profile (Chuẩn bị sẵn cho bạn lát nữa code API)

      final success = await _chefRepo.updateChefProfile(
        bio: _bioController.text.trim(),
        specialty: _specialtyController.text.trim(),
        attachmentUid: attachmentUid,
      );

      if (!mounted) return;

      if (success) {
        showAppSnackBar(context, "Update successfully!",
            type: SnackBarType.success);
        Navigator.pop(context, true);
      } else {
        showAppSnackBar(context, "Error when updating!",
            type: SnackBarType.error);
      }
    } catch (e) {
      debugPrint("Error saving information: $e");
      if (mounted) {
        showAppSnackBar(context, 'Error: ${e.toString()}',
            type: SnackBarType.error);
      }
    } finally {
      if (mounted) setState(() => _isSaving = false);
    }
  }

  @override
  void dispose() {
    _nameController.dispose();
    _phoneController.dispose();
    _emailController.dispose();
    _bioController.dispose();
    _specialtyController.dispose();
    _kitchenAddressController.dispose();
    _kitchenStreetController.dispose();
    _kitchenWardController.dispose();
    _kitchenDistrictController.dispose();
    _kitchenCityController.dispose();
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
          "Edit Chef Profile",
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
                  // --- AVATAR & RATING ---
                  Center(
                    child: Column(
                      children: [
                        GestureDetector(
                          onTap: _pickAvatar,
                          child: Stack(
                            alignment: Alignment.bottomRight,
                            children: [
                              CircleAvatar(
                                radius: 45,
                                backgroundColor: _inputFillColor,
                                backgroundImage: _newAvatarFile != null
                                    ? FileImage(_newAvatarFile!)
                                        as ImageProvider
                                    : (_avatarUrl != null &&
                                            _avatarUrl!.isNotEmpty
                                        ? NetworkImage(_avatarUrl!)
                                        : null),
                                child: _newAvatarFile == null &&
                                        (_avatarUrl == null ||
                                            _avatarUrl!.isEmpty)
                                    ? const Icon(Icons.restaurant,
                                        size: 40, color: Colors.grey)
                                    : null,
                              ),
                              Container(
                                padding: const EdgeInsets.all(6),
                                decoration: const BoxDecoration(
                                  color: Colors.white,
                                  shape: BoxShape.circle,
                                  boxShadow: [
                                    BoxShadow(
                                        color: Colors.black12, blurRadius: 4)
                                  ],
                                ),
                                child: Icon(Icons.camera_alt,
                                    size: 16, color: _primaryRed),
                              ),
                            ],
                          ),
                        ),
                        const SizedBox(height: 12),
                        Row(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            const Icon(Icons.star,
                                color: Colors.amber, size: 20),
                            const SizedBox(width: 4),
                            Text(
                              "${_rating.toStringAsFixed(1)} ($_orders orders)",
                              style: TextStyle(
                                  color: _textBrown,
                                  fontWeight: FontWeight.bold,
                                  fontSize: 16),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 30),

                  // --- FORM FIELDS ---
                  _buildLabel("Chef Name"),
                  _buildTextField(
                      controller: _nameController, isReadOnly: true),
                  const SizedBox(height: 20),

                  _buildLabel("Specialty (e.g. Italian, Vegan)"),
                  _buildTextField(controller: _specialtyController),
                  const SizedBox(height: 20),

                  _buildLabel("Bio"),
                  _buildTextField(controller: _bioController, maxLines: 3),
                  const SizedBox(height: 20),

                  _buildLabel("Phone Number"),
                  _buildTextField(
                      controller: _phoneController, isReadOnly: true),
                  const SizedBox(height: 20),

                  _buildLabel("Email"),
                  _buildTextField(
                      controller: _emailController, isReadOnly: true),
                  const SizedBox(height: 20),

                  const SizedBox(height: 30),

                  // --- KITCHEN LOCATION SECTION ---
                  Text(
                    "Kitchen Location",
                    style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.bold,
                        color: Colors.brown.shade800),
                  ),
                  const SizedBox(height: 15),

                  // Nút xem/sửa bản đồ
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
                        // Tái sử dụng màn hình GoogleMapPickerPage
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
                            : "Kitchen Location Updated!",
                        style: TextStyle(
                            color: _selectedKitchenLocation == null
                                ? Colors.redAccent
                                : Colors.green,
                            fontWeight: FontWeight.bold),
                      ),
                    ),
                  ),
                  const SizedBox(height: 15),

                  _buildLabel("House No., Building Name"),
                  _buildTextField(controller: _kitchenAddressController),
                  const SizedBox(height: 20),

                  _buildLabel("Street"),
                  _buildTextField(controller: _kitchenStreetController),
                  const SizedBox(height: 20),

                  _buildLabel("Ward"),
                  _buildTextField(controller: _kitchenWardController),
                  const SizedBox(height: 20),

                  _buildLabel("District"),
                  _buildTextField(controller: _kitchenDistrictController),
                  const SizedBox(height: 20),

                  _buildLabel("City"),
                  _buildTextField(controller: _kitchenCityController),

                  const SizedBox(height: 30),

                  // --- BANK INFO (READ ONLY) ---
                  if (_bankInfo != null) ...[
                    _buildLabel("Bank Information"),
                    Container(
                      width: double.infinity,
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        color: _inputFillColor,
                        borderRadius: BorderRadius.circular(10),
                        border: Border.all(color: Colors.grey.shade300),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          // Dòng tiêu đề: Tên NH + Trạng thái
                          Row(
                            children: [
                              Icon(Icons.account_balance,
                                  color: _primaryRed, size: 22),
                              const SizedBox(width: 8),
                              Expanded(
                                child: Text(
                                  _bankInfo!.bankName ?? "Unknown Bank",
                                  style: TextStyle(
                                      fontWeight: FontWeight.bold,
                                      fontSize: 16,
                                      color: _textBrown),
                                ),
                              ),
                              // Badge Trạng thái
                              Container(
                                padding: const EdgeInsets.symmetric(
                                    horizontal: 10, vertical: 4),
                                decoration: BoxDecoration(
                                  color: _bankInfo!.isVerified
                                      ? Colors.green.withValues(alpha: 0.1)
                                      : Colors.orange.withValues(alpha: 0.1),
                                  borderRadius: BorderRadius.circular(12),
                                ),
                                child: Text(
                                  _bankInfo!.isVerified
                                      ? "Verified"
                                      : "Pending",
                                  style: TextStyle(
                                    color: _bankInfo!.isVerified
                                        ? Colors.green
                                        : Colors.orange,
                                    fontSize: 12,
                                    fontWeight: FontWeight.bold,
                                  ),
                                ),
                              )
                            ],
                          ),
                          const Divider(height: 24, thickness: 1),

                          // Các dòng chi tiết
                          _buildBankDetailRow(
                              "Account Name", _bankInfo!.bankAccountName),
                          const SizedBox(height: 10),
                          _buildBankDetailRow(
                              "Account No.", _bankInfo!.bankAccountNumber),
                          const SizedBox(height: 10),
                          _buildBankDetailRow("Branch", _bankInfo!.bankBranch),
                          const SizedBox(height: 10),
                          _buildBankDetailRow("Bank Code", _bankInfo!.bankCode),

                          const SizedBox(height: 16),

                          // Cảnh báo
                          Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              const Icon(Icons.info_outline,
                                  size: 16, color: Colors.redAccent),
                              const SizedBox(width: 6),
                              Expanded(
                                child: Text(
                                  "Bank details are locked for security. To update your payout account, please contact Amomeal Support.",
                                  style: TextStyle(
                                      fontSize: 12,
                                      color: Colors.grey.shade700,
                                      fontStyle: FontStyle.italic),
                                ),
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 40),
                  ],

                  // --- NÚT SAVE ---
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
                              "Save Changes",
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

  // Widget helper để vẽ các dòng chi tiết ngân hàng cho thẳng hàng
  Widget _buildBankDetailRow(String label, String? value) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          width: 110, // Độ rộng cố định để label thẳng cột với nhau
          child: Text(label,
              style: const TextStyle(color: Colors.grey, fontSize: 13)),
        ),
        Expanded(
          child: Text(
            value != null && value.isNotEmpty ? value : "N/A",
            style: const TextStyle(
                fontWeight: FontWeight.w600,
                fontSize: 14,
                color: Colors.black87),
          ),
        ),
      ],
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
          color: isReadOnly ? Colors.grey : Colors.black87,
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
