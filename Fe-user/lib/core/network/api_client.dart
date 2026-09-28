// import 'dart:convert';
// //import 'package:flutter/material.dart';
// import 'package:http/http.dart' as http;
// import 'package:shared_preferences/shared_preferences.dart'; // 1. Import này
// import './api_constants.dart';

// class ApiClient {
//   final http.Client _client = http.Client();

//   // ===========================================================================
//   // 1. HELPER LẤY HEADERS (TỰ ĐỘNG KÈM TOKEN)
//   // ===========================================================================
//   Future<Map<String, String>> _getHeaders(Map<String, String>? extraHeaders) async {
//     // Lấy token từ bộ nhớ máy
//     final prefs = await SharedPreferences.getInstance();
//     final token = prefs.getString('token'); // Đảm bảo key 'token' khớp với lúc Login

//     Map<String, String> headers = {
//       "Content-Type": "application/json",
//       ...?extraHeaders,
//     };

   
//     if (token != null && token.isNotEmpty) {
//       headers["Authorization"] = "Bearer $token";
//     }

//     return headers;
//   }


//   Future<dynamic> post(
//     String endpoint, 
//     Map<String, dynamic> body, 
//     {
//       Map<String, String>? headers,
//       Map<String, dynamic>? queryParameters, 
//     }
//   ) async {
//     final requestHeaders = await _getHeaders(headers);

   
//     Uri uri = Uri.parse("${ApiConstants.baseUrl}$endpoint");

    
//     if (queryParameters != null && queryParameters.isNotEmpty) {
      
//       final stringParams = queryParameters.map((key, value) => 
//           MapEntry(key, value.toString()));
      
//       uri = uri.replace(queryParameters: stringParams);
//     }


//     final response = await _client.post(
//       uri,
//       headers: requestHeaders,
//       body: jsonEncode(body),
//     );

//     return _handleResponse(response);
//   }
  

//   // PUT
//   Future<dynamic> put(
//     String endpoint, 
//     Map<String, dynamic> body, 
//     {Map<String, String>? headers}
//   ) async {
//     final requestHeaders = await _getHeaders(headers);

//     final response = await _client.put(
//       Uri.parse("${ApiConstants.baseUrl}$endpoint"),
//       headers: requestHeaders,
//       body: jsonEncode(body),
//     );

//     return _handleResponse(response);
//   }

//   // GET
//   Future<dynamic> get(
//     String endpoint, 
//     {
//     Map<String, dynamic>? queryParameters, 
//     Map<String, String>? headers,
//   }
//   ) async {
//     final requestHeaders = await _getHeaders(headers);

//     Uri uri = Uri.parse("${ApiConstants.baseUrl}$endpoint");


//     if (queryParameters != null && queryParameters.isNotEmpty) {
      
//       final stringParams = queryParameters.map((key, value) => MapEntry(key, value.toString()));
      
//       uri = uri.replace(queryParameters: stringParams);
//     }

    
//     final response = await _client.get(
//       uri, // 👈 Truyền URI đã có params vào đây
//       headers: requestHeaders,
//     );

//     return _handleResponse(response);
//   }

//   // DELETE
//   Future<dynamic> delete(
//     String endpoint, {
//     Map<String, dynamic>? body,
//     Map<String, String>? headers,
//   }) async {
//     final requestHeaders = await _getHeaders(headers);

//     final response = await _client.delete(
//       Uri.parse("${ApiConstants.baseUrl}$endpoint"),
//       headers: requestHeaders,
//       body: body != null ? jsonEncode(body) : null,
//     );

//     return _handleResponse(response);
//   }

//   // ===========================================================================
//   // 3. XỬ LÝ RESPONSE CHUNG
//   // ===========================================================================
//   dynamic _handleResponse(http.Response response) {
//     // Debug Log (Có thể comment lại khi release)
//     // print('API Response [${response.statusCode}] Url: ${response.request?.url}');
    
//     if (response.statusCode >= 200 && response.statusCode < 300) {
//       // Trường hợp body rỗng (ví dụ 204 No Content)
//       if (response.body.isEmpty) return {};
      
//       try {
//         // Cố gắng parse JSON
//         return jsonDecode(response.body);
//       } catch (e) {
//         // Nếu Backend trả về Plain Text thay vì JSON, trả thẳng Text ra
//         return response.body; 
//       }
//     } 
//     else if (response.statusCode == 401) {
//       throw Exception("Unauthorized: Token expired or invalid.");
//     } 
//     else {
//       throw Exception(
//         "API Error [${response.statusCode}]: ${response.body}",
//       );
//     }
//   }


//   Future<dynamic> patch(
//     String endpoint, {
//     Map<String, dynamic>? data,      // Named param để khớp với repo: data: {...}
//     Map<String, dynamic>? queryParameters, // Để khớp với repo: queryParameters: {...}
//     Map<String, String>? headers,
//   }) async {
//     final requestHeaders = await _getHeaders(headers);

