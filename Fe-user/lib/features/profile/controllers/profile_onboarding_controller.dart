import 'package:flutter/material.dart';

import '../../auth/repositories/auth_repository.dart';
import '../../auth/state/auth_session.dart';
import '../models/profile_onboarding_draft.dart';
import '../repository/profile_onboarding_repository.dart';

class ProfileOnboardingController extends ChangeNotifier {
  final ProfileOnboardingRepository _repository;
  final AuthRepository _authRepository;
  final AuthSession _authSession;

  ProfileOnboardingController({
    ProfileOnboardingRepository? repository,
    AuthRepository? authRepository,
    AuthSession? authSession,
  })  : _repository = repository ?? ProfileOnboardingRepository(),
        _authRepository = authRepository ?? AuthRepository(),
        _authSession = authSession ?? AuthSession.instance;

  ProfileOnboardingDraft _draft = const ProfileOnboardingDraft();
  bool _submitting = false;
  String? _error;

  ProfileOnboardingDraft get draft => _draft;
  bool get submitting => _submitting;
  String? get error => _error;

  void setPhysicalAttributes({
    required double heightCm,
    required double weightKg,
  }) {
    _draft = _draft.copyWith(heightCm: heightCm, weightKg: weightKg);
    _error = null;
    notifyListeners();
  }

  void toggleAllergy(String uid) {
    final values = List<String>.from(_draft.allergicIngredientUids);
    values.contains(uid) ? values.remove(uid) : values.add(uid);
    _draft = _draft.copyWith(allergicIngredientUids: values);
    notifyListeners();
  }

  void toggleFavoriteIngredient(String uid) {
    final values = List<String>.from(_draft.favoriteIngredientUids);
    values.contains(uid) ? values.remove(uid) : values.add(uid);
    _draft = _draft.copyWith(favoriteIngredientUids: values);
    notifyListeners();
  }

  Future<void> completeOnboarding() async {
    if (_draft.heightCm == null || _draft.weightKg == null) {
      throw StateError('Physical attributes must be completed first.');
    }

    _submitting = true;
    _error = null;
    notifyListeners();

    try {
      await _repository.completeOnboarding(_draft);

      final currentUser =
          _authSession.user ?? await _authRepository.getUserFromCache();
      if (currentUser == null) {
        throw StateError('Authenticated user was not found after onboarding.');
      }

      final updatedUser = currentUser.copyWith(isOnboarded: true);
      await _authRepository.saveUserToCache(updatedUser);
      _authSession.setUser(updatedUser);
    } catch (e) {
      _error = e.toString();
      rethrow;
    } finally {
      _submitting = false;
      notifyListeners();
    }
  }
}
