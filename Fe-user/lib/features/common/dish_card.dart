import 'package:flutter/material.dart';
import 'package:testing/features/home/presentations/dish_detail_page.dart';
import '../home/models/dish_model.dart';
import 'package:intl/intl.dart';

import 'app_theme.dart';

class DishCard extends StatelessWidget {
  final DishModel dish;
  final VoidCallback? onTap;

  const DishCard({
    super.key,
    required this.dish,
    this.onTap,
  });

  static final NumberFormat _currency = NumberFormat.currency(
    locale: 'vi_VN',
    symbol: 'đ',
    decimalDigits: 0,
  );

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: AppInsets.dishCardOuter,
      child: Material(
        color: AppColors.surface,
        elevation: 0,
        borderRadius: BorderRadius.circular(AppRadii.card),
        child: Ink(
          decoration: BoxDecoration(
            color: AppColors.surface,
            borderRadius: BorderRadius.circular(AppRadii.card),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withValues(alpha: 0.06),
                blurRadius: AppSpacing.xl,
                offset: const Offset(0, AppSpacing.xs),
              ),
            ],
          ),
          child: InkWell(
            borderRadius: BorderRadius.circular(AppRadii.card),
            onTap: onTap ??
                () {
                  Navigator.of(context).push(
                    MaterialPageRoute(
                      builder: (_) => DishDetailPage(dishId: dish.uid),
                    ),
                  );
                },
            child: Padding(
              padding: AppInsets.dishCardInner,
              child: IntrinsicHeight(
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    _buildImage(),
                    const SizedBox(width: AppSpacing.xl),
                    Expanded(child: _buildInfo(_currency)),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildImage() {
    return ClipRRect(
      borderRadius: BorderRadius.circular(AppRadii.image),
      child: SizedBox(
        width: AppSizes.dishImage,
        height: AppSizes.dishImage,
        child: Image.network(
          dish.imageUrl ?? "https://via.placeholder.com/96?text=No+Image",
          cacheWidth: AppSizes.dishImageCache,
          fit: BoxFit.cover,
          errorBuilder: (_, __, ___) => Container(
            color: AppColors.dishImageError,
            child: const Icon(Icons.restaurant,
                color: AppColors.dishImagePlaceholder, size: 32),
          ),
          loadingBuilder: (ctx, child, progress) {
            if (progress == null) return child;
            return Container(
              color: AppColors.dishImageLoading,
              alignment: Alignment.center,
              child: const SizedBox(
                width: AppSizes.progressIndicator,
                height: AppSizes.progressIndicator,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
            );
          },
        ),
      ),
    );
  }

  Widget _buildInfo(NumberFormat currency) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        // Row 1: Title + Stock badge
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Text(
                dish.name,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: AppTextStyles.dishTitle,
              ),
            ),
            const SizedBox(width: AppSpacing.md),
            _StockBadge(stock: dish.availableQuantity.toInt()),
          ],
        ),
        const SizedBox(height: AppSpacing.sm),

        // Row 2: Description
        Text(
          dish.description ?? "No description available",
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: AppTextStyles.dishDescription,
        ),
        const SizedBox(height: AppSpacing.lg),

        // Row 3: Price + Rating
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            Flexible(
              child: Text(
                currency.format(dish.price),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: AppTextStyles.dishPrice,
              ),
            ),
            // 👇 TECH LEAD FIX: Component Rating nằm ở góc phải
            _RatingBadge(rating: dish.avgRating ?? 0.0),
          ],
        ),
      ],
    );
  }
}

// --------------------------------------------------------
// CÁC COMPONENT PHỤ TRỢ (SUB-WIDGETS)
// --------------------------------------------------------

class _StockBadge extends StatelessWidget {
  final int stock;
  const _StockBadge({required this.stock});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: AppInsets.stockBadge,
      decoration: BoxDecoration(
        color: AppColors.dishStockBackground,
        borderRadius: BorderRadius.circular(AppRadii.pill),
      ),
      child: Text(
        'In Stock : $stock',
        style: AppTextStyles.dishStock,
      ),
    );
  }
}

class _RatingBadge extends StatelessWidget {
  final double rating;
  const _RatingBadge({required this.rating});

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        const Icon(
          Icons.star_rounded,
          color: Colors.amber,
          size: AppSizes.ratingIcon,
        ),
        const SizedBox(width: AppSpacing.xs),
        Text(
          rating.toStringAsFixed(1),
          style: AppTextStyles.dishRating,
        ),
      ],
    );
  }
}
