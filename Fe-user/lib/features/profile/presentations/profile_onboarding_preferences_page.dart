// import 'package:flutter/material.dart';

// import '../../common/app_components.dart';
// import '../../recommend/models/ingredient_model.dart';
// import '../../recommend/repositories/ingredient_repository.dart';
// import '../controllers/profile_onboarding_scope.dart';

// class ProfileOnboardingPreferencesPage extends StatefulWidget {
//   const ProfileOnboardingPreferencesPage({
//     super.key,
//     required this.onCompleted,
//   });

//   final VoidCallback onCompleted;

//   @override
//   State<ProfileOnboardingPreferencesPage> createState() =>
//       _ProfileOnboardingPreferencesPageState();
// }

// class _ProfileOnboardingPreferencesPageState
//     extends State<ProfileOnboardingPreferencesPage> {
//   final IngredientRepository _ingredientRepository = IngredientRepository();
//   late Future<List<IngredientModel>> _ingredientsFuture;

//   @override
//   void initState() {
//     super.initState();
//     _ingredientsFuture = _ingredientRepository.getIngredients(pageSize: 100);
//   }

//   Future<void> _completeOnboarding() async {
//     final controller = ProfileOnboardingScope.of(context);

//     try {
//       await controller.completeOnboarding();
//       if (!mounted) return;
//       widget.onCompleted();
//     } catch (e) {
//       if (!mounted) return;
//       showAppSnackBar(
//         context,
//         'Failed to complete onboarding: $e',
//         type: SnackBarType.error,
//       );
//     }
//   }

//   Widget _chipSection({
//     required String title,
//     required List<IngredientModel> ingredients,
//     required bool Function(String uid) isSelected,
//     required void Function(String uid) onToggle,
//   }) {
//     return Column(
//       crossAxisAlignment: CrossAxisAlignment.start,
//       children: [
//         Text(
//           title,
//           style: const TextStyle(
//             fontSize: 16,
//             fontWeight: FontWeight.bold,
//             color: Color(0xFF4A3225),
//           ),
//         ),
//         const SizedBox(height: 12),
//         Wrap(
//           spacing: 8,
//           runSpacing: 8,
//           children: ingredients.map((ingredient) {
//             final uid = ingredient.uid;
//             if (uid == null || uid.isEmpty) return const SizedBox.shrink();

//             return ChoiceChip(
//               label: Text(ingredient.name ?? 'Unnamed Ingredient'),
//               selected: isSelected(uid),
//               selectedColor: const Color(0xFFE55866).withValues(alpha: 0.18),
//               labelStyle: TextStyle(
//                 color:
//                     isSelected(uid) ? const Color(0xFFE55866) : Colors.black87,
//                 fontWeight: isSelected(uid) ? FontWeight.bold : FontWeight.w500,
//               ),
//               onSelected: (_) => setState(() => onToggle(uid)),
//             );
//           }).toList(),
//         ),
//       ],
//     );
//   }

//   @override
//   Widget build(BuildContext context) {
//     final controller = ProfileOnboardingScope.of(context);
//     final draft = controller.draft;

//     return Scaffold(
//       backgroundColor: const Color(0xFFFFBB94),
//       appBar: AppBar(
//         backgroundColor: Colors.transparent,
//         elevation: 0,
//         foregroundColor: Colors.black87,
//         title: const Text('Dietary Preferences'),
//         automaticallyImplyLeading: false,
//       ),
//       body: FutureBuilder<List<IngredientModel>>(
//         future: _ingredientsFuture,
//         builder: (context, snapshot) {
//           if (snapshot.connectionState == ConnectionState.waiting) {
//             return const Center(
//               child: CircularProgressIndicator(color: Color(0xFFE55866)),
//             );
//           }

