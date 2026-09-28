import 'package:flutter/material.dart';
import 'package:testing/features/common/app_components.dart';
import 'package:testing/features/recommend/models/daily_nutrition_summary_model.dart';
import 'package:testing/features/recommend/repositories/recommend_repository.dart';
// Nhớ import các file Model và Repo của bạn ở đây

class NutritionPage extends StatefulWidget {
  const NutritionPage({super.key});

  @override
  State<NutritionPage> createState() => _NutritionPageState();
}

class _NutritionPageState extends State<NutritionPage> {
  // --- MÀU SẮC THEME (Lấy từ code của bạn) ---
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _inputFillColor = Colors.grey.shade200;
  final Color _textBrown = Colors.brown.shade900;

  // --- STATE VARIABLES ---
  bool _isLoading = true;
  bool _isSaving = false;
  DailyNutritionSummaryModel? _summaryData;

  // Repository
  final RecommendRepository _recommendRepository = RecommendRepository();

  // --- FORM CONTROLLERS ---
  final TextEditingController _ageController = TextEditingController();
  final TextEditingController _heightController = TextEditingController();
  final TextEditingController _weightController = TextEditingController();
  String? _selectedGender;
  String? _selectedActivity;
  String? _selectedGoal;

  @override
  void initState() {
    super.initState();
    _fetchData();
  }

  @override
  void dispose() {
    _ageController.dispose();
    _heightController.dispose();
    _weightController.dispose();
    super.dispose();
  }

  // --- LOGIC: FETCH DATA ---
  Future<void> _fetchData() async {
    setState(() => _isLoading = true);
    
    // Gọi API lấy dữ liệu hôm nay
    _summaryData = await _recommendRepository.getDailyNutritionSummary();
    if(_summaryData != null) {
      debugPrint("Fetched Daily Nutrition Summary: ${_summaryData}");
    }else{
      debugPrint("No Daily Nutrition Summary found for today. User may need to fill the form.");
    }
    // TODO: Tạm thời gán null để test màn hình Form Init (Nhập liệu)
    // Để test màn hình Dashboard, bạn có thể gán mock data vào đây.
   //_summaryData = null; 

    if (mounted) setState(() => _isLoading = false);
  }

  // --- LOGIC: SUBMIT INIT FORM ---
  Future<void> _handleInitNutrition() async {
    // Validate cơ bản
    if (_ageController.text.isEmpty || _heightController.text.isEmpty || _weightController.text.isEmpty) {
      showAppSnackBar(
        context,
        "Please fill all required fields!",
        type: SnackBarType.warning,
      );
      return;
    }

    setState(() => _isSaving = true);

    final age = int.tryParse(_ageController.text.trim()) ?? 0;
    final height = num.tryParse(_heightController.text.trim()) ?? 0;
    final weight = num.tryParse(_weightController.text.trim()) ?? 0;

    // Gọi API POST
    
    final success = await _recommendRepository.initDailyNutrition(
      age: age,
      heightCm: height,
      weightKg: weight,
      gender: _selectedGender,
      activityLevel: _selectedActivity,
      goal: _selectedGoal,
    );
    
  

    if (!mounted) return;

    if (success) {
      showAppSnackBar(
        context,
        "Initializing nutrition data successfully!",
        type: SnackBarType.success,
      );
      // Khởi tạo xong thì gọi lại API GET để render Dashboard
      _fetchData(); 
    } else {
      showAppSnackBar(
        context,
        "Error initializing nutrition data. Please try again.",
        type: SnackBarType.error,
      );
    }

    setState(() => _isSaving = false);
  }

