import 'package:flutter/material.dart';
import 'package:testing/features/common/dish_card.dart';
import 'package:testing/features/recommend/models/better_dish_response.dart';
import 'package:testing/features/recommend/repositories/recommend_repository.dart';

class BetterAlternativesSheet extends StatefulWidget {
  final String badDishUid;
  final String badDishName; // Truyền thêm tên món để UI thân thiện hơn

  const BetterAlternativesSheet({
    super.key,
    required this.badDishUid,
    required this.badDishName,
  });

  // 👇 TECH LEAD: Hàm static tiện ích để gọi BottomSheet ở mọi nơi
  static void show(BuildContext context, {required String dishUid, required String dishName}) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (context) => BetterAlternativesSheet(badDishUid: dishUid, badDishName: dishName),
    );
  }

  @override
  State<BetterAlternativesSheet> createState() => _BetterAlternativesSheetState();
}

class _BetterAlternativesSheetState extends State<BetterAlternativesSheet> {
  final RecommendRepository _repo = RecommendRepository();
  bool _isLoading = true;
  BetterDishResponse? _data;

  @override
  void initState() {
    super.initState();
    _fetchAlternatives();
  }

  Future<void> _fetchAlternatives() async {
    final response = await _repo.getBetterDishesForIssue(dishUid: widget.badDishUid, limit: 5);
    debugPrint("Better Alternatives fetched for Dish UID: ${response?.items.length ?? 0} (Issue: ${response?.issue ?? 'N/A'})");
    if (mounted) {
      setState(() {
        _data = response;
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.only(top: 24, bottom: 32),
      decoration: const BoxDecoration(
        color: Color(0xFFF7F7F7),
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min, // Tự động co giãn theo nội dung
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Header gạch ngang
          Center(
            child: Container(width: 40, height: 5, decoration: BoxDecoration(color: Colors.grey.shade300, borderRadius: BorderRadius.circular(10))),
          ),
          const SizedBox(height: 20),

          // Lời xin lỗi & Tiêu đề
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  "Didn't like ${widget.badDishName}?",
                  style: TextStyle(fontSize: 14, color: Colors.grey.shade600, fontWeight: FontWeight.w500),
                ),
                const SizedBox(height: 4),
                if (_data != null && _data!.issue.isNotEmpty)
                  Text(
                    "Try these instead (Less ${_data!.issue})",
                    style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold, color: Color(0xFFE84D67)),
                  )
                else
                  const Text(
                    "Try these better alternatives",
                    style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold, color: Color(0xFF2D3142)),
                  ),
              ],
            ),
          ),
          const SizedBox(height: 20),

          // Body chứa Content
          _buildContent(),
        ],
      ),
    );
  }

  Widget _buildContent() {
    // 1. Trạng thái Loading
    if (_isLoading) {
      return const SizedBox(
        height: 250,
        child: Center(child: CircularProgressIndicator(color: Color(0xFFE84D67))),
      );
    }

    // 2. Trạng thái Lỗi / Không có data
    if (_data == null || _data!.items.isEmpty) {
      return SizedBox(
        height: 200,
        child: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(Icons.search_off_rounded, size: 48, color: Colors.grey.shade400),
              const SizedBox(height: 12),
              Text("No better alternatives found at the moment.", style: TextStyle(color: Colors.grey.shade600)),
            ],
          ),
        ),
      );
    }

    // 3. Trạng thái Thành công (Danh sách ngang)
    return SizedBox(
      height: 260, // Set cứng chiều cao để chứa DishCard
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        physics: const BouncingScrollPhysics(),
        padding: const EdgeInsets.symmetric(horizontal: 20),
        itemCount: _data!.items.length,
        separatorBuilder: (_, __) => const SizedBox(width: 16),
        itemBuilder: (context, index) {
          final dish = _data!.items[index];
          debugPrint("Better Alternative #$index: ${_data!.items.length} (Issue: ${_data!.issue}) (Name: ${dish.dishName})");
          // Tái sử dụng DishCard của bạn ở đây
          return SizedBox(
            width: 330, 
            child: DishCard(dish: dish.toDishModel()), 
          );
        },
      ),
    );
  }
}
