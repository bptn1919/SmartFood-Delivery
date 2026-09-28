import 'package:flutter/material.dart';
import '../models/address_item.dart';
import '../repositories/edit_order_repository.dart';
import '../../common/app_components.dart';
import 'package:testing/features/map/google_map_picker_page.dart';


class AddAddressPage extends StatefulWidget {
  const AddAddressPage({super.key});

  @override
  State<AddAddressPage> createState() => _AddAddressPageState();
}

class _AddAddressPageState extends State<AddAddressPage> {
  // Styling colors matching the pattern
  final Color _primaryOrange = const Color(0xFFFFB68C);
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _textBrown = const Color(0xFF4A3225);
  final Color _inputYellow = const Color(0xFFF9F0C2);

  final _formKey = GlobalKey<FormState>();
  final _addressCtrl = TextEditingController();
  final _streetCtrl = TextEditingController();
  final _wardCtrl = TextEditingController();
  final _districtCtrl = TextEditingController();

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

  final _repo = EditOrderRepository();

  bool _posting = false;
  
  // 👇 TECH LEAD: Thêm State để lưu tọa độ
  double? _selectedLat;
  double? _selectedLng;

  @override
  void dispose() {
    _addressCtrl.dispose();
    _streetCtrl.dispose();
    _wardCtrl.dispose();
    _districtCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    // 1. Validate Form text
    if (!_formKey.currentState!.validate() || _posting) return;
    
    // 👇 TECH LEAD: 2. Validate Tọa độ (Bắt buộc phải ghim map)
    if (_selectedLat == null || _selectedLng == null) {
      showAppSnackBar(context, 'Please pin your location on the map!', type: SnackBarType.warning);
      return;
    }

    setState(() => _posting = true);
    try {
      // 👇 TECH LEAD: Cập nhật hàm gọi API, truyền thêm latitude và longitude
      // LƯU Ý: Nhớ mở file EditOrderRepository và thêm 2 tham số này vào hàm createCustomerAddress nhé!
      final created = await _repo.createCustomerAddress(
        address: _addressCtrl.text.trim(),
        street: _streetCtrl.text.trim(),
        ward: _wardCtrl.text.trim(),
        district: _districtCtrl.text.trim(),
        city: _selectedCity!,
        latitude: _selectedLat, // Cung cấp giá trị mặc định nếu null (nên có vì đã validate ở trên)
        longitude: _selectedLng,
      );
      
      if (!mounted) return;
      Navigator.pop<AddressItem>(context, created);
    } catch (e) {
      if (!mounted) return;
      showAppSnackBar(context, 'Create address failed: $e', type: SnackBarType.error);
    } finally {
      if (mounted) setState(() => _posting = false);
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
                  icon: const Icon(Icons.chevron_left, color: Colors.black, size: 30),
                  onPressed: () => Navigator.of(context).pop(),
                ),
                const Expanded(
                  child: Text(
                    "Add New Address",
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: Colors.white,
                      fontSize: 24, // Giảm nhẹ font để không bị tràn màn hình nhỏ
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
                physics: const BouncingScrollPhysics(),
                padding: const EdgeInsets.all(24),
                child: Form(
                  key: _formKey,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      // 👇 TECH LEAD: WIDGET CHỌN TỌA ĐỘ MAP
                      _buildLabel("Pin Location"),
                      const SizedBox(height: 8),
                      InkWell(
                        onTap: () async {
                          // Tạm thời comment dòng này nếu bạn chưa import GoogleMapPickerPage
                          
                          final pickedLocation = await Navigator.push(
                            context,
                            MaterialPageRoute(
                              builder: (context) => const GoogleMapPickerPage(),
                            ),
                          );

                          if (pickedLocation != null) {
                            setState(() {
                              _selectedLat = pickedLocation.latitude;
                              _selectedLng = pickedLocation.longitude;
                            });
                          }
                          
                        },
                        borderRadius: BorderRadius.circular(10),
                        child: Container(
                          padding: const EdgeInsets.symmetric(vertical: 16, horizontal: 16),
                          decoration: BoxDecoration(
                            color: _selectedLat != null 
                                ? Colors.green.withOpacity(0.1) 
                                : _primaryRed.withOpacity(0.1),
                            borderRadius: BorderRadius.circular(10),
                            border: Border.all(
                              color: _selectedLat != null 
                                  ? Colors.green.withOpacity(0.3) 
                                  : _primaryRed.withOpacity(0.3),
                            ),
                          ),
                          child: Row(
                            children: [
                              Icon(
                                Icons.map_rounded, 
                                color: _selectedLat != null ? Colors.green : _primaryRed,
                              ),
                              const SizedBox(width: 12),
                              Expanded(
                                child: Text(
                                  _selectedLat != null 
                                      ? "Location Pinned! ✓" 
                                      : "Tap to pin location on Map",
                                  style: TextStyle(
                                    color: _selectedLat != null ? Colors.green : _primaryRed,
                                    fontWeight: FontWeight.bold,
                                  ),
                                ),
                              ),
                              Icon(
                                Icons.chevron_right, 
                                color: _selectedLat != null ? Colors.green : _primaryRed,
                              ),
                            ],
                          ),
                        ),
                      ),
                      const SizedBox(height: 24),

                      // Address (house no., building)
                      _buildLabel("Address (house no., building)"),
                      const SizedBox(height: 8),
                      TextFormField(
                        controller: _addressCtrl,
                        decoration: _decor(),
                        validator: (v) => (v == null || v.trim().isEmpty) ? 'Required' : null,
                      ),
                      const SizedBox(height: 20),

                      // Street
                      _buildLabel("Street"),
                      const SizedBox(height: 8),
                      TextFormField(
                        controller: _streetCtrl,
                        decoration: _decor(),
                        validator: (v) => (v == null || v.trim().isEmpty) ? 'Required' : null,
                      ),
                      const SizedBox(height: 20),

                      // Ward
                      _buildLabel("Ward"),
                      const SizedBox(height: 8),
                      TextFormField(
                        controller: _wardCtrl,
                        decoration: _decor(),
                        validator: (v) => (v == null || v.trim().isEmpty) ? 'Required' : null,
                      ),
                      const SizedBox(height: 20),

                      // District
                      _buildLabel("District"),
                      const SizedBox(height: 8),
                      TextFormField(
                        controller: _districtCtrl,
                        decoration: _decor(),
                        validator: (v) => (v == null || v.trim().isEmpty) ? 'Required' : null,
                      ),
                      const SizedBox(height: 20),

                      // City
                      _buildLabel("City"),
                      const SizedBox(height: 8),
                      DropdownButtonFormField<String>(
                        value: _selectedCity,
                        decoration: _decor(), // Tái sử dụng cái hàm _decor() nền vàng của bạn
                        icon: const Icon(Icons.keyboard_arrow_down_rounded),
                        dropdownColor: Colors.white, // Nền của list xổ xuống
                        borderRadius: BorderRadius.circular(16), // Bo góc cho cái list xổ xuống
                        hint: const Text(
                          "Select your city",
                          style: TextStyle(color: Colors.black38, fontSize: 13),
                        ),
                        items: _cities.map((String city) {
                          return DropdownMenuItem<String>(
                            value: city,
                            child: Text(
                              city,
                              style: const TextStyle(fontSize: 14, color: Colors.black87),
                            ),
                          );
                        }).toList(),
                        onChanged: (String? newValue) {
                          setState(() {
                            _selectedCity = newValue;
                          });
                        },
                        validator: (v) => v == null || v.isEmpty ? 'Please select a city' : null,
                      ),

                      const SizedBox(height: 40),
                      
                      // Submit Button
                      Center(
                        child: SizedBox(
                          width: double.infinity,
                          height: 54, // Tăng chút height cho nút dễ bấm hơn
                          child: ElevatedButton(
                            onPressed: _posting ? null : _submit,
                            style: ElevatedButton.styleFrom(
                              backgroundColor: _primaryRed,
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(25),
                              ),
                              elevation: 2,
                            ),
                            child: _posting
                                ? const SizedBox(
                                    width: 24,
                                    height: 24,
                                    child: CircularProgressIndicator(
                                      strokeWidth: 2.5,
                                      color: Colors.white,
                                    ),
                                  )
                                : const Text(
                                    'Save Address', // Đổi text cho ngắn gọn & Call-to-action tốt hơn
                                    style: TextStyle(
                                      color: Colors.white,
                                      fontSize: 16,
                                      fontWeight: FontWeight.bold,
                                    ),
                                  ),
                          ),
                        ),
                      ),
                      const SizedBox(height: 20), // Padding đáy
                    ],
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildLabel(String text) {
    return Text(
      text,
      style: TextStyle(
        fontSize: 14,
        fontWeight: FontWeight.bold,
        color: _textBrown,
      ),
    );
  }

  InputDecoration _decor() => InputDecoration(
        filled: true,
        fillColor: _inputYellow,
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide: BorderSide.none,
        ),
        hintStyle: const TextStyle(color: Colors.black38, fontSize: 13),
      );
}
