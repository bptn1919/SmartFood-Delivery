import 'package:flutter/foundation.dart';
import 'package:testing/core/network/api_client.dart';
import 'package:testing/features/profile/models/chef_profile_model.dart';
// import 'api_client.dart';
// import 'chef_profile_model.dart';

class ChefProfileRepository {
  final ApiClient _api;

  ChefProfileRepository({ApiClient? api}) : _api = api ?? ApiClient();

  Future<ChefProfileModel?> getChefProfile(int chefId) async {
    try {
      // Nối chuỗi an toàn vào URL
      final response = await _api.get('/api/chef-profiles/$chefId');
      debugPrint("GetChefProfile Response: $response");
      
      return ChefProfileModel.fromJson(response['data']);
    } catch (e) {
      debugPrint("GetChefProfile Error: $e");
      return null;
    }
  }

  Future<bool> updateChefProfile({
    String? bio,
    String? specialty,
    String? attachmentUid,
  }) async {
    try {
      final Map<String, dynamic> data = {};
      
      // Chỉ đẩy data lên nếu có giá trị
      if (bio != null) data['bio'] = bio;
      if (specialty != null) data['specialty'] = specialty;
      if (attachmentUid != null) data['attachment_uid'] = attachmentUid;

      // Gọi đúng endpoint /me theo thiết kế của Backend
      await _api.patch('/api/chef-profiles/me', data: data);
      
      return true; // Thành công
    } catch (e) {
      debugPrint("UpdateChefProfile Error: $e");
      return false; // Thất bại
    }
  }
}