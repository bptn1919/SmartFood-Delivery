class WithdrawRequestResponse {
  final String resetSessionToken;
  final String message;

  WithdrawRequestResponse({
    required this.resetSessionToken,
    required this.message,
  });

  factory WithdrawRequestResponse.fromJson(Map<String, dynamic> json) {
    return WithdrawRequestResponse(
      resetSessionToken: json['reset_session_token'] ?? '',
      message: json['message'] ?? '',
    );
  }
}
