class ChefProfile {
  final int userId;
  final String fullname;
  final String? mail;
  final String? phone;
  final String? bio;
  final String? file;           
  final double? rating;
  final int? numberOfOrders;

  ChefProfile({
    required this.userId,
    required this.fullname,
    this.mail,
    this.phone,
    this.bio,
    this.file,
    this.rating,
    this.numberOfOrders,
  });

  factory ChefProfile.fromJson(Map<String, dynamic> j) => ChefProfile(
        userId: (j['user_id'] ?? 0) as int,
        fullname: (j['fullname'] ?? '').toString(),
        mail: j['mail']?.toString(),
        phone: j['phone']?.toString(),
        bio: j['bio']?.toString(),
        file: j['file']?.toString(),
        rating: j['rating'] == null ? null : double.tryParse(j['rating'].toString()),
        numberOfOrders: j['number_of_orders'] == null ? null : int.tryParse(j['number_of_orders'].toString()),
      );
}
