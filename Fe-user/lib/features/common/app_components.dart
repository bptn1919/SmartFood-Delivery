import 'package:flutter/material.dart';

import 'app_theme.dart';

export 'app_theme.dart';

// 2. Widget Template cho màn hình Auth (Login/Signup giống hệt nhau khung ngoài)
class AuthTemplate extends StatelessWidget {
  final String title;
  final Widget child;
  final VoidCallback? onBack;

  const AuthTemplate({
    super.key,
    required this.title,
    required this.child,
    this.onBack,
  });

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.headerBg,
      body: Column(
        children: [
          // Header chung
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 60, 20, 20),
            child: Row(
              children: [
                if (onBack != null)
                  IconButton(
                    icon: const Icon(Icons.chevron_left,
                        color: Colors.black, size: 30),
                    onPressed: onBack,
                  )
                else
                  const SizedBox(
                      width:
                          48), // Placeholder để cân đối nếu không có nút back

                Expanded(
                  child: Text(
                    title,
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                      color: AppColors.surface,
                      fontSize: 28,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
                const SizedBox(width: 48), // Placeholder cân đối
              ],
            ),
          ),

          // Body chung (Phần màu trắng bo tròn)
          Expanded(
            child: Container(
              width: double.infinity,
              padding: AppInsets.allXxxl,
              decoration: const BoxDecoration(
                color: AppColors.bodyBg,
                borderRadius: BorderRadius.only(
                  topLeft: Radius.circular(30),
                  topRight: Radius.circular(30),
                ),
              ),
              child: SingleChildScrollView(child: child),
            ),
          ),
        ],
      ),
    );
  }
}

// 3. Widget Input Field (Bao gồm cả Label và Logic ẩn/hiện pass)
class AppInput extends StatelessWidget {
  final String label;
  final String hint;
  final TextEditingController controller;
  final bool isPassword;
  final bool? isVisible;
  final VoidCallback? onToggleVisibility;
  final TextInputType keyboardType;

  const AppInput({
    super.key,
    required this.label,
    required this.controller,
    this.hint = "",
    this.isPassword = false,
    this.isVisible,
    this.onToggleVisibility,
    this.keyboardType = TextInputType.text,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Label
        Padding(
          padding: const EdgeInsets.only(top: 10, bottom: 6),
          child: Text(
            label,
            style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 20),
          ),
        ),
        // TextField
        TextField(
          controller: controller,
          obscureText: isPassword && (isVisible == false),
          keyboardType: keyboardType,
          decoration: InputDecoration(
            hintText: hint,
            filled: true,
            fillColor: AppColors.inputFill,
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(10),
              borderSide: BorderSide.none,
            ),
            contentPadding:
                const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
            suffixIcon: isPassword
                ? IconButton(
                    icon: Icon(
                      (isVisible ?? false)
                          ? Icons.visibility
                          : Icons.visibility_off,
                      color: Colors.grey[700],
                    ),
                    onPressed: onToggleVisibility,
                  )
                : null,
          ),
        ),
      ],
    );
  }
}

// 4. Widget Button Chính (Xử lý loading state)
class AppButton extends StatelessWidget {
  final String text;
  final VoidCallback? onPressed;
  final bool isLoading;
  final double? width;
  final double? height;

  const AppButton({
    super.key,
    required this.text,
    required this.onPressed,
    this.isLoading = false,
    this.width,
    this.height
  });

  @override
  Widget build(BuildContext context) {
    return Center( // <-- THÊM CENTER Ở ĐÂY
      child: ElevatedButton(
        onPressed: isLoading ? null : onPressed,
        style: ElevatedButton.styleFrom(
          backgroundColor: AppColors.primary,
          padding: const EdgeInsets.symmetric(vertical: 15),
          minimumSize: Size(
            width ?? double.infinity,
            height ?? 45,
          ),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(30),
          ),
          elevation: 2,
        ),
        child: isLoading
            ? const SizedBox(
                height: 20,
                width: 20,
                child: CircularProgressIndicator(
                  color: AppColors.surface,
                  strokeWidth: 2,
                ),
              )
            : Text(
                text,
                style: const TextStyle(
                  color: AppColors.surface,
                  fontWeight: FontWeight.bold,
                  fontSize: 24,
                ),
              ),
      ),
    );
  }
}

