import 'package:flutter/foundation.dart';

import '../models/user_model.dart';

class AuthSession extends ChangeNotifier {
  AuthSession._();

  static final AuthSession instance = AuthSession._();

  UserModel? _user;

  UserModel? get user => _user;
  bool get isAuthenticated => _user != null;
  bool get requiresOnboarding => _user != null && !_user!.isOnboarded;

  void setUser(UserModel? user) {
    _user = user;
    notifyListeners();
  }

  void markOnboardingComplete() {
    final current = _user;
    if (current == null) return;
    _user = current.copyWith(isOnboarded: true);
    notifyListeners();
  }

  void clear() {
    _user = null;
    notifyListeners();
  }
}
