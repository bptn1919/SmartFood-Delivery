import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';
import '../../../core/network/api_client.dart'; // Import ApiClient mới
import '../models/menu_model.dart';

class MenuRepository {
  final ApiClient _api;

  MenuRepository({ApiClient? api}) : _api = api ?? ApiClient();

  // GET /api/menus/{chefId}
  // Swagger Page 41: Trả về List<Menu>
  Future<List<MenuModel>> getMenusOfChef(String chefId) async {
    try {
      final response = await _api.get('/api/menus/chef/$chefId');
      
      debugPrint("Raw response from getMenusOfChef: $response + Chef ID: $chefId");
      if (response is List) {
        return (response).map((e) => MenuModel.fromJson(e)).toList();
      } 
      
      // Case 2: Backend trả về { "data": [...] } hoặc { "content": [...] }
      // Dựa vào code cũ của bạn, có vẻ backend trả về nhiều kiểu wrapper khác nhau.
      // Ta viết hàm helper để extract list an toàn.
      final listData = _extractList(response);
      
      return listData.map((e) => MenuModel.fromJson(e)).toList();

    } catch (e) {
      debugPrint("GetMenusOfChef Error: $e");
      return [];
    }
  }

  List<dynamic> _extractList(dynamic data) {
    if (data is List) return data;
    if (data is Map) {
      if (data['content'] is List) return data['content'];
      if (data['data'] is List) return data['data'];
      if (data['results'] is List) return data['results'];
    }
    return [];
  }
}