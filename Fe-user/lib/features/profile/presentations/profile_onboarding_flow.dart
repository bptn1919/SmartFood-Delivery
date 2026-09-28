// import 'package:flutter/material.dart';

// import '../controllers/profile_onboarding_controller.dart';
// import '../controllers/profile_onboarding_scope.dart';
// import 'profile_onboarding_physical_page.dart';
// import 'profile_onboarding_preferences_page.dart';

// class ProfileOnboardingFlow extends StatefulWidget {
//   const ProfileOnboardingFlow({super.key});

//   @override
//   State<ProfileOnboardingFlow> createState() => _ProfileOnboardingFlowState();
// }

// class _ProfileOnboardingFlowState extends State<ProfileOnboardingFlow> {
//   late final ProfileOnboardingController _controller;

//   @override
//   void initState() {
//     super.initState();
//     _controller = ProfileOnboardingController();
//   }

//   @override
//   void dispose() {
//     _controller.dispose();
//     super.dispose();
//   }

//   void _exitToHome() {
//     Navigator.of(context).pushNamedAndRemoveUntil('/home', (route) => false);
//   }

//   @override
//   Widget build(BuildContext context) {
//     return PopScope(
//       canPop: false,
//       child: ProfileOnboardingScope(
//         controller: _controller,
//         child: Navigator(
//           initialRoute: '/',
//           onGenerateRoute: (settings) {
//             Widget page;
//             switch (settings.name) {
//               case '/preferences':
//                 page = ProfileOnboardingPreferencesPage(
//                   onCompleted: _exitToHome,
//                 );
//                 break;
//               case '/':
//                 page = const ProfileOnboardingPhysicalPage();
//                 break;
//               default:
//                 page = _UnknownOnboardingRoutePage(routeName: settings.name);
//                 break;
//             }

//             return MaterialPageRoute(
//               builder: (_) => page,
//               settings: settings,
//             );
//           },
//         ),
//       ),
//     );
//   }
// }

// class _UnknownOnboardingRoutePage extends StatelessWidget {
//   const _UnknownOnboardingRoutePage({required this.routeName});

//   final String? routeName;

//   @override
//   Widget build(BuildContext context) {
//     return Scaffold(
//       backgroundColor: const Color(0xFFFFBB94),
//       body: SafeArea(
//         child: Center(
//           child: Padding(
//             padding: const EdgeInsets.all(24),
//             child: Card(
//               elevation: 0,
//               shape: RoundedRectangleBorder(
//                 borderRadius: BorderRadius.circular(24),
//               ),
//               child: Padding(
//                 padding: const EdgeInsets.all(24),
//                 child: Column(
//                   mainAxisSize: MainAxisSize.min,
//                   children: [
//                     const Icon(
//                       Icons.error_outline,
//                       color: Colors.redAccent,
//                       size: 42,
//                     ),
//                     const SizedBox(height: 12),
//                     const Text(
//                       'Unknown onboarding route',
//                       style: TextStyle(
//                         fontSize: 18,
//                         fontWeight: FontWeight.bold,
//                         color: Color(0xFF4A3225),
//                       ),
//                     ),
//                     const SizedBox(height: 8),
//                     Text(
//                       routeName ?? 'null',
//                       textAlign: TextAlign.center,
//                       style: const TextStyle(color: Colors.black54),
//                     ),
//                   ],
//                 ),
//               ),
//             ),
//           ),
//         ),
//       ),
//     );
//   }
// }
import 'package:flutter/material.dart';

import '../controllers/profile_onboarding_controller.dart';
import '../controllers/profile_onboarding_scope.dart';
import 'profile_onboarding_physical_page.dart';
import 'profile_onboarding_preferences_page.dart';

class ProfileOnboardingFlow extends StatefulWidget {
  const ProfileOnboardingFlow({super.key});

  @override
  State<ProfileOnboardingFlow> createState() => _ProfileOnboardingFlowState();
}

class _ProfileOnboardingFlowState extends State<ProfileOnboardingFlow> {
  late final ProfileOnboardingController _controller;

  @override
  void initState() {
    super.initState();
    _controller = ProfileOnboardingController();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _exitToHome() {
    // FIX TẠI ĐÂY: Thêm rootNavigator: true để đập vỡ lồng định tuyến
    Navigator.of(context, rootNavigator: true).pushNamedAndRemoveUntil('/home', (route) => false);
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      child: ProfileOnboardingScope(
        controller: _controller,
        child: Navigator(
          initialRoute: '/',
          onGenerateRoute: (settings) {
            Widget page;
            switch (settings.name) {
              case '/preferences':
                page = ProfileOnboardingPreferencesPage(
                  onCompleted: _exitToHome,
                );
                break;
              case '/':
                page = const ProfileOnboardingPhysicalPage();
                break;
              default:
                page = _UnknownOnboardingRoutePage(routeName: settings.name);
                break;
            }

            return MaterialPageRoute(
              builder: (_) => page,
              settings: settings,
            );
          },
        ),
      ),
    );
  }
}

class _UnknownOnboardingRoutePage extends StatelessWidget {
  const _UnknownOnboardingRoutePage({required this.routeName});

  final String? routeName;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFFFFBB94),
      body: SafeArea(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Card(
              elevation: 0,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(24),
              ),
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(
                      Icons.error_outline,
                      color: Colors.redAccent,
                      size: 42,
                    ),
                    const SizedBox(height: 12),
                    const Text(
                      'Unknown onboarding route',
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.bold,
                        color: Color(0xFF4A3225),
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      routeName ?? 'null',
                      textAlign: TextAlign.center,
                      style: const TextStyle(color: Colors.black54),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}