//     // 1. Xây dựng URI và merge Query Parameters (nếu có)
//     Uri uri = Uri.parse("${ApiConstants.baseUrl}$endpoint");
//     if (queryParameters != null && queryParameters.isNotEmpty) {
//       // Uri.replace yêu cầu Map<String, dynamic> (thường là String hoặc List<String>)
//       // Ta convert value sang String để an toàn
//       final stringParams = queryParameters.map((key, value) => MapEntry(key, value.toString()));
      
//       // Merge với params có sẵn trong endpoint (nếu có)
//       final newParams = Map<String, dynamic>.from(uri.queryParameters)..addAll(stringParams);
      
//       uri = uri.replace(queryParameters: newParams);
//     }

//     // 2. Thực hiện Request
//     final response = await _client.patch(
//       uri,
//       headers: requestHeaders,
//       body: data != null ? jsonEncode(data) : null,
//     );

//     return _handleResponse(response);
//   }
// }


import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import './api_constants.dart';

enum HttpMethod { get, post, put, patch, delete }

class ApiClient {
  final http.Client _client = http.Client();

  bool _isRefreshing = false;
  Completer<bool>? _refreshTokenCompleter;

  // ===========================================================================
  // 1. HELPER LẤY HEADERS (TỰ ĐỘNG KÈM TOKEN)
  // ===========================================================================
  Future<Map<String, String>> _getHeaders(Map<String, String>? extraHeaders) async {
    final prefs = await SharedPreferences.getInstance();
    final token = prefs.getString('token');

    Map<String, String> headers = {
      "Content-Type": "application/json",
      ...?extraHeaders,
    };

    if (token != null && token.isNotEmpty) {
      headers["Authorization"] = "Bearer $token";
    }

    return headers;
  }

  // ===========================================================================
  // 2. CORE REQUEST HANDLER (TÍCH HỢP TỰ ĐỘNG REFRESH TOKEN)
  // ===========================================================================
  Future<dynamic> _sendRequest(
    HttpMethod method,
    String endpoint, {
    Map<String, dynamic>? body,
    Map<String, dynamic>? queryParameters,
    Map<String, String>? headers,
  }) async {
    Uri uri = Uri.parse("${ApiConstants.baseUrl}$endpoint");

    // Xử lý Query Parameters
    if (queryParameters != null && queryParameters.isNotEmpty) {
      final stringParams = queryParameters.map((key, value) => MapEntry(key, value.toString()));
      final newParams = Map<String, dynamic>.from(uri.queryParameters)..addAll(stringParams);
      uri = uri.replace(queryParameters: newParams);
    }

    // Lấy Headers (chứa Access Token hiện tại)
    Map<String, String> requestHeaders = await _getHeaders(headers);

    // Bắn Request lần 1
    http.Response response = await _executeHttp(method, uri, requestHeaders, body);

    // 🚨 NẾU TOKEN HẾT HẠN (401)
    if (response.statusCode == 401) {
      
      // ==========================================
      // CƠ CHẾ KHÓA & HÀNG ĐỢI (LOCK & QUEUE)
      // ==========================================
      
      if (_isRefreshing) {
        // LUỒNG ĐẾN SAU: Nếu đã có ông khác đang đi lấy token rồi -> Đứng chờ!
        debugPrint("⏳ Đang có luồng refresh token chạy, đưa request [$endpoint] vào hàng chờ...");
        
        // Luồng này sẽ bị "đóng băng" ở đây cho đến khi Completer trả về kết quả
        bool success = await _refreshTokenCompleter!.future;
        
        if (success) {
          debugPrint("♻️ Chờ xong, token đã có. Thử gọi lại API [$endpoint]...");
          requestHeaders = await _getHeaders(headers); // Lấy token mới
          response = await _executeHttp(method, uri, requestHeaders, body);
          return _handleResponse(response);
        } else {
          // Ông đi lấy token báo thất bại, mình cũng văng lỗi luôn
          throw Exception("SessionExpired: Vui lòng đăng nhập lại.");
        }
      }

      // LUỒNG ĐI ĐẦU TIÊN: Chưa ai lấy token, mình sẽ xung phong đi lấy
      _isRefreshing = true;
      _refreshTokenCompleter = Completer<bool>(); // Tạo hộp chứa kết quả
      debugPrint("🔄 Token hết hạn (401), KHÓA LUỒNG và bắt đầu refresh token...");
      
      bool isRefreshed = false;
      try {
        isRefreshed = await _refreshToken();
      } finally {
        // Dù lấy token thành công hay lỗi, cũng phải MỞ KHÓA và BÁO CÁO cho anh em đang chờ
        _isRefreshing = false;
        _refreshTokenCompleter?.complete(isRefreshed);
      }
      
      if (isRefreshed) {
        debugPrint("✅ Tự động Refresh Token thành công, thử gọi lại API bị lỗi [$endpoint]...");
        requestHeaders = await _getHeaders(headers);
        response = await _executeHttp(method, uri, requestHeaders, body);
      } else {
        debugPrint("❌ Refresh Token thất bại. Bắt buộc user đăng nhập lại!");
        await _forceLogout();
        throw Exception("SessionExpired: Vui lòng đăng nhập lại."); 
      }
    }

    return _handleResponse(response);
  }

