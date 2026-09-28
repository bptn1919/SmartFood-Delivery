import 'package:flutter/foundation.dart';
import 'package:testing/core/network/api_client.dart';
import 'package:testing/features/profile/models/customer_profile_model.dart';

class AddressRepository {
  final ApiClient _api;

  AddressRepository({ApiClient? api}) : _api = api ?? ApiClient();

  // Lấy danh sách địa chỉ (GET)
  Future<List<AddressModel>> getAddresses() async {
    try {
      final response = await _api.get('/api/customer-profiles/addresses');

      debugPrint("GetAddresses Response: $response");

      final data = response['data'];
      
      if (data is List) {
        return data.map((e) => AddressModel.fromJson(e as Map<String, dynamic>)).toList();
      }
      return [];
    } catch (e) {
      debugPrint("GetAddresses Error: $e");
      return [];
    }
  }

  // Tạo địa chỉ mới (POST)
  Future<bool> createAddress({
    required String address,
    required String street,
    required String ward,
    required String district,
    required String city,
    bool selected = false,
    double? latitude,
    double? longitude,
  }) async {
    try {
      final Map<String, dynamic> data = {
        "address": address,
        "street": street,
        "ward": ward,
        "district": district,
        "city": city,
        "selected": selected,

        if (latitude != null) "latitude": latitude,
        if (longitude != null) "longitude": longitude,
      };

      debugPrint("CreateAddress Payload: $data");

      await _api.post('/api/customer-profiles/addresses', data);
      return true;
    } catch (e) {
      debugPrint("CreateAddress Error: $e");
      return false;
    }
  }

  // Hàm gọi API set địa chỉ mặc định
  Future<bool> setDefaultAddress(int addressId) async {
    try {
      // Backend của bạn sẽ tự nhận diện URL này và xử lý transaction ngầm
      final res = await _api.put('/api/customer-profiles/$addressId/set-default', {});
      
      // Nếu API không văng lỗi (nhờ ApiClient đã ném Exception nếu status != 200)
      // Ta mặc định là thành công!
      return true;
      
    } catch (e) {
      debugPrint("🚨 [AddressRepo] Lỗi Set Default Address: $e");
      return false; 
    }
  }
}