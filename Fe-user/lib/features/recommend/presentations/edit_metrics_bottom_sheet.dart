import 'package:flutter/material.dart';
import 'package:testing/features/recommend/models/nutrition_profile_model.dart';
import 'package:testing/features/recommend/repositories/recommend_repository.dart';
// Nhớ import các class Model và Repository của bạn

class EditProfileBottomSheet extends StatefulWidget {
  final NutritionProfileModel? currentProfile;
  final VoidCallback onSuccess; // Callback để báo cho trang chính biết đã lưu xong

  const EditProfileBottomSheet({Key? key, this.currentProfile, required this.onSuccess}) : super(key: key);

  @override
  State<EditProfileBottomSheet> createState() => _EditProfileBottomSheetState();
}

class _EditProfileBottomSheetState extends State<EditProfileBottomSheet> {
  late TextEditingController _weightCtrl;
  late TextEditingController _heightCtrl;
  late TextEditingController _ageCtrl;
  bool _isSaving = false;

  final RecommendRepository _recommendRepository = RecommendRepository(); // TODO: Thay bằng instance repository của bạn (có thể dùng Provider hoặc GetIt)

  @override
  void initState() {
    super.initState();
    // Đổ dữ liệu GET vào form
    _weightCtrl = TextEditingController(text: widget.currentProfile?.weightKg?.toString() ?? "");
    _heightCtrl = TextEditingController(text: widget.currentProfile?.heightCm?.toString() ?? "");
    _ageCtrl = TextEditingController(text: widget.currentProfile?.age?.toString() ?? "");
  }

  @override
  void dispose() {
    _weightCtrl.dispose();
    _heightCtrl.dispose();
    _ageCtrl.dispose();
    super.dispose();
  }

  Future<void> _handleSave() async {
    setState(() => _isSaving = true);

    // Đóng gói data gửi đi PUT
    final newProfile = NutritionProfileModel(
      weightKg: double.tryParse(_weightCtrl.text),
      heightCm: double.tryParse(_heightCtrl.text),
      age: int.tryParse(_ageCtrl.text),
      // Truyền lại các trường cũ kẻo bị mất data
      gender: widget.currentProfile?.gender,
      activityLevel: widget.currentProfile?.activityLevel,
      goal: widget.currentProfile?.goal,
    );

    // Gọi API PUT
    // TODO: Thay bằng instance repository của bạn
    final isSuccess = await _recommendRepository.updateNutritionProfile(newProfile);

    if (mounted) {
      setState(() => _isSaving = false);
      if (isSuccess) {
        widget.onSuccess(); // Báo ra ngoài là thành công
        Navigator.pop(context); // Đóng popup
      } else {
        // Hiện snackbar báo lỗi nếu cần
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text("Cập nhật thất bại, vui lòng thử lại!")),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.only(
        bottom: MediaQuery.of(context).viewInsets.bottom, // Đẩy UI lên khi bàn phím xuất hiện
        left: 24, right: 24, top: 24,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text("Update Metrics", style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold, color: Colors.brown)),
          const SizedBox(height: 20),
          
          TextField(
            controller: _weightCtrl,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(labelText: "Weight (kg)"),
          ),
          const SizedBox(height: 12),
          
          TextField(
            controller: _heightCtrl,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(labelText: "Height (cm)"),
          ),
          const SizedBox(height: 12),
          
          TextField(
            controller: _ageCtrl,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(labelText: "Age"),
          ),
          const SizedBox(height: 24),
          
          SizedBox(
            width: double.infinity,
            child: ElevatedButton(
              style: ElevatedButton.styleFrom(
                backgroundColor: const Color(0xFFE55866),
                padding: const EdgeInsets.symmetric(vertical: 14),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
              ),
              onPressed: _isSaving ? null : _handleSave,
              child: _isSaving 
                  ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2))
                  : const Text("Save Changes", style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold)),
            ),
          ),
          const SizedBox(height: 24),
        ],
      ),
    );
  }
}