  // Helper thực thi các loại Method HTTP
  Future<http.Response> _executeHttp(
    HttpMethod method, 
    Uri uri, 
    Map<String, String> headers, 
    Map<String, dynamic>? body
  ) async {
    final payload = body != null ? jsonEncode(body) : null;
    switch (method) {
      case HttpMethod.get:
        return await _client.get(uri, headers: headers);
      case HttpMethod.post:
        return await _client.post(uri, headers: headers, body: payload);
      case HttpMethod.put:
        return await _client.put(uri, headers: headers, body: payload);
      case HttpMethod.patch:
        return await _client.patch(uri, headers: headers, body: payload);
      case HttpMethod.delete:
        return await _client.delete(uri, headers: headers, body: payload);
    }
  }

  // ===========================================================================
  // 3. LOGIC REFRESH TOKEN (NỘI BỘ, TRÁNH CIRCULAR DEPENDENCY)
  // ===========================================================================
  Future<bool> _refreshToken() async {
    final prefs = await SharedPreferences.getInstance();
    final currentRefreshToken = prefs.getString("refresh_token");

    if (currentRefreshToken == null || currentRefreshToken.isEmpty) return false;

    try {
      final uri = Uri.parse("${ApiConstants.baseUrl}${ApiConstants.refresh}");
      // Bắn API Refresh KHÔNG cần Bearer token cũ
      final response = await _client.post(
        uri,
        headers: {"Content-Type": "application/json"}, 
        body: jsonEncode({"refresh_token": currentRefreshToken}),
      );

      if (response.statusCode >= 200 && response.statusCode < 300) {
        final data = jsonDecode(response.body);
        final newAccessToken = data['access_token'];
        final newRefreshToken = data['refresh_token'];

        if (newAccessToken != null) {
          await prefs.setString("token", newAccessToken);
          if (newRefreshToken != null) {
            await prefs.setString("refresh_token", newRefreshToken);
          }
          return true;
        }
      }
      return false;
    } catch (e) {
      return false;
    }
  }

  Future<void> _forceLogout() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove('token');
    await prefs.remove('refresh_token');
    await prefs.remove('user_data');
    // Ở UI, bạn có thể listen lỗi "SessionExpired" để đẩy user ra màn hình Login
  }

  // ===========================================================================
  // 4. CÁC HÀM GỌI API DÀNH CHO REPOSITORY (GỌN GÀNG HƠN)
  // ===========================================================================
  Future<dynamic> get(String endpoint, {Map<String, dynamic>? queryParameters, Map<String, String>? headers}) async {
    return await _sendRequest(HttpMethod.get, endpoint, queryParameters: queryParameters, headers: headers);
  }

  Future<dynamic> post(String endpoint, Map<String, dynamic> body, {Map<String, dynamic>? queryParameters, Map<String, String>? headers}) async {
    return await _sendRequest(HttpMethod.post, endpoint, body: body, queryParameters: queryParameters, headers: headers);
  }

  Future<dynamic> put(String endpoint, Map<String, dynamic> body, {Map<String, String>? headers}) async {
    return await _sendRequest(HttpMethod.put, endpoint, body: body, headers: headers);
  }

  Future<dynamic> patch(String endpoint, {Map<String, dynamic>? data, Map<String, dynamic>? queryParameters, Map<String, String>? headers}) async {
    return await _sendRequest(HttpMethod.patch, endpoint, body: data, queryParameters: queryParameters, headers: headers);
  }

  Future<dynamic> delete(String endpoint, {Map<String, dynamic>? body, Map<String, String>? headers}) async {
    return await _sendRequest(HttpMethod.delete, endpoint, body: body, headers: headers);
  }

  // ===========================================================================
  // 5. XỬ LÝ RESPONSE CHUNG
  // ===========================================================================
  dynamic _handleResponse(http.Response response) {
    if (response.statusCode >= 200 && response.statusCode < 300) {
      if (response.body.isEmpty) return {};
      try {
        return jsonDecode(response.body);
      } catch (e) {
        return response.body; 
      }
    } else if (response.statusCode == 401) {
      // Nếu rơi vào đây nghĩa là refresh token cũng thất bại rồi
      throw Exception("Unauthorized: Token expired or invalid.");
    } else {
      throw Exception("API Error [${response.statusCode}]: ${response.body}");
    }
  }
}