  // --- MAIN BUILD ---
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        backgroundColor: Colors.white,
        elevation: 0,
        leading: IconButton(
          icon: Container(
            padding: const EdgeInsets.all(4),
            decoration: BoxDecoration(
              color: _primaryRed.withOpacity(0.1),
              borderRadius: BorderRadius.circular(8),
            ),
            child: Icon(Icons.arrow_back_ios_new, color: _primaryRed, size: 18),
          ),
          onPressed: () => Navigator.pop(context),
        ),
        centerTitle: true,
        title: Text(
          "Daily Nutrition",
          style: TextStyle(color: _textBrown, fontSize: 20, fontWeight: FontWeight.bold),
        ),
      ),
      body: _isLoading
          ? Center(child: CircularProgressIndicator(color: _primaryRed))
          : (_summaryData == null)
              ? _buildInitFormView() // Chưa có data -> Hiện form
              : _buildDashboardView(), // Có data -> Hiện biểu đồ
    );
  }

  // ==========================================
  // VIEW 1: INIT FORM (Giao diện nhập liệu)
  // ==========================================
  Widget _buildInitFormView() {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(24.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            "Let's personalize your plan! 🍎",
            style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800, color: _textBrown),
          ),
          const SizedBox(height: 8),
          const Text(
            "Enter your body metrics to calculate your daily nutritional target.",
            style: TextStyle(color: Colors.grey, fontSize: 14),
          ),
          const SizedBox(height: 30),

          // 1. AGE & GENDER (Xếp chung 1 hàng cho gọn)
          Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _buildLabel("Age"),
                    _buildTextField(controller: _ageController, isNumber: true, hint: "Years"),
                  ],
                ),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _buildLabel("Gender"),
                    _buildDropdown(
                      value: _selectedGender,
                      items: const {"MALE": "Male", "FEMALE": "Female"},
                      onChanged: (val) => setState(() => _selectedGender = val),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 20),

          // 2. HEIGHT & WEIGHT
          Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _buildLabel("Height"),
                    _buildTextField(controller: _heightController, isNumber: true, hint: "cm"),
                  ],
                ),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _buildLabel("Weight"),
                    _buildTextField(controller: _weightController, isNumber: true, hint: "kg"),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 20),

          // 3. ACTIVITY LEVEL
          _buildLabel("Activity Level"),
          _buildDropdown(
            value: _selectedActivity,
            items: const {
              "SEDENTARY": "Sedentary (Little/No exercise)",
              "LIGHTLY_ACTIVE": "Lightly Active",
              "MODERATELY_ACTIVE": "Moderately Active",
              "VERY_ACTIVE": "Very Active",
            },
            onChanged: (val) => setState(() => _selectedActivity = val),
          ),
          const SizedBox(height: 20),

          // 4. GOAL
          _buildLabel("Fitness Goal"),
          _buildDropdown(
            value: _selectedGoal,
            items: const {
              "LOSE_WEIGHT": "Lose Weight",
              "MAINTAIN": "Maintain Weight",
              "GAIN_WEIGHT": "Gain Weight",
            },
            onChanged: (val) => setState(() => _selectedGoal = val),
          ),
          const SizedBox(height: 40),

          // NÚT SAVE TỪ CODE CỦA BẠN
          SizedBox(
            width: double.infinity,
            height: 50,
            child: ElevatedButton(
              style: ElevatedButton.styleFrom(
                backgroundColor: _primaryRed,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                elevation: 0,
              ),
              onPressed: _isSaving ? null : _handleInitNutrition,
              child: _isSaving
                  ? const SizedBox(
                      width: 20, height: 20,
                      child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2),
                    )
                  : const Text(
                      "Calculate Targets",
                      style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Colors.white),
                    ),
            ),
          ),
        ],
      ),
    );
  }

  // ==========================================
  // VIEW 2: DASHBOARD (Giao diện Biểu đồ)
  // ==========================================
  Widget _buildDashboardView() {
    // Extract data an toàn
    final target = _summaryData?.target;
    final consumed = _summaryData?.consumed;
    
    // Tạm mock data hiển thị nếu API chưa cắm
    final tdee = _summaryData?.tdeeKcal ?? 2200;
    final bmr = _summaryData?.bmrKcal ?? 1600;

    return SingleChildScrollView(
      padding: const EdgeInsets.all(24.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Center(
            child: Text(
              "Today's Summary",
              style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold, color: _textBrown),
            ),
          ),
          const SizedBox(height: 24),

          // Cặp thẻ BMR & TDEE
          Row(
            children: [
              _buildInfoCard("BMR", "$bmr kcal", Icons.local_fire_department, Colors.orange),
              const SizedBox(width: 16),
              _buildInfoCard("TDEE", "$tdee kcal", Icons.directions_run, Colors.blue),
            ],
          ),
          const SizedBox(height: 30),

          Text("Macro Nutrients", style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: _textBrown)),
          const SizedBox(height: 16),

          // Thanh tiến trình Macros
          _buildMacroProgress(
            "Protein", 
            consumed: consumed?.proteinG ?? 45, 
            target: target?.proteinG ?? 120, 
            color: Colors.blueAccent, 
            unit: "g"
          ),
          _buildMacroProgress(
            "Lipid (Fat)", 
            consumed: consumed?.lipidG ?? 30, 
            target: target?.lipidG ?? 60, 
            color: Colors.amber, 
            unit: "g"
          ),
          _buildMacroProgress(
            "Carbs", 
            consumed: consumed?.carbG ?? 150, 
            target: target?.carbG ?? 250, 
            color: Colors.green, 
            unit: "g"
          ),
        ],
      ),
    );
  }

  // ==========================================
  // HELPER WIDGETS
  // ==========================================

  Widget _buildLabel(String text) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8.0),
      child: Text(text, style: TextStyle(fontSize: 14, fontWeight: FontWeight.bold, color: _textBrown)),
    );
  }

  Widget _buildTextField({required TextEditingController controller, bool isNumber = false, String? hint}) {
    return Container(
      decoration: BoxDecoration(color: _inputFillColor, borderRadius: BorderRadius.circular(8)),
      child: TextField(
        controller: controller,
        keyboardType: isNumber ? TextInputType.number : TextInputType.text,
        style: const TextStyle(color: Colors.black87, fontSize: 15),
        decoration: InputDecoration(
          hintText: hint,
          hintStyle: const TextStyle(color: Colors.grey),
          border: InputBorder.none,
          contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        ),
      ),
    );
  }

  Widget _buildDropdown({required String? value, required Map<String, String> items, required Function(String?) onChanged}) {
    return DropdownButtonFormField<String>(
      value: value,
      decoration: InputDecoration(
        filled: true,
        fillColor: _inputFillColor,
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: BorderSide.none),
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      ),
      items: items.entries.map((e) => DropdownMenuItem(value: e.key, child: Text(e.value))).toList(),
      onChanged: onChanged,
    );
  }

  // Card hiển thị TDEE / BMR
  Widget _buildInfoCard(String title, String value, IconData icon, Color iconColor) {
    return Expanded(
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: _inputFillColor,
          borderRadius: BorderRadius.circular(16),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, color: iconColor, size: 28),
            const SizedBox(height: 12),
            Text(title, style: const TextStyle(color: Colors.grey, fontSize: 14, fontWeight: FontWeight.w600)),
            const SizedBox(height: 4),
            Text(value, style: TextStyle(color: _textBrown, fontSize: 18, fontWeight: FontWeight.bold)),
          ],
        ),
      ),
    );
  }

  // Thanh tiến trình Macros
  Widget _buildMacroProgress(String name, {required num consumed, required num target, required Color color, required String unit}) {
    // Tránh lỗi chia cho 0
    double progress = target > 0 ? (consumed / target).clamp(0.0, 1.0) : 0.0;
    
    return Padding(
      padding: const EdgeInsets.only(bottom: 20.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(name, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 15)),
              Text("${consumed.toStringAsFixed(1)} / ${target.toStringAsFixed(1)} $unit", 
                style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14, color: Colors.grey)),
            ],
          ),
          const SizedBox(height: 8),
          ClipRRect(
            borderRadius: BorderRadius.circular(10),
            child: LinearProgressIndicator(
              value: progress,
              minHeight: 10,
              backgroundColor: _inputFillColor,
              valueColor: AlwaysStoppedAnimation<Color>(color),
            ),
          ),
        ],
      ),
    );
  }
}
