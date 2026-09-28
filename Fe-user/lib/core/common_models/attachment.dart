class AttachmentResponse {
  final String uid;
  final String originalName;
  final String publicUrl;

  AttachmentResponse({
    required this.uid,
    required this.originalName,
    required this.publicUrl,
  });

  factory AttachmentResponse.fromJson(Map<String, dynamic> json) {
    return AttachmentResponse(
      uid: json['uid'] ?? '',
      originalName: json['original_name'] ?? '',
      publicUrl: json['public_url'] ?? '',
    );
  }

  Map<String, dynamic> toJson() => {
        'uid': uid,
        'original_name': originalName,
        'public_url': publicUrl,
      };
}
