import 'package:flutter/material.dart';
import '../../common/app_components.dart';
import '../repositories/edit_order_repository.dart';
import '../models/order_draft.dart';

class EditTimePage extends StatefulWidget {
  const EditTimePage({
    super.key,
    required this.orderUid,
    required this.currentDeliveryDate,
    this.initialTime,
  });

  final String orderUid;
  final DateTime currentDeliveryDate;
  final TimeOfDay? initialTime;

  @override
  State<EditTimePage> createState() => _EditTimePageState();
}

class _EditTimePageState extends State<EditTimePage> {
  // Styling colors matching the pattern
  final Color _primaryOrange = const Color(0xFFFFB68C);
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _textBrown = const Color(0xFF4A3225);

  final _repo = EditOrderRepository();

  TimeOfDay? _time;
  bool _posting = false;

  @override
  void initState() {
    super.initState();
    _time = widget.initialTime;
  }

  String _two(int x) => x.toString().padLeft(2, '0');
  String _yyyyMmDd(DateTime d) => '${d.year}-${_two(d.month)}-${_two(d.day)}';

  Future<void> _pickTime() async {
    final picked = await showTimePicker(
      context: context,
      initialTime: _time ?? const TimeOfDay(hour: 10, minute: 30),
      builder: (ctx, child) {
        return Theme(
          data: Theme.of(ctx).copyWith(
            colorScheme: const ColorScheme.light(primary: Color(0xFFE55866)),
          ),
          child: MediaQuery(
            data: MediaQuery.of(ctx),
            child: child!,
          ),
        );
      },
    );
    if (picked != null) {
      int m = (picked.minute / 5).round() * 5;
      int h = picked.hour;
      if (m == 60) { m = 0; h = (h + 1) % 24; }
      setState(() => _time = TimeOfDay(hour: h, minute: m));
    }
  }

  Future<void> _confirm() async {
    if (_posting) return;
    if (_time == null) {
      showAppSnackBar(
        context,
        'Please pick a delivery time', 
        type: SnackBarType.warning, 
      );
      return;
    }

    setState(() => _posting = true);
    try {
      final hms = '${_two(_time!.hour)}:${_two(_time!.minute)}:00';
      debugPrint('[EditTimePage] PATCH delivery-time uid=${widget.orderUid} hms=$hms');

      final OrderDraft updated = await _repo.editDeliveryTimeOfOrder(
        uid: widget.orderUid,
        hms: hms,
      );

      if (!mounted) return;

      showAppSnackBar(
        context,
        'Time updated successfully!',
        type: SnackBarType.success, // Tự động có icon check, màu xanh, bo góc và nổi lên
      );

      Navigator.pop<Map<String, dynamic>>(context, {
        'updated_draft': updated,
        'delivery_time': hms,
      });
    } catch (e) {
      if (!mounted) return;
      debugPrint('[EditTimePage] confirm error: $e');
      showAppSnackBar(
        context,
        'Update failed: $e', 
        type: SnackBarType.error, 
      );
      setState(() => _posting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final fixedDateStr = _yyyyMmDd(widget.currentDeliveryDate);
    final timeLabel = _time == null
        ? '-- : --'
        : '${_two(_time!.hour)}:${_two(_time!.minute)}';

    return Scaffold(
      backgroundColor: _primaryOrange,
      body: Column(
        children: [
          // ===== HEADER WITH PATTERN =====
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 60, 20, 20),
            child: Row(
              children: [
                IconButton(
                  icon: const Icon(Icons.chevron_left, color: Colors.black, size: 30),
                  onPressed: _posting ? null : () => Navigator.of(context).pop(),
                ),
                const Expanded(
                  child: Text(
                    "Delivery Time",
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: Colors.white,
                      fontSize: 28,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
                const SizedBox(width: 48),
              ],
            ),
          ),

          // ===== BODY WITH WHITE CARD =====
          Expanded(
            child: AbsorbPointer(
              absorbing: _posting,
              child: Container(
                width: double.infinity,
                decoration: const BoxDecoration(
                  color: Colors.white,
                  borderRadius: BorderRadius.only(
                    topLeft: Radius.circular(30),
                    topRight: Radius.circular(30),
                  ),
                ),
                child: Column(
                  children: [
                    // Fixed delivery date
                    Padding(
                      padding: const EdgeInsets.fromLTRB(20, 20, 20, 0),
                      child: Container(
                        padding: const EdgeInsets.all(16),
                        decoration: BoxDecoration(
                          color: Colors.grey[50],
                          borderRadius: BorderRadius.circular(12),
                          border: Border.all(color: Colors.grey[200]!),
                        ),
                        child: Row(
                          children: [
                            const Icon(Icons.calendar_month, color: Colors.black54),
                            const SizedBox(width: 12),
                            Expanded(
                              child: Text(
                                'Delivery date: $fixedDateStr',
                                style: const TextStyle(fontWeight: FontWeight.w600),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),

                    const SizedBox(height: 24),

                    // Time picker section
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 20),
                      child: Container(
                        padding: const EdgeInsets.all(16),
                        decoration: BoxDecoration(
                          color: Colors.grey[50],
                          borderRadius: BorderRadius.circular(12),
                          border: Border.all(color: Colors.grey[200]!),
                        ),
                        child: Row(
                          children: [
                            const Icon(Icons.schedule, color: Colors.black54),
                            const SizedBox(width: 12),
                            Container(
                              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                              decoration: BoxDecoration(
                                color: Colors.white,
                                borderRadius: BorderRadius.circular(8),
                                border: Border.all(color: Colors.grey[300]!),
                              ),
                              child: Text(
                                timeLabel,
                                style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16),
                              ),
                            ),
                            const Spacer(),
                            OutlinedButton(
                              onPressed: _pickTime,
                              style: OutlinedButton.styleFrom(
                                foregroundColor: _primaryRed,
                                side: BorderSide(color: _primaryRed),
                                shape: RoundedRectangleBorder(
                                  borderRadius: BorderRadius.circular(25),
                                ),
                                padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
                              ),
                              child: const Text(
                                'Pick time',
                                style: TextStyle(fontWeight: FontWeight.w700),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),

                    const Spacer(),

                    // Confirm button
                    Padding(
                      padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
                      child: SizedBox(
                        height: 50,
                        width: double.infinity,
                        child: ElevatedButton(
                          onPressed: _posting ? null : _confirm,
                          style: ElevatedButton.styleFrom(
                            backgroundColor: _primaryRed,
                            disabledBackgroundColor: _primaryRed.withOpacity(0.4),
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(25),
                            ),
                          ),
                          child: _posting
                              ? const SizedBox(
                                  width: 20,
                                  height: 20,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                    color: Colors.white,
                                  ),
                                )
                              : const Text(
                                  'Confirm',
                                  style: TextStyle(
                                    color: Colors.white,
                                    fontSize: 16,
                                    fontWeight: FontWeight.bold,
                                  ),
                                ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
