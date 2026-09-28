import 'package:flutter/material.dart';
import '../../../core/network/api_client.dart'; // Import ApiClient của bạn
import '../models/chef_model.dart';

class ChefRepository {
  final ApiClient _api;

  ChefRepository({ApiClient? api}) : _api = api ?? ApiClient();

  // ===========================================================================
  // 1. GET ALL CHEF PROFILES
  // Endpoint: GET /api/chef-profiles/
  // Swagger Page 53: Trả về List [ { ... }, { ... } ]
  // ===========================================================================
  Future<List<ChefModel>> getPopularChefs() async {
    try {
      // Gọi endpoint /popular mà chúng ta vừa tạo ở Backend
      final response = await _api.get('/api/chef-profiles/popular');

      debugPrint("GetPopularChefs Response: $response");

      List<dynamic> list = _extractList(response);

      return list.map((e) => ChefModel.fromJson(e)).toList();
    } catch (e) {
      debugPrint("GetPopularChefs Error: $e");
      return [];
    }
  }

  Future<List<ChefModel>> getAllChefs2({int page = 1, String sortBy = "rating_desc"}) async {
    try {
      final response = await _api.get('/api/chef-profiles/?page=$page&sort_by=$sortBy');

      debugPrint("GetAllChefs (Page $page, Sort: $sortBy) Response: $response");

      final List<dynamic> dataList = response['data']['items'] ?? [];
      return dataList.map((json) => ChefModel.fromJson(json)).toList();
    } catch (e) {
      debugPrint("GetAllChefs Error: $e");
      return [];
    }
  }

  // ===========================================================================
  // 2. GET CHEF DETAIL
  // Endpoint: GET /api/chef-profiles/{chef_id}
  // Swagger Page 54: Trả về Object { ... }
  // ===========================================================================
  Future<ChefModel?> getChefDetail(String chefId) async {
    try {
      final response = await _api.get('/api/chef-profiles/$chefId');
      debugPrint("GetChefDetail Response: $response");
      // ApiClient thường trả về Map nếu success
      if (response is Map<String, dynamic>) {
        // Xử lý trường hợp backend bọc data: { "data": {...} }
        final data = response.containsKey('data') ? response['data'] : response;
        return ChefModel.fromJson(data);
      }
      return null;
    } catch (e) {
      debugPrint("GetChefDetail Error: $e");
      return null;
    }
  }

  // ---------------------------------------------------------------------------
  // Helper: Xử lý cấu trúc JSON không nhất quán từ Backend
  // Có lúc trả về [ ... ], có lúc trả về { "data": [ ... ] }
  // ---------------------------------------------------------------------------
  List<dynamic> _extractList(dynamic response) {
    if (response is List) {
      return response;
    } 
    if (response is Map) {
      if (response['data'] is List) return response['data'];
      if (response['content'] is List) return response['content']; // Paging support
      if (response['results'] is List) return response['results'];
    }
    return [];
  }
}