//           if (snapshot.hasError) {
//             return Center(
//               child: Padding(
//                 padding: const EdgeInsets.all(24),
//                 child: Column(
//                   mainAxisSize: MainAxisSize.min,
//                   children: [
//                     const Icon(Icons.error_outline,
//                         color: Colors.redAccent, size: 42),
//                     const SizedBox(height: 12),
//                     Text(
//                       'Could not load ingredients.\n${snapshot.error}',
//                       textAlign: TextAlign.center,
//                     ),
//                     const SizedBox(height: 16),
//                     OutlinedButton(
//                       onPressed: () {
//                         setState(() {
//                           _ingredientsFuture =
//                               _ingredientRepository.getIngredients(
//                             pageSize: 100,
//                           );
//                         });
//                       },
//                       child: const Text('Retry'),
//                     ),
//                   ],
//                 ),
//               ),
//             );
//           }

//           final ingredients = snapshot.data ?? const <IngredientModel>[];

//           return AnimatedBuilder(
//             animation: controller,
//             builder: (context, _) {
//               final currentDraft = controller.draft;
//               return ListView(
//                 padding: const EdgeInsets.all(24),
//                 children: [
//                   Card(
//                     elevation: 0,
//                     shape: RoundedRectangleBorder(
//                       borderRadius: BorderRadius.circular(24),
//                     ),
//                     child: Padding(
//                       padding: const EdgeInsets.all(20),
//                       child: Column(
//                         crossAxisAlignment: CrossAxisAlignment.start,
//                         children: [
//                           const Text(
//                             'Choose what matters to you',
//                             style: TextStyle(
//                               fontSize: 22,
//                               fontWeight: FontWeight.bold,
//                               color: Color(0xFF4A3225),
//                             ),
//                           ),
//                           const SizedBox(height: 8),
//                           const Text(
//                             'Empty selections are allowed. You can update them later.',
//                             style: TextStyle(color: Colors.grey, height: 1.4),
//                           ),
//                           const SizedBox(height: 24),
//                           _chipSection(
//                             title: 'Allergies',
//                             ingredients: ingredients,
//                             isSelected:
//                                 currentDraft.allergicIngredientUids.contains,
//                             onToggle: controller.toggleAllergy,
//                           ),
//                           const SizedBox(height: 28),
//                           _chipSection(
//                             title: 'Favorite Ingredients',
//                             ingredients: ingredients,
//                             isSelected:
//                                 currentDraft.favoriteIngredientUids.contains,
//                             onToggle: controller.toggleFavoriteIngredient,
//                           ),
//                         ],
//                       ),
//                     ),
//                   ),
//                   const SizedBox(height: 24),
//                   ElevatedButton(
//                     onPressed:
//                         controller.submitting ? null : _completeOnboarding,
//                     style: ElevatedButton.styleFrom(
//                       backgroundColor: const Color(0xFFE55866),
//                       foregroundColor: Colors.white,
//                       padding: const EdgeInsets.symmetric(vertical: 14),
//                       shape: RoundedRectangleBorder(
//                         borderRadius: BorderRadius.circular(14),
//                       ),
//                     ),
//                     child: controller.submitting
//                         ? const SizedBox(
//                             width: 20,
//                             height: 20,
//                             child: CircularProgressIndicator(
//                               color: Colors.white,
//                               strokeWidth: 2,
//                             ),
//                           )
//                         : const Text(
//                             'Complete Onboarding',
//                             style: TextStyle(fontWeight: FontWeight.bold),
//                           ),
//                   ),
//                   if (draft.heightCm == null || draft.weightKg == null)
//                     const Padding(
//                       padding: EdgeInsets.only(top: 12),
//                       child: Text(
//                         'Physical attributes are missing. Please go back and complete Step 1.',
//                         style: TextStyle(color: Colors.redAccent),
//                       ),
//                     ),
//                 ],
//               );
//             },
//           );
//         },
//       ),
//     );
//   }
// }
import 'package:flutter/material.dart';

import '../../common/app_components.dart';
import '../../recommend/models/ingredient_model.dart';
import '../../recommend/repositories/ingredient_repository.dart';
import '../controllers/profile_onboarding_scope.dart';

class ProfileOnboardingPreferencesPage extends StatefulWidget {
  const ProfileOnboardingPreferencesPage({
    super.key,
    required this.onCompleted,
  });

  final VoidCallback onCompleted;

  @override
  State<ProfileOnboardingPreferencesPage> createState() =>
      _ProfileOnboardingPreferencesPageState();
}

