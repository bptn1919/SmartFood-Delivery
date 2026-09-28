class PersonalInfoSchema {
  final String fullName;
  final String phoneNumber;

  const PersonalInfoSchema({
    required this.fullName,
    required this.phoneNumber,
  });

  /// Chuyển đổi object Dart thành một Map JSON để gửi đi.
  Map<String, dynamic> toJson() {
    return {
      'full_name': fullName,
      'phone_number': phoneNumber,
    };
  }
}