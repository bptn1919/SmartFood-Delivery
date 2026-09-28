import 'package:flutter/material.dart';

import 'profile_onboarding_controller.dart';

class ProfileOnboardingScope
    extends InheritedNotifier<ProfileOnboardingController> {
  const ProfileOnboardingScope({
    super.key,
    required ProfileOnboardingController controller,
    required super.child,
  }) : super(notifier: controller);

  static ProfileOnboardingController of(BuildContext context) {
    final scope =
        context.dependOnInheritedWidgetOfExactType<ProfileOnboardingScope>();
    if (scope == null || scope.notifier == null) {
      throw StateError('ProfileOnboardingScope was not found in context.');
    }
    return scope.notifier!;
  }
}
