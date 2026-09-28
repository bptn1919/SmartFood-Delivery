import 'package:flutter/material.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';
import 'package:testing/features/common/app_components.dart';
import 'package:testing/features/map/google_map_picker_page.dart';
import 'package:testing/features/profile/models/customer_profile_model.dart';
import 'package:testing/features/profile/repository/address_repository.dart';


class ShippingAddressPage extends StatefulWidget {
  const ShippingAddressPage({super.key});

  @override
  State<ShippingAddressPage> createState() => _ShippingAddressPageState();
}

class _ShippingAddressPageState extends State<ShippingAddressPage> {
  // --- TONE MÀU & STYLE ---
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _textBrown = const Color(0xFF4A3225);
  final Color _inputFillColor = const Color(0xFFEEEEEE);

  // --- REPO & STATE ---
  final AddressRepository _repo = AddressRepository();

  List<AddressModel> _addresses = [];
  bool _isLoading = true;

  bool _isUpdating = false;

  @override
  void initState() {
    super.initState();
    _fetchAddresses();
  }

  Future<void> _fetchAddresses() async {
    setState(() => _isLoading = true);
    try {
      final data = await _repo.getAddresses();
      
      if (mounted) {
        setState(() {
          _addresses = data;
          _isLoading = false;
        });
      }
    } catch (e) {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  Future<void> _handleSetDefault(int addressId) async {
    // 1. Nếu đang cập nhật cái khác rồi thì chặn lại tránh spam click
    if (_isUpdating) return;

    // 2. Bật loading (Có thể là show dialog hoặc xoay vòng tròn nhỏ)
    setState(() => _isUpdating = true);

    // 3. Gọi Repository
    final success = await _repo.setDefaultAddress(addressId);

    if (!mounted) return;

    // 4. Xử lý kết quả
    if (success) {
      // ✅ Thành công: Lấy lại danh sách mới từ Backend. 
      // Lúc này Backend đã tự set thằng cũ = false, thằng mới = true.
      await _fetchAddresses(); // Hàm fetch data của bạn
      
      showAppSnackBar(context, "Update successfully!", type: SnackBarType.success);
    } else {
      // ❌ Thất bại: Báo lỗi cho user
      showAppSnackBar(context, "Update failed, please try again!", type: SnackBarType.error);
    }

    // 5. Tắt loading
    if (mounted) setState(() => _isUpdating = false);
  }

  // --- UI: BOTTOM SHEET TẠO ĐỊA CHỈ ---
  void _showAddAddressBottomSheet() {
    // Khởi tạo controller cho Form
    final addressCtrl = TextEditingController();
    final streetCtrl = TextEditingController();
    final wardCtrl = TextEditingController();
    final districtCtrl = TextEditingController();

    final List<String> _cities = [
    "Điện Biên", 
    "Hòa Bình", 
    "Lai Châu", 
    "Lào Cai", 
    "Sơn La", 
    "Yên Bái",
    "Bắc Giang", 
    "Bắc Kạn", 
    "Cao Bằng", 
    "Hà Giang", 
    "Lạng Sơn", 
    "Phú Thọ", 
    "Quảng Ninh", 
    "Thái Nguyên", 
    "Tuyên Quang",
    "Bắc Ninh", 
    "Hà Nam", 
    "Hà Nội", 
    "Hải Dương", 
    "Hải Phòng", 
    "Hưng Yên", 
    "Nam Định", 
    "Ninh Bình", 
    "Thái Bình",
    "Vĩnh Phúc",
    "Hà Tĩnh", 
    "Nghệ An", 
    "Quảng Bình", 
    "Quảng Trị", 
    "Thanh Hóa",
    "Thừa Thiên Huế",
    "Bình Định", 
    "Bình Thuận", 
    "Đà Nẵng", 
    "Khánh Hòa", 
    "Ninh Thuận", 
    "Phú Yên", 
    "Quảng Nam",
    "Quảng Ngãi",
    "Đắk Lắk", 
    "Đắk Nông", 
    "Gia Lai", 
    "Kon Tum",
    "Lâm Đồng",
    "Bà Rịa Vũng Tàu", 
    "Bình Dương", 
    "Bình Phước", 
    "Đồng Nai", 
    "Hồ Chí Minh",
    "Tây Ninh",
    "An Giang", 
    "Bạc Liêu", 
    "Bến Tre", 
    "Cà Mau", 
    "Cần Thơ", 
    "Đồng Tháp", 
    "Hậu Giang", 
    "Kiên Giang", 
    "Long An", 
    "Sóc Trăng", 
    "Tiền Giang", 
    "Trà Vinh",
    "Vĩnh Long"
  ];
    String? _selectedCity;

    double? selectedLat;
    double? selectedLng;

    bool isSubmitting = false;

    showModalBottomSheet(
      context: context,
      isScrollControlled: true, // Cho phép vuốt lên cao khi bàn phím xuất hiện
      backgroundColor: Colors.transparent,
      builder: (ctx) {
        return StatefulBuilder(
          builder: (BuildContext context, StateSetter setModalState) {
            return Container(
              padding: EdgeInsets.only(
                bottom: MediaQuery.of(ctx).viewInsets.bottom, // Đẩy UI lên khi có bàn phím
                left: 24, right: 24, top: 24,
              ),
              decoration: const BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
              ),
              child: SingleChildScrollView(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Center(
                      child: Container(width: 40, height: 5, decoration: BoxDecoration(color: Colors.grey.shade300, borderRadius: BorderRadius.circular(10))),
                    ),
                    const SizedBox(height: 20),
                    Text("Add New Address", style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: _textBrown)),
                    const SizedBox(height: 20),

                    InkWell(
                      onTap: () async {
                        final LatLng? pickedLocation = await Navigator.push(
                          context,
                          MaterialPageRoute(
                            builder: (context) => const GoogleMapPickerPage(),
                          ),
                        );

                        // Nếu user bấm Confirm và có tọa độ trả về
                        if (pickedLocation != null) {
                          setModalState(() {
                            selectedLat = pickedLocation.latitude;
                            selectedLng = pickedLocation.longitude;
                            // Code chạy đến đây là UI đã có tọa độ chuẩn bị đẩy lên API!
                          });
                          debugPrint("Selected Location: Lat ${pickedLocation.latitude}, Lng ${pickedLocation.longitude}");
                        }
                      },
                      child: Container(
                        padding: const EdgeInsets.symmetric(vertical: 14, horizontal: 16),
                        decoration: BoxDecoration(
                          color: _primaryRed.withOpacity(0.1),
                          borderRadius: BorderRadius.circular(8),
                          border: Border.all(color: _primaryRed.withOpacity(0.3)),
                        ),
                        child: Row(
                          children: [
                            Icon(Icons.map, color: _primaryRed),
                            const SizedBox(width: 12),
                            Expanded(
                              child: Text(
                                selectedLat != null 
                                    ? "Location Pinned! ✓" 
                                    : "Tap to pin location on Map",
                                style: TextStyle(
                                  color: selectedLat != null ? Colors.green : _primaryRed,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            ),
                            Icon(Icons.chevron_right, color: _primaryRed),
                          ],
                        ),
                      ),
                    ),
                    const SizedBox(height: 16),

                    _buildTextField(controller: addressCtrl, hint: "House Number, Building..."),
                    const SizedBox(height: 12),
                    _buildTextField(controller: streetCtrl, hint: "Street"),
                    const SizedBox(height: 12),
                    _buildTextField(controller: wardCtrl, hint: "Ward"),
                    const SizedBox(height: 12),
                    _buildTextField(controller: districtCtrl, hint: "District"),
                    const SizedBox(height: 12),
                    
                    _buildDropdownField(
                      hint: "City",
                      value: _selectedCity,
                      items: _cities,
                      onChanged: (newValue) {
                        setState(() {
                          _selectedCity = newValue;
                        });
                      },
                    ),
                    const SizedBox(height: 24),

                    SizedBox(
                      width: double.infinity,
                      height: 50,
                      child: ElevatedButton(
                        style: ElevatedButton.styleFrom(backgroundColor: _primaryRed, shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)), elevation: 0),
                        onPressed: isSubmitting
                            ? null
                            : () async {
                                if (addressCtrl.text.isEmpty || streetCtrl.text.isEmpty || _selectedCity == null ) {
                                  showAppSnackBar(ctx, "Please fill all required fields", type: SnackBarType.warning);
                                  return;
                                }

                                setModalState(() => isSubmitting = true);

                                // GỌI API TẠO MỚI
                                debugPrintStack(label: "📌 [Create Address] Payload", maxFrames: 1);
                                
                                final success = await _repo.createAddress(
                                  address: addressCtrl.text.trim(),
                                  street: streetCtrl.text.trim(),
                                  ward: wardCtrl.text.trim(),
                                  district: districtCtrl.text.trim(),
                                  city: _selectedCity!,
                                  selected: true, // Mặc định khi tạo mới sẽ là địa chỉ được chọn
                                  latitude: selectedLat,
                                  longitude: selectedLng,
                                );
                              

                                setModalState(() => isSubmitting = false);

                                if (success) {
                                  Navigator.pop(ctx); // Đóng BottomSheet
                                  _fetchAddresses(); // Render lại danh sách
                                  showAppSnackBar(context, "Address added successfully", type: SnackBarType.success);
                                }
                              },
                        child: isSubmitting
                            ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2))
                            : const Text("Save Address", style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Colors.white)),
                      ),
                    ),
                    const SizedBox(height: 30),
                  ],
                ),
              ),
            );
          },
        );
      },
    ).whenComplete(() {
      addressCtrl.dispose();
      streetCtrl.dispose();
      wardCtrl.dispose();
      districtCtrl.dispose();
    });
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
            decoration: BoxDecoration(color: _primaryRed.withOpacity(0.1), borderRadius: BorderRadius.circular(8)),
            child: Icon(Icons.arrow_back_ios_new, color: _primaryRed, size: 18),
          ),
          onPressed: () => Navigator.pop(context),
        ),
        centerTitle: true,
        title: Text("Shipping Addresses", style: TextStyle(color: _textBrown, fontSize: 20, fontWeight: FontWeight.bold)),
      ),
      body: _isLoading
          ? Center(child: CircularProgressIndicator(color: _primaryRed))
          : _addresses.isEmpty
              ? _buildEmptyState()
              : _buildAddressList(),
      // FAB ĐỂ THÊM ĐỊA CHỈ
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24.0),
          child: SizedBox(
            height: 50,
            child: ElevatedButton.icon(
              style: ElevatedButton.styleFrom(
                backgroundColor: _primaryRed,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                elevation: 0,
              ),
              icon: const Icon(Icons.add, color: Colors.white),
              label: const Text("Add New Address", style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Colors.white)),
              onPressed: _showAddAddressBottomSheet,
            ),
          ),
        ),
      ),
    );
  }

  // --- HELPER WIDGETS ---
  Widget _buildEmptyState() {
    return const Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(Icons.location_off, size: 60, color: Colors.grey),
          SizedBox(height: 16),
          Text("No addresses found.", style: TextStyle(fontSize: 16, color: Colors.grey)),
          Text("Add a new address to proceed.", style: TextStyle(fontSize: 14, color: Colors.grey)),
        ],
      ),
    );
  }

  Widget _buildAddressList() {
    return ListView.separated(
      padding: const EdgeInsets.all(24),
      itemCount: _addresses.length,
      separatorBuilder: (_, __) => const SizedBox(height: 16),
      itemBuilder: (context, index) {
        final item = _addresses[index];
        
        // Tạo 1 biến trung gian để code nhìn "sạch" hơn, không phải viết item.selected == true nhiều lần
        final bool isSelected = item.selected == true; 

        return GestureDetector(
          onTap: () {
            // Chỉ gọi API nếu user bấm vào địa chỉ CHƯA được chọn
            if (!isSelected) {
              // Hàm _handleSetDefault chúng ta đã viết ở bước trước
              // Chú ý: Đảm bảo model Address của bạn có thuộc tính 'id' nhé
              _handleSetDefault(item.id); 
            }
          },
          // TECH LEAD TIP: Dùng AnimatedContainer để khi đổi màu có hiệu ứng fade rất xịn
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 300), 
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: isSelected ? _primaryRed.withOpacity(0.05) : Colors.white,
              border: Border.all(
                color: isSelected ? _primaryRed : Colors.grey.shade300, 
                width: isSelected ? 1.5 : 1,
              ),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(
                  Icons.location_on, 
                  color: isSelected ? _primaryRed : Colors.grey,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        item.fullAddress ?? "Unknown Address",
                        style: TextStyle(
                          fontSize: 15, 
                          fontWeight: isSelected ? FontWeight.bold : FontWeight.normal, 
                          color: _textBrown, 
                          height: 1.4,
                        ),
                      ),
                    ],
                  ),
                ),
                if (isSelected)
                  Icon(Icons.check_circle, color: _primaryRed)
              ],
            ),
          ),
        );
      },
    );
  }

  Widget _buildTextField({required TextEditingController controller, required String hint}) {
    return Container(
      decoration: BoxDecoration(color: _inputFillColor, borderRadius: BorderRadius.circular(8)),
      child: TextField(
        controller: controller,
        style: const TextStyle(color: Colors.black87, fontSize: 15),
        decoration: InputDecoration(
          hintText: hint,
          hintStyle: const TextStyle(color: Colors.grey),
          border: InputBorder.none,
          contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        ),
      ),
    );
  }

  Widget _buildDropdownField({
    required String hint,
    required String? value,
    required List<String> items,
    required ValueChanged<String?> onChanged,
  }) {
    return Container(
      decoration: BoxDecoration(
        color: _inputFillColor, // Dùng chung màu nền
        borderRadius: BorderRadius.circular(8), // Dùng chung độ bo góc
      ),
      child: DropdownButtonFormField<String>(
        value: value,
        isExpanded: true, // Chống lỗi tràn chữ nếu item quá dài
        icon: const Icon(Icons.keyboard_arrow_down_rounded, color: Colors.grey),
        dropdownColor: Colors.white, // Nền của list xổ xuống
        borderRadius: BorderRadius.circular(12), // Bo góc list xổ xuống
        style: const TextStyle(color: Colors.black87, fontSize: 15),
        decoration: InputDecoration(
          hintText: hint,
          hintStyle: const TextStyle(color: Colors.grey),
          border: InputBorder.none, // Ẩn viền giống hệt TextField
          contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14), // Padding y hệt
        ),
        items: items.map((String item) {
          return DropdownMenuItem<String>(
            value: item,
            child: Text(item),
          );
        }).toList(),
        onChanged: onChanged,
      ),
    );
  }
}
