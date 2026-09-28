class AttachmentModel {
  final String? uid;
  final String? originalName;
  final String? publicUrl;

  AttachmentModel({
    this.uid,
    this.originalName,
    this.publicUrl,
  });

  factory AttachmentModel.fromJson(Map<String, dynamic> json) {
    return AttachmentModel(
      uid: json['uid'] as String?,
      originalName: json['original_name'] as String?,
      publicUrl: json['public_url'] as String?,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'uid': uid,
      'original_name': originalName,
      'public_url': publicUrl,
    };
  }
}

class IngredientModel {
  final AttachmentModel? attachment;
  final String? uid;
  final String? name;
  final String? category;
  final num? weight;
  final num? energy;
  final num? protein;
  final num? lipid;
  final num? carbohydrate;
  final num? fiber;
  final num? natri;
  final num? kali;
  final num? cholesterol;
  final num? retinol;
  final num? caroten;
  final num? vitaminB1;
  final num? vitaminB2;
  final num? vitaminPp;
  final num? vitaminC;
  final num? calcium;
  final num? phosphorus;
  final num? fe;
  final num? mg;
  final num? zn;
  final String? source;

  IngredientModel({
    this.attachment,
    this.uid,
    this.name,
    this.category,
    this.weight,
    this.energy,
    this.protein,
    this.lipid,
    this.carbohydrate,
    this.fiber,
    this.natri,
    this.kali,
    this.cholesterol,
    this.retinol,
    this.caroten,
    this.vitaminB1,
    this.vitaminB2,
    this.vitaminPp,
    this.vitaminC,
    this.calcium,
    this.phosphorus,
    this.fe,
    this.mg,
    this.zn,
    this.source,
  });

  factory IngredientModel.fromJson(Map<String, dynamic> json) {
    return IngredientModel(
      attachment: json['attachment'] != null
          ? AttachmentModel.fromJson(json['attachment'])
          : null,
      uid: json['uid'] as String?,
      name: json['name'] as String?,
      category: json['category'] as String?,
      weight: json['weight'] as num?,
      energy: json['energy'] as num?,
      protein: json['protein'] as num?,
      lipid: json['lipid'] as num?,
      carbohydrate: json['carbohydrate'] as num?,
      fiber: json['fiber'] as num?,
      natri: json['natri'] as num?,
      kali: json['kali'] as num?,
      cholesterol: json['cholesterol'] as num?,
      retinol: json['retinol'] as num?,
      caroten: json['caroten'] as num?,
      // Ép chuẩn camelCase bên Dart
      vitaminB1: json['vitamin_b_1'] as num?,
      vitaminB2: json['vitamin_b_2'] as num?,
      vitaminPp: json['vitamin_pp'] as num?,
      vitaminC: json['vitamin_c'] as num?,
      calcium: json['calcium'] as num?,
      phosphorus: json['phosphorus'] as num?,
      fe: json['fe'] as num?,
      mg: json['mg'] as num?,
      zn: json['zn'] as num?,
      source: json['source'] as String?,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'attachment': attachment?.toJson(),
      'uid': uid,
      'name': name,
      'category': category,
      'weight': weight,
      'energy': energy,
      'protein': protein,
      'lipid': lipid,
      'carbohydrate': carbohydrate,
      'fiber': fiber,
      'natri': natri,
      'kali': kali,
      'cholesterol': cholesterol,
      'retinol': retinol,
      'caroten': caroten,
      'vitamin_b_1': vitaminB1,
      'vitamin_b_2': vitaminB2,
      'vitamin_pp': vitaminPp,
      'vitamin_c': vitaminC,
      'calcium': calcium,
      'phosphorus': phosphorus,
      'fe': fe,
      'mg': mg,
      'zn': zn,
      'source': source,
    };
  }
}