// 5. Utility để hiện Dialog đẹp (Lấy từ Login page cũ của bạn)
void showAppDialog(BuildContext context, String title, String message,
    {VoidCallback? onOk, bool isError = false}) {
  showDialog(
    context: context,
    builder: (context) => AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      title: Column(
        children: [
          Icon(
            isError ? Icons.error_outline : Icons.check_circle_outline,
            color: isError ? Colors.red : Colors.green,
            size: 60,
          ),
          const SizedBox(height: 10),
          Text(title,
              style: const TextStyle(fontWeight: FontWeight.bold),
              textAlign: TextAlign.center),
        ],
      ),
      content: Text(message, textAlign: TextAlign.center),
      actions: [
        SizedBox(
          width: double.infinity,
          child: ElevatedButton(
            style: ElevatedButton.styleFrom(
              backgroundColor: isError ? Colors.red : Colors.green,
              shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12)),
            ),
            onPressed: () {
              Navigator.pop(context);
              if (onOk != null) onOk();
            },
            child: const Text("OK", style: TextStyle(color: AppColors.surface)),
          ),
        )
      ],
    ),
  );
}

// 6. Utility để hiện Custom SnackBar (Dùng cho Add to cart, báo lỗi nhẹ...)
enum SnackBarType { success, error, warning, info }

// 2. Cập nhật lại hàm showAppSnackBar
void showAppSnackBar(
  BuildContext context,
  String message, {
  SnackBarType type = SnackBarType.success, // Thay đổi từ isSuccess sang Enum
  String? actionLabel,
  VoidCallback? onAction,
  double bottomMargin = 50,
}) {
  ScaffoldMessenger.of(context).clearSnackBars();

  // Xác định Màu sắc và Icon dựa trên Type
  Color bgColor;
  IconData iconData;

  switch (type) {
    case SnackBarType.success:
      bgColor = Colors.green.shade600;
      iconData = Icons.check_circle_outline;
      break;
    case SnackBarType.error:
      bgColor = AppColors.primaryRed;
      iconData = Icons.error_outline;
      break;
    case SnackBarType.warning:
      bgColor = Colors.orange.shade600; // Màu cam cảnh báo
      iconData = Icons.warning_amber_rounded;
      break;
    case SnackBarType.info:
      bgColor = Colors.blue.shade600; // Màu xanh trung tính
      iconData = Icons.info_outline;
      break;
  }

  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(
      behavior: SnackBarBehavior.floating,
      margin: EdgeInsets.only(bottom: bottomMargin, left: 20, right: 20),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      backgroundColor: bgColor,
      duration: const Duration(seconds: 3), // Warning thường cần đọc lâu hơn 1 chút
      content: Row(
        children: [
          Icon(iconData, color: AppColors.surface),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              message,
              style: const TextStyle(color: AppColors.surface, fontWeight: FontWeight.bold),
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
            ),
          ),
          if (actionLabel != null && onAction != null)
            TextButton(
              onPressed: () {
                ScaffoldMessenger.of(context).hideCurrentSnackBar();
                onAction();
              },
              child: Text(
                actionLabel,
                style: const TextStyle(color: AppColors.surface, fontWeight: FontWeight.w900),
              ),
            )
        ],
      ),
    ),
  );
}

// THÊM MỚI: Widget xử lý nhập 4 số OTP
class AppOtpInput extends StatefulWidget {
  final ValueChanged<String> onChanged;

  const AppOtpInput({super.key, required this.onChanged});

  @override
  State<AppOtpInput> createState() => _AppOtpInputState();
}

class _AppOtpInputState extends State<AppOtpInput> {
  // Quản lý 4 ô input
  final List<TextEditingController> _controllers =
      List.generate(4, (_) => TextEditingController());
  final List<FocusNode> _focusNodes = List.generate(4, (_) => FocusNode());

  @override
  void dispose() {
    for (var c in _controllers) c.dispose();
    for (var f in _focusNodes) f.dispose();
    super.dispose();
  }

  void _onCodeChanged(String value, int index) {
    // Logic tự động nhảy focus
    if (value.isNotEmpty) {
      if (index < 3) _focusNodes[index + 1].requestFocus();
    } else {
      if (index > 0) _focusNodes[index - 1].requestFocus();
    }

    // Gom chuỗi OTP gửi ra ngoài
    String otp = _controllers.map((c) => c.text).join();
    widget.onChanged(otp);
  }

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: List.generate(4, (index) {
        return Container(
          margin: AppInsets.horizontalMd,
          width: 60,
          height: 70,
          child: TextField(
            controller: _controllers[index],
            focusNode: _focusNodes[index],
            keyboardType: TextInputType.number,
            textAlign: TextAlign.center,
            maxLength: 1,
            style: const TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
            decoration: InputDecoration(
              counterText: "",
              filled: true,
              fillColor: AppColors.inputNeutral,
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(10),
                borderSide: BorderSide.none,
              ),
              contentPadding: EdgeInsets.zero,
            ),
            onChanged: (val) => _onCodeChanged(val, index),
          ),
        );
      }),
    );
  }
}
