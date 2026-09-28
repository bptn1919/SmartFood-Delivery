import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_image_compress/flutter_image_compress.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';
import 'package:testing/core/network/api_constants.dart';

class AttachmentRepository {
  final String apiBaseUrl = ApiConstants.baseUrl;

  Future<String?> uploadAndGetAttachmentId(
    File originalFile,
    String token,
    String attachmentType,
  ) async {
    try {
      final fileToUpload = await _compressImageIfNeeded(originalFile);
      final fileSize = await fileToUpload.length();
      final fileName = fileToUpload.path.split('/').last;

      final presignedData = await _getPresignedUrl(
        fileName,
        fileSize,
        token,
        attachmentType,
      );
      if (presignedData == null) return null;

      final dataObject = presignedData['data'];
      final uid = dataObject['uid'].toString();
      final s3PutUrl = dataObject['url'].toString();

      final isUploaded = await _uploadToS3(s3PutUrl, fileToUpload);
      if (!isUploaded) throw Exception('Upload to S3 failed');

      final isCompleted = await _completeUpload(uid, token);
      if (!isCompleted) throw Exception('Complete upload status failed');

      return uid;
    } catch (e) {
      debugPrint('❌ Upload Attachment Error: $e');
      return null;
    }
  }

  Future<File> _compressImageIfNeeded(File file) async {
    final sizeInBytes = await file.length();
    if (sizeInBytes < 2097152) return file;

    final tempDir = await getTemporaryDirectory();
    final targetPath =
        '${tempDir.path}/compressed_${file.path.split('/').last}';

    final result = await FlutterImageCompress.compressAndGetFile(
      file.absolute.path,
      targetPath,
      quality: 70,
      minWidth: 1080,
      minHeight: 1080,
    );

    if (result == null) return file;
    return File(result.path);
  }

  Future<Map<String, dynamic>?> _getPresignedUrl(
    String fileName,
    int fileSize,
    String token,
    String attachmentType,
  ) async {
    final response = await http.post(
      Uri.parse('$apiBaseUrl/api/attachments/presigned-url'),
      headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer $token',
      },
      body: jsonEncode({
        'file_name': fileName,
        'file_size': fileSize,
        'attachment_type': attachmentType,
      }),
    );

    if (response.statusCode == 200) {
      return jsonDecode(response.body);
    }
    return null;
  }

  Future<bool> _uploadToS3(String presignedUrl, File file) async {
    final bytes = await file.readAsBytes();

    String contentType = 'image/jpeg';
    if (file.path.toLowerCase().endsWith('.png')) contentType = 'image/png';

    final response = await http.put(
      Uri.parse(presignedUrl),
      headers: {'Content-Type': contentType},
      body: bytes,
    );

    return response.statusCode == 200 || response.statusCode == 204;
  }

  Future<bool> _completeUpload(String uid, String token) async {
    final response = await http.put(
      Uri.parse('$apiBaseUrl/api/attachments/$uid/completed'),
      headers: {'Authorization': 'Bearer $token'},
    );

    return response.statusCode == 200;
  }
}
