import 'package:http/http.dart' as http;
import 'package:flutter/material.dart';
import 'package:flutter_image_compress/flutter_image_compress.dart';
import 'package:path_provider/path_provider.dart';
import 'package:testing/core/network/api_client.dart';
import 'dart:convert';
import 'dart:io';

import 'package:testing/core/network/api_constants.dart';
import 'package:testing/features/profile/models/customer_profile_model.dart';

class CustomerProfileRepository {
  final String apiBaseUrl = ApiConstants.baseUrl; // Đổi lại theo IP server của bạn
  final ApiClient _api;

  CustomerProfileRepository({ApiClient? api}) : _api = api ?? ApiClient();

  Future<CustomerProfileModel?> getCustomerProfile() async {
    try {
      // Gọi GET đến endpoint profile
      final response = await _api.get('/api/customer-profiles/');
      
      // Vì API trả về 1 Object (Map<String, dynamic>), ta map thẳng vào Model
      // (Giả định ApiClient của bạn đã parse jsonDecode trả ra Map rồi)
      return CustomerProfileModel.fromJson(response['data']);
      
    } catch (e) {
      debugPrint("GetCustomerProfile Error: $e");
      return null; // Trả về null khi có lỗi để UI tự xử lý hiển thị
    }
  }

  Future<bool> updateCustomerProfile({
    String? bio,
    String? phone,
    String? attachmentUid,
    String? dietMode,
    String? dietLevel,
    String? allergyMode,
  }) async {
    try {
      // Chỉ đóng gói những trường có dữ liệu (hoặc bạn có thể gửi luôn null nếu BE yêu cầu)
      final Map<String, dynamic> data = {};
      
      if (bio != null) data['bio'] = bio;
      if (phone != null) data['phone'] = phone;
      if (attachmentUid != null) data['attachment_uid'] = attachmentUid;
      if (dietMode != null) data['diet_mode'] = dietMode;
      if (dietLevel != null) data['diet_level'] = dietLevel;
      if (allergyMode != null) data['allergy_mode'] = allergyMode;

      // Gọi API PATCH theo đúng định dạng JSON
      await _api.patch('/api/customer-profiles/', data: data);

      debugPrint(">>>>>>>>>>>>>> UpdateProfile Success: $data");
      
      return true; // Thành công
    } catch (e) {
      debugPrint("UpdateProfile Error: $e");
      return false; // Thất bại
    }
  }
}