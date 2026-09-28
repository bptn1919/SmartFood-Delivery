import 'package:flutter/material.dart';
import '../../../core/network/api_client.dart';
import '../models/address_model.dart'; 

class AddressRepository {
  final ApiClient _api;

  AddressRepository({ApiClient? api}) : _api = api ?? ApiClient();

  // Helper: Normalize response data
  Map<String, dynamic> _normalizeData(dynamic data) {
    if (data is Map<String, dynamic>) {
      if (data.containsKey('data') && data['data'] is Map) {
        return Map<String, dynamic>.from(data['data']);
      }
      return data;
    }
    return {};
  }

  // GET CUSTOMER ADDRESS
  Future<AddressModel?> getCustomerAddress() async {
    try {
      
      final response = await _api.get('/api/customer-profiles/addresses');
      
      final data = _normalizeData(response);
      
      if (data.isEmpty) {
        debugPrint('⚠️ [AddressRepo] No address data found');
        return null;
      }
      
      final address = AddressModel.fromJson(data);
      debugPrint('✅ [AddressRepo] Fetched address: ${address.fullAddress}');
      
      return address;
      
    } catch (e) {
      debugPrint('❌ [AddressRepo] Error fetching address: $e');
      return null;
    }
  }

  // Helper methods
  String formatAddressDisplay(AddressModel address) {
    return address.fullAddress;
  }

  String getShortAddress(AddressModel address, {int maxLength = 50}) {
    if (address.fullAddress.length <= maxLength) {
      return address.fullAddress;
    }
    return '${address.fullAddress.substring(0, maxLength)}...';
  }
}