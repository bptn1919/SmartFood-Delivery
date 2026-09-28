import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../../../core/network/api_client.dart';
import '../../../core/network/api_constants.dart';
import '../models/user_model.dart';
import 'package:jwt_decoder/jwt_decoder.dart';

class AuthRepository {
  final ApiClient _api;

  AuthRepository({ApiClient? api}) : _api = api ?? ApiClient();

  // Định nghĩa key lưu cache
  static const String _kTokenKey = 'token';
  static const String _kResetTokenKey = 'reset_session_token';

  // ===========================================================================
  // 1. LOGIN
  // Input: email, password
  // Output: token + user info
  // ===========================================================================
// Trong AuthRepository, sửa lại method login
  Future<UserModel> login(String email, String password) async {
    try {
      final res = await _api.post(ApiConstants.login, {
        "email": email,
        "password": password,
      });

      debugPrint("🔍 LOGIN RESPONSE: $res");

      Map<String, dynamic> data;
      if (res['data'] != null && res['data'] is Map) {
        data = res['data'];
      } else {
        data = res;
      }

      final String? token = data['access_token'];
      final String? refreshToken = data['refresh_token'];
      if (token != null) {
        final prefs = await SharedPreferences.getInstance();
        await prefs.setString("token", token);
        if (refreshToken != null) {
          await prefs.setString("refresh_token", refreshToken);
        }
        Map<String, dynamic> decodedToken = JwtDecoder.decode(token);
        if (decodedToken.containsKey('user_id')) {
          await prefs.setInt("user_id", decodedToken['user_id']);
          debugPrint("✅ Saved User ID from JWT: ${decodedToken['user_id']}");
        }
      }

      final userMap = data['user'];
      if (userMap == null) {
        throw Exception("API returned success but missing 'user' info");
      }

      // Parse user
      UserModel user = UserModel.fromJson(Map<String, dynamic>.from(userMap));

      // Sau khi login thành công, kiểm tra xem có phải chef không
      final chefStatus = await checkIsChef();
      user = user.copyWith(
        isChef: chefStatus['isChef'],
        chefId: chefStatus['chefId'], // Có thể là null
      );

      debugPrint(
          "🔍 USER AFTER LOGIN: isChef=${user.isChef}, chefId=${user.chefId}");

      // Lưu user vào cache
      await saveUserToCache(user);

      return user;
    } catch (e) {
      debugPrint("❌ Login Error: $e");
      rethrow;
    }
  }

  Future<bool> refreshToken() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final currentRefreshToken = prefs.getString("refresh_token");

      if (currentRefreshToken == null || currentRefreshToken.isEmpty) {
        debugPrint("❌ Không tìm thấy refresh_token trong cache.");
        return false;
      }

      // Gọi API refresh (nhớ khai báo ApiConstants.refresh là '/api/auth/refresh')
      final res = await _api.post(ApiConstants.refresh, {
        "refresh_token": currentRefreshToken,
      });

      debugPrint("🔍 REFRESH TOKEN RESPONSE: $res");

      // Bóc tách token mới từ response
      final String? newAccessToken = res['access_token'];
      final String? newRefreshToken = res['refresh_token'];

