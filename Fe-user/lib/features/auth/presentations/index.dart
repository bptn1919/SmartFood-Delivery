import 'package:flutter/material.dart';

import '../models/user_model.dart';
import '../repositories/auth_repository.dart';
import '../state/auth_session.dart';

class IndexPage extends StatefulWidget {
  const IndexPage({super.key});

  @override
  State<IndexPage> createState() => _IndexPageState();
}

class _IndexPageState extends State<IndexPage> {
  final AuthRepository _authRepository = AuthRepository();
  bool _resolving = false;

  @override
  void initState() {
    super.initState();
    Future.microtask(_resolveInitialRoute);
  }

  String _routeForUser(UserModel? user) {
    if (user == null) return '/login';
    return user.isOnboarded ? '/home' : '/profile-onboarding';
  }

  Future<void> _resolveInitialRoute() async {
    if (_resolving) return;
    _resolving = true;

    try {
      final cachedUser = await _authRepository.getUserFromCache();
      final cachedRoute = _routeForUser(cachedUser);
      if (cachedUser == null) {
        AuthSession.instance.clear();
        if (!mounted) return;
        Navigator.pushReplacementNamed(context, cachedRoute);
        return;
      }

      AuthSession.instance.setUser(cachedUser);

      final freshUser = await _authRepository.getCurrentUserWithChefStatus();
      await _authRepository.saveUserToCache(freshUser);
      AuthSession.instance.setUser(freshUser);

      if (!mounted) return;
      Navigator.pushReplacementNamed(context, _routeForUser(freshUser));
    } catch (e) {
      AuthSession.instance.clear();
      await _authRepository.clearUserCache();
      if (!mounted) return;
      Navigator.pushReplacementNamed(context, _routeForUser(null));
    } finally {
      _resolving = false;
    }
  }

  void _handlePress() {
    _resolveInitialRoute();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: GestureDetector(
        onTap: _handlePress,
        child: Container(
          width: double.infinity,
          decoration: const BoxDecoration(
            image: DecorationImage(
              image: AssetImage('assets/images/launch_background.jpg'),
              fit: BoxFit.cover,
            ),
          ),
          // 👇 CẢI TIẾN 1: Phủ gradient mờ để chữ luôn nổi bật
          child: Container(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: [
                  Colors.black
                      .withValues(alpha: 0.1), // Trên cùng hơi trong suốt
                  Colors.black.withValues(alpha: 0.3), // Giữa mờ nhẹ
                  Colors.black.withValues(
                      alpha: 0.8), // Dưới cùng tối để tôn Slogan lên
                ],
              ),
            ),
            child: SafeArea(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  // 👇 CẢI TIẾN 2: Hiệu ứng trượt và mờ dần cho Title
                  Padding(
                    padding: const EdgeInsets.only(top: 80),
                    child: TweenAnimationBuilder(
                      tween: Tween<double>(begin: 0, end: 1),
                      duration: const Duration(milliseconds: 1200),
                      curve: Curves.easeOutCubic,
                      builder: (context, double value, child) {
                        return Opacity(
                          opacity: value,
                          child: Transform.translate(
                            offset: Offset(0,
                                20 * (1 - value)), // Trượt nhẹ từ dưới lên 20px
                            child: child,
                          ),
                        );
                      },
                      child: const Text(
                        "AmoMeal",
                        style: TextStyle(
                          fontSize: 42, // Chữ to hơn chút cho bề thế
                          fontWeight: FontWeight.w900,
                          color: Colors.white,
                          letterSpacing:
                              1.5, // Giãn chữ ra một chút nhìn sang hơn
                        ),
                      ),
                    ),
                  ),

                  // 👇 CẢI TIẾN 3: Slogan mới & Animation
                  Padding(
                    padding: const EdgeInsets.only(bottom: 40),
                    child: TweenAnimationBuilder(
                      tween: Tween<double>(begin: 0, end: 1),
                      duration: const Duration(milliseconds: 1500),
                      curve: Curves.easeOutCubic,
                      builder: (context, double value, child) {
                        return Opacity(
                          opacity: value,
                          child: child,
                        );
                      },
                      child: Column(
                        children: [
                          const Text(
                            "From local kitchens to your table", // Slogan bắt tai hơn
                            style: TextStyle(
                              fontSize: 18,
                              fontWeight: FontWeight.w600,
                              color: Colors.white,
                            ),
                          ),
                          const SizedBox(height: 8),
                          Text(
                            "Connecting you with passionate local chefs",
                            style: TextStyle(
                              fontSize: 13,
                              color: Colors.white.withValues(alpha: 0.7),
                              letterSpacing: 0.5,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