class _ProfileOnboardingPreferencesPageState
    extends State<ProfileOnboardingPreferencesPage> {
  final IngredientRepository _ingredientRepository = IngredientRepository();
  late Future<List<IngredientModel>> _ingredientsFuture;
  
  // Thêm Controller để quản lý việc tìm kiếm
  final TextEditingController _searchController = TextEditingController();
  String _searchQuery = '';

  @override
  void initState() {
    super.initState();
    _ingredientsFuture = _ingredientRepository.getIngredients(pageSize: 100);
  }

  @override
  void dispose() {
    _searchController.dispose(); // Đừng quên giải phóng bộ nhớ
    super.dispose();
  }

  Future<void> _completeOnboarding() async {
    final controller = ProfileOnboardingScope.of(context);

    try {
      await controller.completeOnboarding();
      if (!mounted) return;
      widget.onCompleted();
    } catch (e) {
      if (!mounted) return;
      showAppSnackBar(
        context,
        'Failed to complete onboarding: $e',
        type: SnackBarType.error,
      );
    }
  }

  // Hàm sắp xếp: Ưu tiên đưa các món ĐÃ CHỌN lên đầu list
  List<IngredientModel> _sortAndFilterIngredients(
    List<IngredientModel> ingredients,
    Set<String> selectedUids,
  ) {
    // 1. Lọc theo từ khóa tìm kiếm
    var filtered = ingredients.where((item) {
      final name = (item.name ?? '').toLowerCase();
      return name.contains(_searchQuery.toLowerCase());
    }).toList();

    // 2. Đưa các món đã chọn lên đầu
    filtered.sort((a, b) {
      final aSelected = selectedUids.contains(a.uid);
      final bSelected = selectedUids.contains(b.uid);
      if (aSelected && !bSelected) return -1;
      if (!aSelected && bSelected) return 1;
      return 0;
    });

    return filtered;
  }

  Widget _chipSection({
    required String title,
    required List<IngredientModel> ingredients,
    required Set<String> selectedUids,
    required void Function(String uid) onToggle,
  }) {
    // Áp dụng bộ lọc và sắp xếp
    final displayIngredients = _sortAndFilterIngredients(ingredients, selectedUids);

    if (displayIngredients.isEmpty) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title.toUpperCase(),
            style: const TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w700,
              color: Color(0xFF666666),
              letterSpacing: 1.0,
            ),
          ),
          const SizedBox(height: 16),
          const Text(
            'No ingredients found.',
            style: TextStyle(color: Colors.black38, fontStyle: FontStyle.italic),
          ),
        ],
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          title.toUpperCase(),
          style: const TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w700,
            color: Color(0xFF666666),
            letterSpacing: 1.0,
          ),
        ),
        const SizedBox(height: 16),
        Wrap(
          spacing: 10,
          runSpacing: 12,
          children: displayIngredients.map((ingredient) {
            final uid = ingredient.uid;
            if (uid == null || uid.isEmpty) return const SizedBox.shrink();

            final selected = selectedUids.contains(uid);

            return ChoiceChip(
              label: Text(ingredient.name ?? 'Unnamed'),
              selected: selected,
              showCheckmark: false,
              backgroundColor: const Color(0xFFF8F9FA),
              selectedColor: const Color(0xFFE55866).withValues(alpha: 0.08),
              elevation: 0,
              pressElevation: 0,
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12),
                side: BorderSide(
                  color: selected ? const Color(0xFFE55866) : Colors.transparent,
                  width: 1.5,
                ),
              ),
              labelStyle: TextStyle(
                color: selected ? const Color(0xFFE55866) : const Color(0xFF1A1A1A),
                fontWeight: selected ? FontWeight.bold : FontWeight.w600,
                fontSize: 15,
              ),
              onSelected: (_) => setState(() => onToggle(uid)),
            );
          }).toList(),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final controller = ProfileOnboardingScope.of(context);
    final draft = controller.draft;

    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        backgroundColor: Colors.white,
        elevation: 0,
        scrolledUnderElevation: 0,
        foregroundColor: const Color(0xFF1A1A1A),
        iconTheme: const IconThemeData(color: Color(0xFF1A1A1A)),
      ),
      body: FutureBuilder<List<IngredientModel>>(
        future: _ingredientsFuture,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(
              child: CircularProgressIndicator(color: Color(0xFFE55866)),
            );
          }

          if (snapshot.hasError) {
            return Center(
              child: TextButton(
                onPressed: () => setState(() {
                  _ingredientsFuture = _ingredientRepository.getIngredients(pageSize: 100);
                }),
                child: const Text('Load Failed. Tap to Retry'),
              ),
            );
          }

          final ingredients = snapshot.data ?? const <IngredientModel>[];

          return AnimatedBuilder(
            animation: controller,
            builder: (context, _) {
              final currentDraft = controller.draft;
              return ListView(
                padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 8),
                children: [
                  const Text(
                    'STEP 2 OF 2',
                    style: TextStyle(
                      color: Color(0xFFE55866),
                      fontSize: 12,
                      fontWeight: FontWeight.w800,
                      letterSpacing: 1.5,
                    ),
                  ),
                  const SizedBox(height: 16),
                  const Text(
                    'Dietary\nPreferences.',
                    style: TextStyle(
                      fontSize: 40,
                      fontWeight: FontWeight.w900,
                      color: Color(0xFF1A1A1A),
                      letterSpacing: -1.0,
                      height: 1.1,
                    ),
                  ),
                  const SizedBox(height: 24),
                  
                  // --- THANH TÌM KIẾM TỐI GIẢN ---
                  TextField(
                    controller: _searchController,
                    onChanged: (value) => setState(() => _searchQuery = value),
                    decoration: InputDecoration(
                      hintText: 'Search ingredients...',
                      hintStyle: const TextStyle(color: Colors.black38),
                      prefixIcon: const Icon(Icons.search_rounded, color: Colors.black38),
                      filled: true,
                      fillColor: const Color(0xFFF8F9FA),
                      contentPadding: const EdgeInsets.symmetric(vertical: 16),
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(16),
                        borderSide: BorderSide.none,
                      ),
                    ),
                  ),
                  const SizedBox(height: 40),
                  
                  _chipSection(
                    title: 'Allergies',
                    ingredients: ingredients,
                    selectedUids: currentDraft.allergicIngredientUids.toSet(),
                    onToggle: controller.toggleAllergy,
                  ),
                  const SizedBox(height: 40),
                  
                  _chipSection(
                    title: 'Favorite Ingredients',
                    ingredients: ingredients,
                    selectedUids: currentDraft.favoriteIngredientUids.toSet(),
                    onToggle: controller.toggleFavoriteIngredient,
                  ),
                  
                  const SizedBox(height: 40), 
                ],
              );
            },
          );
        },
      ),
      
      // --- NÚT BẤM CỐ ĐỊNH (STICKY BUTTON) ĐỂ KHÔNG PHẢI CUỘN ---
      bottomNavigationBar: SafeArea(
        child: Container(
          padding: const EdgeInsets.fromLTRB(32, 16, 32, 24),
          decoration: BoxDecoration(
            color: Colors.white,
            boxShadow: [
              BoxShadow(
                color: Colors.white.withValues(alpha: 0.95),
                spreadRadius: 20,
                blurRadius: 20,
                offset: const Offset(0, -10),
              ),
            ],
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (draft.heightCm == null || draft.weightKg == null)
                const Padding(
                  padding: EdgeInsets.only(bottom: 12),
                  child: Text(
                    'Missing physical attributes.',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: Colors.redAccent, fontWeight: FontWeight.w600),
                  ),
                ),
              ElevatedButton(
                onPressed: controller.submitting ? null : _completeOnboarding,
                style: ElevatedButton.styleFrom(
                  backgroundColor: const Color(0xFFE55866),
                  foregroundColor: Colors.white,
                  elevation: 0,
                  padding: const EdgeInsets.symmetric(vertical: 20),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                  ),
                ),
                child: controller.submitting
                    ? const SizedBox(
                        width: 20, height: 20,
                        child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2.5),
                      )
                    : const Text(
                        'COMPLETE ONBOARDING',
                        style: TextStyle(fontSize: 16, fontWeight: FontWeight.w800, letterSpacing: 1.2),
                      ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}