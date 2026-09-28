import 'package:flutter/material.dart';

class AppColors {
  AppColors._();

  static const Color primary = Color(0xFFDC586D);
  static const Color primaryRed = Color(0xFFE55866);
  static const Color accentPink = Color(0xFFE84D67);
  static const Color headerBg = Color(0xFFFFBB94);
  static const Color bodyBg = Color(0xFFF5F5F5);
  static const Color inputFill = Color(0xFFF3E9B5);
  static const Color inputNeutral = Color(0xFFE0E0E0);
  static const Color inputSoftGrey = Color(0xFFEEEEEE);
  static const Color primaryOrange = Color(0xFFFFB68C);
  static const Color surface = Colors.white;
  static const Color darkText = Color(0xFF2D3142);
  static const Color brownText = Color(0xFF4A3225);
  static const Color divider = Color(0xFFF0F0F0);
  static const Color dishStockBackground = Color(0xFFE6F7EC);
  static const Color dishStockForeground = Color(0xFF1F9E54);
  static const Color dishTitle = Color(0xFF333333);
  static const Color dishDescription = Color(0xFF8A8A8F);
  static const Color dishImageError = Color(0xFFF1F1F4);
  static const Color dishImageLoading = Color(0xFFF5F5F7);
  static const Color dishImagePlaceholder = Color(0xFFB0B0B8);
}

class AppSpacing {
  AppSpacing._();

  static const double xxs = 2;
  static const double xs = 4;
  static const double sm = 6;
  static const double md = 8;
  static const double lg = 10;
  static const double xl = 12;
  static const double xxl = 16;
  static const double xxxl = 20;
  static const double huge = 24;
}

class AppRadii {
  AppRadii._();

  static const double small = 4;
  static const double medium = 8;
  static const double input = 10;
  static const double image = 12;
  static const double card = 16;
  static const double dialog = 20;
  static const double pill = 999;
}

class AppSizes {
  AppSizes._();

  static const double dishImage = 96;
  static const double progressIndicator = 20;
  static const double ratingIcon = 18;
  static const int dishImageCache = 250;
}

class AppInsets {
  AppInsets._();

  static const EdgeInsets allXl = EdgeInsets.all(AppSpacing.xl);
  static const EdgeInsets allXxxl = EdgeInsets.all(AppSpacing.xxxl);
  static const EdgeInsets dishCardOuter = EdgeInsets.symmetric(
    horizontal: AppSpacing.xl,
    vertical: AppSpacing.sm,
  );
  static const EdgeInsets dishCardInner = EdgeInsets.all(AppSpacing.xl);
  static const EdgeInsets stockBadge = EdgeInsets.symmetric(
    horizontal: AppSpacing.lg,
    vertical: AppSpacing.xs,
  );
  static const EdgeInsets horizontalMd = EdgeInsets.symmetric(
    horizontal: AppSpacing.md,
  );
  static const EdgeInsets horizontalXxxl = EdgeInsets.symmetric(
    horizontal: AppSpacing.xxxl,
  );
  static const EdgeInsets cardBottom = EdgeInsets.only(
    bottom: AppSpacing.xxl,
  );
  static const EdgeInsets chefCardMargin = EdgeInsets.only(
    right: AppSpacing.xxl,
    bottom: AppSpacing.md,
  );
}

class AppTextStyles {
  AppTextStyles._();

  static const TextStyle dishTitle = TextStyle(
    color: AppColors.dishTitle,
    fontSize: 16,
    fontWeight: FontWeight.w900,
    letterSpacing: -0.3,
  );
  static const TextStyle dishDescription = TextStyle(
    color: AppColors.dishDescription,
    fontSize: 12,
    height: 1.35,
  );
  static const TextStyle dishPrice = TextStyle(
    color: AppColors.accentPink,
    fontSize: 18,
    fontWeight: FontWeight.w800,
  );
  static const TextStyle dishStock = TextStyle(
    color: AppColors.dishStockForeground,
    fontSize: 11,
    fontWeight: FontWeight.w600,
  );
  static const TextStyle dishRating = TextStyle(
    color: AppColors.dishTitle,
    fontSize: 14,
    fontWeight: FontWeight.w700,
  );
  static const TextStyle recommendedPrice = TextStyle(
    color: AppColors.primaryRed,
    fontSize: 15,
    fontWeight: FontWeight.w900,
  );
  static const TextStyle chefName = TextStyle(
    color: AppColors.darkText,
    fontSize: 15,
    fontWeight: FontWeight.bold,
  );
  static const TextStyle chefRating = TextStyle(
    color: AppColors.darkText,
    fontSize: 12,
    fontWeight: FontWeight.w600,
  );
  static const TextStyle sectionTitle = TextStyle(
    color: AppColors.brownText,
    fontSize: 18,
    fontWeight: FontWeight.bold,
  );
}
