import 'dart:async';
import 'package:flutter/material.dart';
import 'package:geolocator/geolocator.dart';
import 'package:firebase_database/firebase_database.dart';

class ChefTrackingService {
  final _dbRef = FirebaseDatabase.instance.ref("active_deliveries");
  StreamSubscription<Position>? _positionStream;

  // Gọi hàm này khi Chef bấm nút "Đi giao hàng"
  void startTracking(String orderUid) async {
    // Cấu hình chỉ lấy tọa độ mới khi Chef di chuyển ít nhất 5 mét (tiết kiệm pin & data)
    LocationSettings locationSettings = const LocationSettings(
      accuracy: LocationAccuracy.high,
      distanceFilter: 5, 
    );

    _positionStream = Geolocator.getPositionStream(locationSettings: locationSettings)
        .listen((Position position) {
      
      // Bắn tọa độ mới nhất lên Firebase
      _dbRef.child(orderUid).set({
        "lat": position.latitude,
        "lng": position.longitude,
        "heading": position.heading, // Hướng xoay của xe
        "updated_at": DateTime.now().millisecondsSinceEpoch,
      });
      
      debugPrint("📍 Đã cập nhật vị trí Chef: ${position.latitude}, ${position.longitude}");
    });
  }

  // Gọi hàm này khi Chef bấm "Hoàn thành đơn hàng"
  void stopTracking(String orderUid) {
    _positionStream?.cancel();
    _dbRef.child(orderUid).remove(); // Xóa data trên Firebase cho sạch
  }
}