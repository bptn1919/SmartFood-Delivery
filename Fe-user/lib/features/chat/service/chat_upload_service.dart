import 'package:http/http.dart' as http;
import 'package:flutter_image_compress/flutter_image_compress.dart';
import 'package:path_provider/path_provider.dart';
import 'dart:convert';
import 'dart:io';

import 'package:testing/core/network/api_constants.dart';

class ChatUploadService {
  final String apiBaseUrl = ApiConstants.baseUrl; // Đổi lại theo IP server của bạn
  final String bearerToken = 'YOUR_BEARER_TOKEN'; // Lấy từ state management của bạn

  /// Hàm chính để gọi từ UI
  Future<String?> uploadAndGetChatImageUrl(File originalFile, String token) async {
    try {
      // 1. Tối ưu & Nén ảnh (Nếu cần)
      File fileToUpload = await _compressImageIfNeeded(originalFile);
      int fileSize = await fileToUpload.length();
      String fileName = fileToUpload.path.split('/').last;

      // 2. Lấy Presigned URL từ Backend
      final presignedData = await _getPresignedUrl(fileName, fileSize, token);
      if (presignedData == null) return null;


      final dataObject = presignedData['data'];
      
      var rawUid = dataObject['uid']; 
      var rawUrl = dataObject['url'];

      String uid = rawUid.toString();
      String s3PutUrl = rawUrl.toString();

      bool isUploaded = await _uploadToS3(s3PutUrl, fileToUpload);
      if (!isUploaded) throw Exception('Upload to S3 failed');

      bool isCompleted = await _completeUpload(uid);
      if (!isCompleted) throw Exception('Complete upload status failed');

      // 5. Lấy link public cuối cùng (bỏ đi các query params có chứa signature)
      String finalUrl = s3PutUrl.split('?').first;
      return finalUrl;

    } catch (e) {
      print('❌ ChatUploadService Error: $e');
      return null;
    }
  }

  // --- CÁC HÀM XỬ LÝ NỘI BỘ (PRIVATE METHODS) ---

  /// Nén ảnh nếu kích thước > 2MB
  Future<File> _compressImageIfNeeded(File file) async {
    int sizeInBytes = await file.length();
    // Nếu < 2MB (2 * 1024 * 1024) thì không cần nén
    if (sizeInBytes < 2097152) return file;

    final tempDir = await getTemporaryDirectory();
    final targetPath = '${tempDir.path}/compressed_${file.path.split('/').last}';

    var result = await FlutterImageCompress.compressAndGetFile(
      file.absolute.path, 
      targetPath,
      quality: 70, 
      minWidth: 1080, 
      minHeight: 1080,
    );

    return File(result!.path);
  }

  /// Lấy Presigned URL từ Django
  Future<Map<String, dynamic>?> _getPresignedUrl(String fileName, int fileSize, String token) async {
    final response = await http.post(
      Uri.parse('$apiBaseUrl/api/attachments/presigned-url'),
      headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer $token',
      },
      body: jsonEncode({
        "file_name": fileName,
        "file_size": fileSize,
        "attachment_type": "CHAT" // Đã dùng Type mới!
      }),
    );

    if (response.statusCode == 200) {
      return jsonDecode(response.body);
    }
    return null;
  }

  /// Bắn file binary qua HTTP PUT lên S3
  Future<bool> _uploadToS3(String presignedUrl, File file) async {
    final bytes = await file.readAsBytes();
    
    // Tự động nhận diện content-type cơ bản
    String contentType = 'image/jpeg';
    if (file.path.toLowerCase().endsWith('.png')) contentType = 'image/png';
    if (file.path.toLowerCase().endsWith('.pdf')) contentType = 'application/pdf';

    final response = await http.put(
      Uri.parse(presignedUrl),
      headers: {
        'Content-Type': contentType,
      },
      body: bytes,
    );

    return response.statusCode == 200 || response.statusCode == 204;
  }

  /// Báo cho Django biết đã upload xong
  Future<bool> _completeUpload(String uid) async {
    final response = await http.put(
      Uri.parse('$apiBaseUrl/api/attachments/$uid/completed'),
      headers: {
        'Authorization': 'Bearer $bearerToken',
      },
    );

    return response.statusCode == 200;
  }
}