import 'package:flutter/material.dart';

class EditDeliveryTimeResult {
  final String deliveryDate;
  final String deliveryTime;

  const EditDeliveryTimeResult({
    required this.deliveryDate,
    required this.deliveryTime,
  });
}

class EditDeliveryTimeSheet extends StatefulWidget {
  final String initialDeliveryDate;
  final String initialDeliveryTime;

  const EditDeliveryTimeSheet({
    super.key,
    required this.initialDeliveryDate,
    required this.initialDeliveryTime,
  });

  @override
  State<EditDeliveryTimeSheet> createState() => _EditDeliveryTimeSheetState();
}

class _EditDeliveryTimeSheetState extends State<EditDeliveryTimeSheet> {
  DateTime? _selectedDate;
  TimeOfDay? _selectedTime;

  static const Color _primaryRed = Color(0xFFE55866);
  static const Color _textBrown = Color(0xFF4A3225);

  @override
  void initState() {
    super.initState();
    _selectedDate = _parseYmd(widget.initialDeliveryDate) ?? DateTime.now();
    _selectedTime = _parseTimeOfDay(widget.initialDeliveryTime) ??
        const TimeOfDay(hour: 10, minute: 30);
  }

  DateTime? _parseYmd(String value) {
    if (value.isEmpty) return null;
    try {
      final parts = value.split('-');
      return DateTime(
        int.parse(parts[0]),
        int.parse(parts[1]),
        int.parse(parts[2]),
      );
    } catch (_) {
      return null;
    }
  }

  TimeOfDay? _parseTimeOfDay(String value) {
    if (value.length < 5) return null;
    try {
      return TimeOfDay(
        hour: int.parse(value.substring(0, 2)),
        minute: int.parse(value.substring(3, 5)),
      );
    } catch (_) {
      return null;
    }
  }

  String _two(int value) => value.toString().padLeft(2, '0');

  String _formatDate(DateTime date) =>
      '${date.year}-${_two(date.month)}-${_two(date.day)}';

  String _formatTime(TimeOfDay time) =>
      '${_two(time.hour)}:${_two(time.minute)}:00';

  Future<void> _pickDate() async {
    final now = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      initialDate: _selectedDate ?? now,
      firstDate: DateTime(now.year, now.month, now.day),
      lastDate: now.add(const Duration(days: 365)),
      builder: (context, child) {
        return Theme(
          data: Theme.of(context).copyWith(
            colorScheme: const ColorScheme.light(primary: _primaryRed),
          ),
          child: child!,
        );
      },
    );
    if (picked == null) return;
    setState(() => _selectedDate = picked);
  }

  Future<void> _pickTime() async {
    final picked = await showTimePicker(
      context: context,
      initialTime: _selectedTime ?? const TimeOfDay(hour: 10, minute: 30),
      builder: (context, child) {
        return Theme(
          data: Theme.of(context).copyWith(
            colorScheme: const ColorScheme.light(primary: _primaryRed),
          ),
          child: child!,
        );
      },
    );
    if (picked == null) return;

    int roundedMinute = (picked.minute / 5).round() * 5;
    int hour = picked.hour;
    if (roundedMinute == 60) {
      roundedMinute = 0;
      hour = (hour + 1) % 24;
    }

    setState(() {
      _selectedTime = TimeOfDay(hour: hour, minute: roundedMinute);
    });
  }

  void _confirm() {
    final date = _selectedDate;
    final time = _selectedTime;
    if (date == null || time == null) return;

    Navigator.pop(
      context,
      EditDeliveryTimeResult(
        deliveryDate: _formatDate(date),
        deliveryTime: _formatTime(time),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final dateLabel =
        _selectedDate == null ? 'Select date' : _formatDate(_selectedDate!);
    final timeLabel = _selectedTime == null
        ? 'Select time'
        : '${_two(_selectedTime!.hour)}:${_two(_selectedTime!.minute)}';

    return SafeArea(
      child: Padding(
        padding: EdgeInsets.only(
          left: 20,
          right: 20,
          top: 20,
          bottom: MediaQuery.of(context).viewInsets.bottom + 20,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text(
              'Edit Delivery Time',
              style: TextStyle(
                color: _textBrown,
                fontSize: 20,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 16),
            _PickerTile(
              icon: Icons.calendar_month_outlined,
              title: 'Delivery date',
              value: dateLabel,
              onTap: _pickDate,
            ),
            const SizedBox(height: 12),
            _PickerTile(
              icon: Icons.schedule_outlined,
              title: 'Delivery time',
              value: timeLabel,
              onTap: _pickTime,
            ),
            const SizedBox(height: 20),
            SizedBox(
              height: 48,
              child: ElevatedButton(
                onPressed: _confirm,
                style: ElevatedButton.styleFrom(
                  backgroundColor: _primaryRed,
                  foregroundColor: Colors.white,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(24),
                  ),
                ),
                child: const Text(
                  'Confirm',
                  style: TextStyle(fontWeight: FontWeight.bold),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _PickerTile extends StatelessWidget {
  final IconData icon;
  final String title;
  final String value;
  final VoidCallback onTap;

  const _PickerTile({
    required this.icon,
    required this.title,
    required this.value,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(14),
      onTap: onTap,
      child: Ink(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: const Color(0xFFFFF9FA),
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: const Color(0xFFF3D5D8)),
        ),
        child: Row(
          children: [
            Icon(icon, color: _EditDeliveryTimeSheetState._primaryRed),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: const TextStyle(
                      color: Colors.black54,
                      fontSize: 12,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    value,
                    style: const TextStyle(
                      color: _EditDeliveryTimeSheetState._textBrown,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ],
              ),
            ),
            const Icon(Icons.chevron_right, color: Colors.black38),
          ],
        ),
      ),
    );
  }
}
