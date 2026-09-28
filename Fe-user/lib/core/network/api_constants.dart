import 'package:flutter/foundation.dart';

class ApiConstants {
  //static const String baseUrl = "http://10.0.2.2:8000"; // backend chạy local

  static String get baseUrl {
    if (kIsWeb) {
      return 'http://127.0.0.1:8000';
    } else {
      //return 'http://10.0.2.2:8000';
      //return 'http://127.0.0.1:8000';
      return 'http://app-alb-production-1252640903.us-east-1.elb.amazonaws.com';
    }
  }

  // Auth
  static const String login = "/api/auth/login";
  static const String refresh = '/api/auth/refresh';
  static const String signup = "/api/auth/signup";
  static const String getMe = "/api/auth/me";
  static const String updateMe = "/api/auth/me";
  static const String logout = "/api/auth/logout";
  static const String changePassword = "/api/auth/password/change";
  static const String forgetPassword = "/api/auth/password/forget";
  static const String verifyOtp = "/api/auth/verify-otp";
  static const String resetPassword = "/api/auth/password/reset";
  static const String isChef = '/api/auth/is-chef';
  static const String customerProfileOnboard = '/api/customer-profiles/onboard';

  //Cart
  static const String getCart = "/api/carts/";

  // Chef
  static const String getAllChefProfiles = "/api/chef-profiles/";

  // Dish
  static const String getAllDishes = '/api/dishes/';
  static const String getDish = '/api/dishes/';
  static const String dishesByMenuQuery = '/api/dishes/?menu=';

  // Menu
  static const String getAllMenus = '/api/menus/';
  static const String getMenusOfChef = '/api/menus/';
  static const String getMenu = '/api/menus/';
  static const String addDishToMenu = '/api/menus/';

  static const String s3BaseUrl =
      'https://amomeal-bucket.s3.ap-southeast-1.amazonaws.com';
}
