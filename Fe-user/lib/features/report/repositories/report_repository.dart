import '../../../core/network/api_client.dart';

class ReportRepository {
  final ApiClient _apiClient;

  ReportRepository({ApiClient? apiClient})
      : _apiClient = apiClient ?? ApiClient();

  Future<void> createReport({
    String? orderUid,
    int? chefId,
    String? dishUid,
    required String category,
    required String description,
    String? evidenceUid,
  }) async {
    await _apiClient.post('/api/report', {
      'order_uid': orderUid,
      'chef_id': chefId,
      'dish_uid': dishUid,
      'category': category,
      'description': description,
      'evidence_uid': evidenceUid,
    });
  }
}
