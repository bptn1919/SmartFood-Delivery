import 'package:flutter/material.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';
import 'package:geolocator/geolocator.dart'; // Import thư viện mới

class GoogleMapPickerPage extends StatefulWidget {
  const GoogleMapPickerPage({super.key});

  @override
  State<GoogleMapPickerPage> createState() => _GoogleMapPickerPageState();
}

class _GoogleMapPickerPageState extends State<GoogleMapPickerPage> {
  GoogleMapController? _mapController;
  
  // Mặc định lúc vừa mở map sẽ ở Quận 1 (hoặc bạn có thể để tọa độ trường Bách Khoa)
  LatLng _currentCenter = const LatLng(10.7769, 106.7009);
  
  final Color _primaryRed = const Color(0xFFE55866);

  @override
  void initState() {
    super.initState();
    _getUserCurrentLocation(); // Gọi hàm lấy vị trí ngay khi mở màn hình
  }

  // 👇 TECH LEAD ADD: Hàm xử lý quyền và lấy vị trí thật
  Future<void> _getUserCurrentLocation() async {
    bool serviceEnabled;
    LocationPermission permission;

    // 1. Kiểm tra GPS của máy có bật không
    serviceEnabled = await Geolocator.isLocationServiceEnabled();
    if (!serviceEnabled) return; // Nếu tắt GPS thì thôi, dùng tọa độ mặc định

    // 2. Kiểm tra quyền App
    permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied) {
      permission = await Geolocator.requestPermission(); // Mở popup xin quyền
      if (permission == LocationPermission.denied) return;
    }
    
    if (permission == LocationPermission.deniedForever) return;

    // 3. Lấy tọa độ hiện tại
    Position position = await Geolocator.getCurrentPosition(desiredAccuracy: LocationAccuracy.high);
    
    LatLng realLocation = LatLng(position.latitude, position.longitude);

    // 4. Dời Camera bản đồ về đúng chỗ user đang đứng
    if (_mapController != null) {
      _mapController!.animateCamera(CameraUpdate.newLatLngZoom(realLocation, 16.0));
      setState(() {
        _currentCenter = realLocation; // Cập nhật lại biến chứa tọa độ để trả về
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Colors.white,
        foregroundColor: Colors.black,
        elevation: 0,
        title: const Text("Pin Your Location", style: TextStyle(fontWeight: FontWeight.bold)),
        centerTitle: true,
      ),
      body: Stack(
        alignment: Alignment.center,
        children: [
          GoogleMap(
            initialCameraPosition: CameraPosition(
              target: _currentCenter,
              zoom: 16.0,
            ),
            myLocationEnabled: true, // Chấm xanh sẽ hoạt động mượt mà vì đã cấp quyền!
            myLocationButtonEnabled: true, // Bật nút bấm để nhảy về chỗ cũ
            onMapCreated: (controller) => _mapController = controller,
            onCameraMove: (CameraPosition position) {
              _currentCenter = position.target;
            },
          ),
          
          const Padding(
            padding: EdgeInsets.only(bottom: 40.0),
            child: Icon(Icons.location_on, size: 50, color: Colors.redAccent),
          ),

          Positioned(
            bottom: 30,
            left: 24,
            right: 24,
            child: ElevatedButton(
              style: ElevatedButton.styleFrom(
                backgroundColor: _primaryRed,
                padding: const EdgeInsets.symmetric(vertical: 16),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                elevation: 4,
              ),
              onPressed: () {
                Navigator.pop(context, _currentCenter);
              },
              child: const Text(
                "Confirm Location", 
                style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Colors.white)
              ),
            ),
          ),
        ],
      ),
    );
  }
}