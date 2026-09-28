class UpsertBankResponseModel {
  final String resetSessionToken;
  final String message;

  UpsertBankResponseModel({
    required this.resetSessionToken,
    required this.message,
  });

  factory UpsertBankResponseModel.fromJson(Map<String, dynamic> json) {
    return UpsertBankResponseModel(
      resetSessionToken: json['reset_session_token'] ?? '',
      message: json['message'] ?? '',
    );
  }
}