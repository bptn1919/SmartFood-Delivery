import 'package:flutter/foundation.dart';
import '../../../core/network/api_client.dart';

class ReviewRepository {
  final ApiClient _apiClient;

  ReviewRepository({ApiClient? api}) : _apiClient = api ?? ApiClient();

  // ===========================================================================
  // REVIEW CRUD (Đã có)
  // ===========================================================================
  
  // 1. Create Review
  Future<bool> submitReview({
    required String orderUid,
    required String dishUid,
    required int rating,
    required String comment,
    String? attachmentUid,
  }) async {
    try {
      final payload = {
        "dish_uid": dishUid,
        "order_uid": orderUid,
        "rating": rating,
        "comment": comment,
        if (attachmentUid != null) "attachment_uid": attachmentUid,
      };

      debugPrint("🚀 CREATE REVIEW: $payload");
      final response = await _apiClient.post('/api/reviews/', payload);
      return response != null;
    } catch (e) {
      debugPrint("❌ LỖI CREATE REVIEW: $e");
      rethrow;
    }
  }

  // 2. Get My Reviews
  Future<Map<String, dynamic>?> getMyReviews({int page = 1, int pageSize = 20}) async {
    try {
      final response = await _apiClient.get(
        '/api/reviews/my-reviews',
        queryParameters: {'page': page, 'page_size': pageSize},
      );
      debugPrint("🚀 My Reviews Response: $response");
      return response as Map<String, dynamic>?;
    } catch (e) {
      debugPrint("❌ LỖI getMyReviews: $e");
      return null;
    }
  }

  // 3. Get Reviews By Dish
  Future<Map<String, dynamic>?> getDishReviews(String dishUid, {int page = 1, int pageSize = 5}) async {
    try {
      final response = await _apiClient.get(
        '/api/reviews/dish/$dishUid',
        queryParameters: {'page': page, 'page_size': pageSize},
      );
      return response as Map<String, dynamic>?;
    } catch (e) {
      debugPrint("❌ LỖI getDishReviews: $e");
      return null;
    }
  }

  // 4. Get Dish Rating Stats
  Future<Map<String, dynamic>?> getDishReviewStats(String dishUid) async {
    try {
      final response = await _apiClient.get('/api/reviews/dish/$dishUid/stats');
      return response as Map<String, dynamic>?;
    } catch (e) {
      debugPrint("❌ LỖI getDishReviewStats: $e");
      return null;
    }
  }

  // 5. Update Review
  Future<bool> updateReview({
    required String reviewUid,
    int? rating,
    String? comment,
    String? attachmentUid,
  }) async {
    try {
      final Map<String, dynamic> payload = {};
      if (rating != null) payload["rating"] = rating;
      if (comment != null) payload["comment"] = comment;
      if (attachmentUid != null) payload["attachment_uid"] = attachmentUid;

      final response = await _apiClient.patch('/api/reviews/$reviewUid', data: payload);
      return response != null;
    } catch (e) {
      debugPrint("❌ LỖI updateReview: $e");
      rethrow;
    }
  }

  // 6. Delete Review
  Future<bool> deleteReview(String reviewUid) async {
    try {
      final response = await _apiClient.delete('/api/reviews/$reviewUid');
      return response != null;
    } catch (e) {
      debugPrint("❌ LỖI deleteReview: $e");
      rethrow;
    }
  }

  // ===========================================================================
  // API BỔ SUNG (Reply và Get By Uid)
  // ===========================================================================

  // 7. Get Review By Uid
  Future<Map<String, dynamic>?> getReviewByUid(String reviewUid) async {
    try {
      final response = await _apiClient.get('/api/reviews/$reviewUid');
      return response as Map<String, dynamic>?;
    } catch (e) {
      debugPrint("❌ LỖI getReviewByUid: $e");
      return null;
    }
  }

  // 8. Create Review Reply (Chỉ chef mới có quyền)
  Future<Map<String, dynamic>?> createReviewReply({
    required String reviewUid,
    required String content,
  }) async {
    try {
      final payload = {"content": content};
      debugPrint("🚀 CREATE REPLY: $payload");
      final response = await _apiClient.post('/api/reviews/$reviewUid/reply', payload);
      return response as Map<String, dynamic>?;
    } catch (e) {
      debugPrint("❌ LỖI createReviewReply: $e");
      rethrow;
    }
  }

  // 9. Get Review Reply
  Future<Map<String, dynamic>?> getReviewReply(String reviewUid) async {
    try {
      final response = await _apiClient.get('/api/reviews/$reviewUid/reply');
      return response as Map<String, dynamic>?;
    } catch (e) {
      debugPrint("❌ LỖI getReviewReply: $e");
      return null;
    }
  }

  // 10. Update Review Reply (Chỉ chef mới có quyền)
  Future<Map<String, dynamic>?> updateReviewReply({
    required String replyUid,
    required String content,
  }) async {
    try {
      final payload = {"content": content};
      final response = await _apiClient.patch('/api/reviews/reply/$replyUid', data: payload);
      return response as Map<String, dynamic>?;
    } catch (e) {
      debugPrint("❌ LỖI updateReviewReply: $e");
      rethrow;
    }
  }

  // 11. Delete Review Reply (Chỉ chef mới có quyền)
  Future<bool> deleteReviewReply(String replyUid) async {
    try {
      final response = await _apiClient.delete('/api/reviews/reply/$replyUid');
      return response != null;
    } catch (e) {
      debugPrint("❌ LỖI deleteReviewReply: $e");
      rethrow;
    }
  }

  // ===========================================================================
  // UTILITY METHODS
  // ===========================================================================

  // Lấy thông tin review kèm reply
  Future<Map<String, dynamic>?> getReviewWithReply(String reviewUid) async {
    try {
      final review = await getReviewByUid(reviewUid);
      if (review != null) {
        final reply = await getReviewReply(reviewUid);
        if (reply != null) {
          review['reply'] = reply;
        }
      }
      return review;
    } catch (e) {
      debugPrint("❌ LỖI getReviewWithReply: $e");
      return null;
    }
  }

}