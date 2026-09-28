class IssueSensitivityModel {
  final int? userId;
  final Map<String, dynamic>? issueProfile; 
  final num? confidence;
  final int? dataPoints;

  IssueSensitivityModel({
    this.userId,
    this.issueProfile,
    this.confidence,
    this.dataPoints,
  });

  factory IssueSensitivityModel.fromJson(Map<String, dynamic> json) {
    return IssueSensitivityModel(
      userId: json['user_id'] as int?,
      // Ép kiểu an toàn cho một object động
      issueProfile: json['issue_profile'] != null 
          ? Map<String, dynamic>.from(json['issue_profile']) 
          : null,
      confidence: json['confidence'] as num?,
      dataPoints: json['data_points'] as int?,
    );
  }
}