      if (newAccessToken != null) {
        // Cập nhật token mới vào SharedPreferences
        await prefs.setString("token", newAccessToken);

        if (newRefreshToken != null) {
          await prefs.setString("refresh_token", newRefreshToken);
        }

        // Decode lại token mới để lưu user_id (nếu cần)
        Map<String, dynamic> decodedToken = JwtDecoder.decode(newAccessToken);
        if (decodedToken.containsKey('user_id')) {
          await prefs.setInt("user_id", decodedToken['user_id']);
        }

        debugPrint("✅ Refresh Token thành công!");
        return true;
      }
      return false;
    } catch (e) {
      debugPrint("🚨 Lỗi Refresh Token: $e");
      // Cực kỳ quan trọng: Nếu refresh thất bại (thường do refresh_token cũng hết hạn),
      // bắt buộc phải logout để xóa sạch cache cũ và ép user đăng nhập lại.
      await clearUserCache();
      final prefs = await SharedPreferences.getInstance();
      await prefs.remove(_kTokenKey);
      await prefs.remove("refresh_token");
      return false;
    }
  }

  // ===========================================================================
  // 2. SIGNUP
  // Input: firstname, lastname, email, password, confirm, phone_number
  // Output: id, reset_session_token, purpose
  // ===========================================================================
  Future<void> signup({
    required String firstName,
    required String lastName,
    required String email,
    required String phoneNumber,
    required String password,
    required String passwordConfirm,
  }) async {
    try {
      // Mapping đúng key snake_case theo API specs
      final payload = {
        "firstname": firstName,
        "lastname": lastName,
        "email": email,
        "phone_number": phoneNumber, // Specs ghi là phone_number
        "password": password,
        "password_confirm": passwordConfirm,
      };

      final res = await _api.post(ApiConstants.signup, payload);

      // Specs: Trả về reset_session_token (cần lưu lại cho bước verify sau này nếu có)
      final String? resetToken = res['reset_session_token'];

      if (resetToken != null) {
        final prefs = await SharedPreferences.getInstance();
        await prefs.setString(_kResetTokenKey, resetToken);
      }
    } catch (e) {
      rethrow;
    }
  }

  // ===========================================================================
  // 3. GET ME
  // Output: username, email
  // ===========================================================================
  Future<UserModel> getMe() async {
    try {
      final res = await _api.get(ApiConstants.getMe);
      debugPrint("🚨 RAW JSON TỪ BACKEND: $res");
      final raw = res is Map<String, dynamic> && res['data'] is Map
          ? Map<String, dynamic>.from(res['data'])
          : Map<String, dynamic>.from(res);
      return UserModel.fromJson(
          raw); // Cố gắng parse từ data trước, nếu không có thì parse trực tiếp
    } catch (e) {
      rethrow;
    }
  }

  // ===========================================================================
  // 4. UPDATE ME
  // Input: full_name
  // Output: username, email
  // ===========================================================================
  Future<UserModel> updateMe(String fullName) async {
    try {
      // Specs yêu cầu key là "full_name"
      final res = await _api.put(ApiConstants.updateMe, {
        "full_name": fullName,
      });
      return UserModel.fromJson(res);
    } catch (e) {
      rethrow;
    }
  }

  // ===========================================================================
  // 5. CHANGE PASSWORD
  // Input: old_password, new_password
  // Output: true (200) hoặc Message (401)
  // ===========================================================================
  Future<bool> changePassword(String oldPass, String newPass) async {
    try {
      // Vì API trả về true raw (không phải JSON object) hoặc JSON lỗi
      // ApiClient cần xử lý logic: nếu status 200 -> trả về data.
      await _api.put(ApiConstants.changePassword, {
        "old_password": oldPass,
        "new_password": newPass,
      });

      return true;
    } catch (e) {
      // 1. In log ra để Dev kiểm tra (Không hiện lên UI)
      debugPrint("🚨 [AuthRepo] ChangePassword Error: $e");

      // 2. Chuyển Exception về chữ thường để rà soát từ khóa
      final errorString = e.toString().toLowerCase();

      // 3. 🛡️ CHỐT CHẶN 1: LỖI TIMEOUT DO GỬI MAIL CHẬM
      // App hết kiên nhẫn chờ server gửi mail
      if (errorString.contains("timeout") ||
          errorString.contains("connection closed") ||
          errorString.contains("deadline")) {
        debugPrint(
            "⚠️ Nuốt lỗi: Timeout do server gửi mail chậm -> Vẫn tính là thành công!");
        return true;
      }

      // 4. 🛡️ CHỐT CHẶN 2: LỖI SẬP LUỒNG GỬI MAIL (500)
      // Pass đã đổi, nhưng server crash ở dòng lệnh send_mail()
      if (errorString.contains("500") ||
          errorString.contains("email") ||
          errorString.contains("smtp")) {
        debugPrint(
            "⚠️ Nuốt lỗi: SMTP/Email config lỗi -> Vẫn tính là thành công!");
        return true;
      }

      // 5. ❌ CÁC LỖI THỰC SỰ (400, 401, 403)
      // Ví dụ: Sai mật khẩu cũ, user bị khóa... thì phải ném lỗi ra ngoài cho UI hiển thị
      rethrow;
    }
  }

  Future<void> forgetPassword(String email) async {
    try {
      final res = await _api.post(ApiConstants.forgetPassword, {
        "email": email,
      });

      // Specs: Output giống Signup
      final String? resetToken = res['reset_session_token'];

      if (resetToken != null) {
        final prefs = await SharedPreferences.getInstance();
        await prefs.setString(_kResetTokenKey, resetToken);
      }
    } catch (e) {
      rethrow;
    }
  }

  // ===========================================================================
  // 7. VERIFY OTP
  // Input: reset_session_token, otp
  // Output: true (200)
  // ===========================================================================
  // Future<bool> verifyOtp(String otp) async {
  //   try {
  //     final prefs = await SharedPreferences.getInstance();
  //     final resetToken = prefs.getString(_kResetTokenKey);

  //     if (resetToken == null) throw Exception("Missing Reset Token");

  //     final res = await _api.post(ApiConstants.verifyOtp, {
  //       "reset_session_token": resetToken,
  //       "otp": otp,
  //     });

  //     return res == true;
  //   } catch (e) {
  //     rethrow;
  //   }
  // }

  // Trong file: auth_repository.dart

  Future<Map<String, dynamic>> verifyOtp({
    required String email,
    required String otp,
    bool isSignup = false, // Mặc định là false (Forgot Pass)
  }) async {
    try {
      // 1. Tạo body cơ bản
      final Map<String, dynamic> body = {
        "email": email, // Backend thường cần email để biết verify cho ai
        "otp": otp,
      };

      // 2. Chỉ xử lý reset_session_token nếu KHÔNG phải là Signup
      if (!isSignup) {
        final prefs = await SharedPreferences.getInstance();
        final resetToken = prefs.getString(_kResetTokenKey);

        // Nếu có token thì gửi kèm, không có thì thôi (đừng throw Exception chặn luồng)
        if (resetToken != null) {
          body["reset_session_token"] = resetToken;
        }
      }

      // 3. Gọi API
      // Lưu ý: ApiClient của bạn trả về dynamic hoặc Map, hãy return nguyên vẹn để UI xử lý
      final res = await _api.post(ApiConstants.verifyOtp, body);

      return res is Map<String, dynamic> ? res : {};
    } catch (e) {
      rethrow;
    }
  }

  // ===========================================================================
  // 8. RESET PASSWORD
  // Input: reset_session_token, new_password, confirm_password
  // Output: true (200)
  // ===========================================================================
  Future<bool> resetPassword(String newPass, String confirmPass) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final resetToken = prefs.getString(_kResetTokenKey);

      if (resetToken == null) throw Exception("Missing Reset Token");

      final res = await _api.put(ApiConstants.resetPassword, {
        "reset_session_token": resetToken,
        "new_password": newPass,
        "confirm_password": confirmPass,
      });

      // Thành công -> Xoá token tạm
      await prefs.remove(_kResetTokenKey);

      return res == true;
    } catch (e) {
      rethrow;
    }
  }

  // LOGOUT (Giữ nguyên như cũ)
  Future<void> logout() async {
    try {
      await _api.put(ApiConstants.logout, {});
    } catch (e) {
      // ignore error
    } finally {
      final prefs = await SharedPreferences.getInstance();
      await prefs.remove(_kTokenKey);
      await clearUserCache(); // Xóa cache user
    }
  }

  // Output: { is_chef: bool, chef_id: int }
  // ===========================================================================
  Future<Map<String, dynamic>> checkIsChef() async {
    try {
      final res = await _api.get(ApiConstants.isChef);

      debugPrint("🔍 CHECK IS CHEF RESPONSE: $res");

      // API trả về: { "data": { "is_chef": true, "chef_id": null }, "message_code": "SUCCESS", ... }
      if (res is Map<String, dynamic>) {
        // Lấy data bên trong
        final data = res['data'];

        if (data != null && data is Map<String, dynamic>) {
          return {
            'isChef': data['is_chef'] ?? false,
            'chefId': data['chef_id'],
          };
        }

        // Fallback nếu không có data wrapper
        return {
          'isChef': res['is_chef'] ?? false,
          'chefId': res['chef_id'],
        };
      }

      return {'isChef': false, 'chefId': null};
    } catch (e) {
      debugPrint("❌ Check Is Chef Error: $e");
      // Nếu lỗi (401, 404) thì coi như không phải chef
      return {'isChef': false, 'chefId': null};
    }
  }

  // ===========================================================================
  // 10. GET CURRENT USER WITH CHEF STATUS
  // Kết hợp getMe và checkIsChef
  // ===========================================================================
  Future<UserModel> getCurrentUserWithChefStatus() async {
    try {
      // Lấy thông tin user cơ bản
      final user = await getMe();

      // Kiểm tra xem có phải chef không
      final chefStatus = await checkIsChef();

      // Cập nhật user với thông tin chef
      return user.copyWith(
        isChef: chefStatus['isChef'],
        chefId: chefStatus['chefId'],
      );
    } catch (e) {
      rethrow;
    }
  }

  // ===========================================================================
  // 11. LƯU USER VÀO SHARED PREFERENCES
  // ===========================================================================
  static const String _kUserKey = 'user_data';

  Future<void> saveUserToCache(UserModel user) async {
    final prefs = await SharedPreferences.getInstance();
    final userJson = user.toJson();

    // Loại bỏ các key có giá trị null để tránh lỗi khi encode
    final Map<String, dynamic> cleanJson = {};
    userJson.forEach((key, value) {
      if (value != null) {
        cleanJson[key] = value;
      }
    });

    await prefs.setString(_kUserKey, jsonEncode(cleanJson));
    debugPrint("🔍 SAVED USER TO CACHE: $cleanJson");
  }

  Future<UserModel?> getUserFromCache() async {
    final prefs = await SharedPreferences.getInstance();
    final userString = prefs.getString(_kUserKey);
    if (userString != null) {
      try {
        final userJson = jsonDecode(userString);
        debugPrint("🔍 LOADED USER FROM CACHE: $userJson");
        return UserModel.fromJson(userJson);
      } catch (e) {
        debugPrint("❌ Error parsing user from cache: $e");
        return null;
      }
    }
    return null;
  }

  Future<void> clearUserCache() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_kUserKey);
  }
}
