import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:testing/features/common/recommend_chef_section.dart';
import 'package:testing/features/home/models/nearby_chef_model.dart';
import 'package:testing/features/home/presentations/see_all_chefs_page.dart';
import 'package:testing/features/home/presentations/see_all_dishes_page.dart';
import 'package:testing/features/profile/models/customer_profile_model.dart';
import 'package:testing/features/profile/repository/address_repository.dart';

import '../../../checkout/models/chef_model.dart';
import '../../../checkout/repositories/chef_repository.dart';
import '../../models/dish_model.dart';
import '../../repositories/dish_repository.dart';
import 'delicious_dishes_grid.dart';
import 'home_header.dart';
import 'popular_chefs.dart';
import 'section_title.dart';

class HomeTabContent extends StatefulWidget {
  const HomeTabContent({super.key});

  @override
  State<HomeTabContent> createState() => _HomeTabScreenState();
}

class _HomeTabScreenState extends State<HomeTabContent>
    with TickerProviderStateMixin {
  final DishRepository _dishRepo = DishRepository();
  final ChefRepository _chefRepo = ChefRepository();
  final AddressRepository _addressRepo = AddressRepository();

  late Future<List<DishModel>> _futureDishes;
  late Future<List<ChefModel>> _futureChefs;
  late Future<List<AddressModel>> _futureAddress;
  late Future<List<NearbyChefModel>> _futureNearbyChefs;

  Timer? _searchDebounce;
  final ValueNotifier<String> _searchQuery = ValueNotifier<String>('');
  late AnimationController _fadeController;
  late Animation<double> _fadeAnimation;

  @override
  void initState() {
    super.initState();

    _fadeController = AnimationController(
      duration: const Duration(milliseconds: 600),
      vsync: this,
    );
    _fadeAnimation =
        CurvedAnimation(parent: _fadeController, curve: Curves.easeIn);

    _futureDishes = _dishRepo.getTopDishes();
    _futureChefs = _chefRepo.getPopularChefs();
    _futureAddress = _addressRepo.getAddresses();
    _futureNearbyChefs = _fetchNearbyChefsBasedOnAddress();
    _fadeController.forward();
  }

  Future<List<NearbyChefModel>> _fetchNearbyChefsBasedOnAddress() async {
    try {
      final addresses = await _futureAddress;
      if (addresses.isEmpty) return [];

      final targetAddress = addresses.firstWhere(
        (addr) => addr.selected == true,
        orElse: () => addresses.first,
      );

      return await _dishRepo.getNearbyChefs(
        lat: targetAddress.latitude ?? 10.762622,
        lng: targetAddress.longitude ?? 106.660172,
        radiusKm: 40.0,
      );
    } catch (e) {
      debugPrint("❌ Error in _fetchNearbyChefsBasedOnAddress: $e");
      return [];
    }
  }

  void _onSearchChanged(String value) {
    if (_searchDebounce?.isActive ?? false) _searchDebounce!.cancel();
    _searchDebounce = Timer(const Duration(milliseconds: 500), () {
      _searchQuery.value = value.trim();
    });
  }

  @override
  void dispose() {
    _searchDebounce?.cancel();
    _searchQuery.dispose();
    _fadeController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFFF7F7F7),
      body: Stack(
        children: [
          const _HomeGradientBackground(),
          _HomeLoadedContent(
            fadeAnimation: _fadeAnimation,
            futureAddress: _futureAddress,
            futureNearbyChefs: _futureNearbyChefs,
            futureChefs: _futureChefs,
            initialFutureDishes: _futureDishes,
            searchQuery: _searchQuery,
            dishRepo: _dishRepo,
            onSearchChanged: _onSearchChanged,
          ),
        ],
      ),
    );
  }
}

class _HomeGradientBackground extends StatelessWidget {
  const _HomeGradientBackground();

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        Container(
          decoration: const BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: [
                Color(0xFFFFD3B6),
                Color(0xFFFFBB94),
                Color(0xFFFFA07A),
              ],
            ),
          ),
        ),
        Positioned(
          top: -30,
          right: -40,
          child: const _DecorativeCircle(
            size: 180,
            opacity: 0.3,
          ),
        ),
        Positioned(
          top: 120,
          left: -60,
          child: const _DecorativeCircle(
            size: 250,
            opacity: 0.2,
          ),
        ),
      ],
    );
  }
}

