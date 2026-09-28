// lib/data/repositories/customer_repository.dart

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import '../../../core/network/api_client.dart';
import '../models/customer_address.dart';

class CustomerRepository {
  final ApiClient _apiClient;

  //CustomerRepository(this._apiClient);
  CustomerRepository({ApiClient? api}) : _apiClient = api ?? ApiClient();

  /// Helper để chuẩn hóa dữ liệu trả về từ API
  /// Xử lý trường hợp BE trả về { "data": {...} } hoặc {...} trực tiếp
  Map<String, dynamic> _normalizeData(dynamic data) {
    if (data is Map<String, dynamic>) {
      if (data.containsKey('data') && data['data'] is Map) {
        return Map<String, dynamic>.from(data['data']);
      }
      return data;
    }
    return {};
  }

  /// GET /api/customer-profiles/
  /// Lấy địa chỉ/profile mặc định hoặc hiện tại
  Future<CustomerAddress?> getCurrentAddress() async {
    try {
      final response = await _apiClient.get('/api/customer-profiles/');
      
      final body = _normalizeData(response);
      if (body.isEmpty) return null;
      
      return CustomerAddress.fromJson(body);
    } catch (e) {
      debugPrint('❌ [getCurrentAddress] Error: $e');
      return null;
    }
  }

  /// GET /api/customer-profiles/all-addresses
  /// Lấy danh sách tất cả địa chỉ đã lưu
  Future<List<CustomerAddress>> getAllAddresses() async {
    try {
      final response = await _apiClient.get('/api/customer-profiles/addresses');
      final rawData = response;

      List<dynamic> listRaw = [];

      // Logic bóc tách List tương tự CartRepository
      if (rawData is List) {
        listRaw = rawData;
      } else if (rawData is Map && rawData['data'] is List) {
        listRaw = rawData['data'];
      }

      return listRaw
          .map((e) => CustomerAddress.fromJson(Map<String, dynamic>.from(e)))
          .toList();
    } catch (e) {
      debugPrint('❌ [getAllAddresses] Error: $e');
      return const [];
    }
  }
}