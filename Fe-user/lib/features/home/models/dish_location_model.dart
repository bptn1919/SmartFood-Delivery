class DishLocationModel {
  final int? id;
  final String? name;
  final String? slug;
  final String? type;
  final int? parentId;

  DishLocationModel({
    this.id,
    this.name,
    this.slug,
    this.type,
    this.parentId,
  });

  factory DishLocationModel.fromJson(Map<String, dynamic> json) {
    return DishLocationModel(
      id: json['id'] as int?,
      name: json['name'] as String?,
      slug: json['slug'] as String?,
      type: json['type'] as String?,
      parentId: json['parent_id'] as int?,
    );
  }
}