class _DecorativeCircle extends StatelessWidget {
  const _DecorativeCircle({
    required this.size,
    required this.opacity,
  });

  final double size;
  final double opacity;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        gradient: RadialGradient(
          colors: [
            Colors.white.withValues(alpha: opacity),
            Colors.white.withValues(alpha: 0.0),
          ],
        ),
      ),
    );
  }
}

class _HomeLoadedContent extends StatelessWidget {
  const _HomeLoadedContent({
    required this.fadeAnimation,
    required this.futureAddress,
    required this.futureNearbyChefs,
    required this.futureChefs,
    required this.initialFutureDishes,
    required this.searchQuery,
    required this.dishRepo,
    required this.onSearchChanged,
  });

  final Animation<double> fadeAnimation;
  final Future<List<AddressModel>> futureAddress;
  final Future<List<NearbyChefModel>> futureNearbyChefs;
  final Future<List<ChefModel>> futureChefs;
  final Future<List<DishModel>> initialFutureDishes;
  final ValueListenable<String> searchQuery;
  final DishRepository dishRepo;
  final ValueChanged<String> onSearchChanged;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      bottom: false,
      child: FadeTransition(
        opacity: fadeAnimation,
        child: Column(
          children: [
            HomeHeader(
              onSearchChanged: onSearchChanged,
              futureAddress: futureAddress,
            ),
            Expanded(
              child: _HomeContentShell(
                futureNearbyChefs: futureNearbyChefs,
                futureChefs: futureChefs,
                initialFutureDishes: initialFutureDishes,
                searchQuery: searchQuery,
                dishRepo: dishRepo,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _HomeContentShell extends StatelessWidget {
  const _HomeContentShell({
    required this.futureNearbyChefs,
    required this.futureChefs,
    required this.initialFutureDishes,
    required this.searchQuery,
    required this.dishRepo,
  });

  final Future<List<NearbyChefModel>> futureNearbyChefs;
  final Future<List<ChefModel>> futureChefs;
  final Future<List<DishModel>> initialFutureDishes;
  final ValueListenable<String> searchQuery;
  final DishRepository dishRepo;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.only(top: 20, left: 0, right: 0),
      margin: const EdgeInsets.only(top: 12),
      decoration: const BoxDecoration(
        color: Color(0xFFF7F7F7),
        borderRadius: BorderRadius.only(
          topLeft: Radius.circular(35),
          topRight: Radius.circular(35),
        ),
      ),
      child: _HomeFeedScrollView(
        futureNearbyChefs: futureNearbyChefs,
        futureChefs: futureChefs,
        initialFutureDishes: initialFutureDishes,
        searchQuery: searchQuery,
        dishRepo: dishRepo,
      ),
    );
  }
}

class _HomeFeedScrollView extends StatelessWidget {
  const _HomeFeedScrollView({
    required this.futureNearbyChefs,
    required this.futureChefs,
    required this.initialFutureDishes,
    required this.searchQuery,
    required this.dishRepo,
  });

  final Future<List<NearbyChefModel>> futureNearbyChefs;
  final Future<List<ChefModel>> futureChefs;
  final Future<List<DishModel>> initialFutureDishes;
  final ValueListenable<String> searchQuery;
  final DishRepository dishRepo;

  @override
  Widget build(BuildContext context) {
    return CustomScrollView(
      physics: const BouncingScrollPhysics(),
      slivers: [
        _NearbyChefsSection(
          futureNearbyChefs: futureNearbyChefs,
          searchQuery: searchQuery,
        ),
        const SliverToBoxAdapter(child: SizedBox(height: 24)),
        _PopularChefsSection(futureChefs: futureChefs),
        const SliverToBoxAdapter(child: SizedBox(height: 28)),
        _DeliciousDishesSection(
          initialFutureDishes: initialFutureDishes,
          searchQuery: searchQuery,
          dishRepo: dishRepo,
        ),
        const SliverToBoxAdapter(child: SizedBox(height: 30)),
      ],
    );
  }
}

class _NearbyChefsSection extends StatefulWidget {
  const _NearbyChefsSection({
    required this.futureNearbyChefs,
    required this.searchQuery,
  });

  final Future<List<NearbyChefModel>> futureNearbyChefs;
  final ValueListenable<String> searchQuery;

  @override
  State<_NearbyChefsSection> createState() => _NearbyChefsSectionState();
}

class _NearbyChefsSectionState extends State<_NearbyChefsSection> {
  @override
  void initState() {
    super.initState();
    widget.searchQuery.addListener(_handleSearchChanged);
  }

  @override
  void didUpdateWidget(covariant _NearbyChefsSection oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.searchQuery != widget.searchQuery) {
      oldWidget.searchQuery.removeListener(_handleSearchChanged);
      widget.searchQuery.addListener(_handleSearchChanged);
    }
  }

  @override
  void dispose() {
    widget.searchQuery.removeListener(_handleSearchChanged);
    super.dispose();
  }

  void _handleSearchChanged() {
    setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    if (widget.searchQuery.value.isNotEmpty) {
      return const SliverToBoxAdapter(child: SizedBox.shrink());
    }

    return SliverToBoxAdapter(
      child: RecommendChefSection(futureNearbyChefs: widget.futureNearbyChefs),
    );
  }
}

class _PopularChefsSection extends StatelessWidget {
  const _PopularChefsSection({required this.futureChefs});

  final Future<List<ChefModel>> futureChefs;

  @override
  Widget build(BuildContext context) {
    return SliverMainAxisGroup(
      slivers: [
        SliverToBoxAdapter(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            child: SectionTitle(
              title: "Popular Chefs",
              onSeeAllTap: () => Navigator.push(
                context,
                MaterialPageRoute(builder: (_) => const SeeAllChefsPage()),
              ),
            ),
          ),
        ),
        const SliverToBoxAdapter(child: SizedBox(height: 12)),
        SliverToBoxAdapter(
          child: PopularChefsList(futureChefs: futureChefs),
        ),
      ],
    );
  }
}

class _DeliciousDishesSection extends StatefulWidget {
  const _DeliciousDishesSection({
    required this.initialFutureDishes,
    required this.searchQuery,
    required this.dishRepo,
  });

  final Future<List<DishModel>> initialFutureDishes;
  final ValueListenable<String> searchQuery;
  final DishRepository dishRepo;

  @override
  State<_DeliciousDishesSection> createState() =>
      _DeliciousDishesSectionState();
}

class _DeliciousDishesSectionState extends State<_DeliciousDishesSection> {
  late Future<List<DishModel>> _futureDishes;

  @override
  void initState() {
    super.initState();
    _futureDishes = widget.initialFutureDishes;
    widget.searchQuery.addListener(_handleSearchChanged);
  }

  @override
  void didUpdateWidget(covariant _DeliciousDishesSection oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.searchQuery != widget.searchQuery) {
      oldWidget.searchQuery.removeListener(_handleSearchChanged);
      widget.searchQuery.addListener(_handleSearchChanged);
    }
    if (oldWidget.initialFutureDishes != widget.initialFutureDishes &&
        widget.searchQuery.value.isEmpty) {
      _futureDishes = widget.initialFutureDishes;
    }
  }

  @override
  void dispose() {
    widget.searchQuery.removeListener(_handleSearchChanged);
    super.dispose();
  }

  void _handleSearchChanged() {
    final query = widget.searchQuery.value;
    setState(() {
      _futureDishes = query.isEmpty
          ? widget.initialFutureDishes
          : widget.dishRepo.getAllDishes(search: query);
    });
  }

  @override
  Widget build(BuildContext context) {
    return SliverMainAxisGroup(
      slivers: [
        SliverToBoxAdapter(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            child: SectionTitle(
              title: "Delicious Dishes",
              onSeeAllTap: () => Navigator.push(
                context,
                MaterialPageRoute(builder: (_) => const SeeAllDishesPage()),
              ),
            ),
          ),
        ),
        const SliverToBoxAdapter(child: SizedBox(height: 12)),
        SliverPadding(
          padding: const EdgeInsets.symmetric(horizontal: 20),
          sliver: DeliciousDishesGrid(
            key: ValueKey<String>(widget.searchQuery.value),
            futureDishes: _futureDishes,
          ),
        ),
      ],
    );
  }
}
