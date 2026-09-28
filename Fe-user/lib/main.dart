import 'package:flutter/material.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:testing/features/chat/service/notification_service.dart';

// Import your generated Firebase options file
// import 'firebase_options.dart';

// Import Authentication Pages
import 'features/auth/presentations/login.dart';
import 'features/auth/presentations/sign_up.dart';
import 'features/auth/presentations/reset_pass.dart';
import 'features/auth/presentations/verify_mail.dart';
import 'features/auth/presentations/verify_otp.dart';
import 'features/auth/presentations/verify_otp_su.dart';
import 'features/auth/presentations/index.dart';
import 'features/profile/presentations/profile_onboarding_flow.dart';

// Import Main Features Pages
import 'features/home/presentations/home.dart';
import 'features/home/presentations/detailchef.dart';

import 'firebase_options.dart';

// Import your Chat Detail Page (Adjust path as needed)
// import 'features/chat/presentations/chat_detail_page.dart';

// 1. GLOBAL NAVIGATOR KEY: Essential for redirecting from background notifications
final GlobalKey<NavigatorState> navigatorKey = GlobalKey<NavigatorState>();

// 2. BACKGROUND NOTIFICATION HANDLER: Must be a top-level function
@pragma('vm:entry-point')
Future<void> _firebaseMessagingBackgroundHandler(RemoteMessage message) async {
  // Initialize Firebase if app was completely terminated
  // await Firebase.initializeApp(options: DefaultFirebaseOptions.currentPlatform);
  //await Firebase.initializeApp();
  await Firebase.initializeApp(
    options: DefaultFirebaseOptions.currentPlatform,
  );
  debugPrint("🔔 Background Message Received: ${message.messageId}");
}

//

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  //await Firebase.initializeApp();
  await Firebase.initializeApp(
    options: DefaultFirebaseOptions.currentPlatform,
  );
  FirebaseMessaging.onBackgroundMessage(_firebaseMessagingBackgroundHandler);

  // 👇 CHỈ KHỞI TẠO LẮNG NGHE, KHÔNG YÊU CẦU TOKEN
  await NotificationService().initListeners();

  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'AmoMeal',
      // 👇 ATTACH NAVIGATOR KEY HERE 👇
      navigatorKey: navigatorKey,
      theme: ThemeData(
        primarySwatch: Colors.blue,
        scaffoldBackgroundColor: const Color(0xFFFFBB94),
      ),

      initialRoute: '/',

      // Static Routes
      routes: {
        '/': (context) => const IndexPage(),
        '/login': (context) => const LoginPage(),
        '/signup': (context) => const SignupPage(),
        '/reset': (context) => const ResetPassPage(),
        '/verify-mail': (context) => const VerifyMailPage(),
        '/home': (context) => const HomePage(),
        '/profile-onboarding': (context) => const ProfileOnboardingFlow(),
      },

      // Dynamic Routes with Arguments
      onGenerateRoute: (settings) {
        // --- OTP Routes ---
        if (settings.name == '/verify-otp') {
          final email = settings.arguments as String;
          return MaterialPageRoute(builder: (_) => VerifyOtpPage(email: email));
        }

        if (settings.name == '/verify-otp-su') {
          final args = settings.arguments;
          if (args is String) {
            return MaterialPageRoute(
                builder: (_) => VerifyOtpForSuPage(email: args));
          }
          return _errorRoute("Missing Email argument");
        }

        // --- Chef Detail Route ---
        if (settings.name == '/detail-chef') {
          final args = settings.arguments;
          if (args is String) {
            return MaterialPageRoute(
                builder: (_) => DetailChefPage(chefId: args));
          }
          return _errorRoute("Missing Chef ID");
        }

        // --- Chat Detail Route (Called by NotificationService) ---
        if (settings.name == '/chat-detail') {
          final args = settings.arguments;
          if (args is Map<String, dynamic>) {
            // Example arguments payload: {'room_id': '123', 'token': 'jwt_token'}
            return MaterialPageRoute(
                builder: (_) => Scaffold(
                      appBar: AppBar(title: const Text('Chat Room')),
                      body: Center(
                          child: Text('Chat Room ID: ${args['room_id']}')),
                    )
                // Replace the Scaffold above with your actual ChatDetailPage:
                // builder: (_) => ChatDetailPage(roomId: args['room_id'], token: args['token']),
                );
          }
          return _errorRoute("Missing Chat Room Configuration");
        }

        return null;
      },
    );
  }

  // Error Route Fallback
  MaterialPageRoute _errorRoute(String message) {
    return MaterialPageRoute(
      builder: (_) => Scaffold(
        appBar: AppBar(title: const Text("Navigation Error")),
        body: Center(child: Text(message)),
      ),
    );
  }
}
