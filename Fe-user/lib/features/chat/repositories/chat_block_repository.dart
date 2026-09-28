import 'package:flutter/foundation.dart';

import '../../../core/network/api_client.dart';

class ChatBlockRepository {
  final ApiClient _api;

  ChatBlockRepository({ApiClient? api}) : _api = api ?? ApiClient();

  Future<void> blockUser(int userId) async {
    await _api.post('/api/users/$userId/block', {});
  }

  Future<void> unblockUser(int userId) async {
    await _api.delete('/api/users/$userId/block');
  }

  Future<List<int>> getBlockedUserIds() async {
    final response = await _api.get('/api/users/blocked');
    final rawItems = _extractBlockedItems(response);
    return rawItems.map(_extractUserId).whereType<int>().toList();
  }

  List<dynamic> _extractBlockedItems(dynamic response) {
    if (response is List) return response;
    if (response is Map<String, dynamic>) {
      final data = response['data'];
      if (data is List) return data;
      if (data is Map<String, dynamic>) {
        final nested =
            data['results'] ?? data['blocked_users'] ?? data['users'];
        if (nested is List) return nested;
      }

      final results =
          response['results'] ?? response['blocked_users'] ?? response['users'];
      if (results is List) return results;
    }
    debugPrint('Unexpected blocked users response shape: $response');
    return const [];
  }

  int? _extractUserId(dynamic item) {
    if (item is int) return item;
    if (item is num) return item.toInt();
    if (item is Map<String, dynamic>) {
      final directId = item['user_id'] ??
          item['id'] ??
          item['blocked_user_id'] ??
          item['uid'];
      if (directId is num) return directId.toInt();

      final blockedUser = item['blocked_user'] ?? item['user'];
      if (blockedUser is Map<String, dynamic>) {
        final nestedId = blockedUser['user_id'] ?? blockedUser['id'];
        if (nestedId is num) return nestedId.toInt();
      }
    }
    return null;
  }
}
