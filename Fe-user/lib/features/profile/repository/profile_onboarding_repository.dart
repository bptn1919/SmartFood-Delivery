import '../../../core/network/api_client.dart';
import '../../../core/network/api_constants.dart';
import '../../auth/models/user_model.dart';
import '../models/profile_onboarding_draft.dart';

class ProfileOnboardingRepository {
  final ApiClient _api;

  ProfileOnboardingRepository({ApiClient? api}) : _api = api ?? ApiClient();

  Future<UserModel> completeOnboarding(ProfileOnboardingDraft draft) async {
    final response = await _api.post(
      ApiConstants.customerProfileOnboard,
      draft.toPayloadJson(),
    );

    final raw = response is Map<String, dynamic> && response['data'] is Map
        ? Map<String, dynamic>.from(response['data'])
        : Map<String, dynamic>.from(response as Map);

    return UserModel.fromJson(raw).copyWith(isOnboarded: true);
  }
}
