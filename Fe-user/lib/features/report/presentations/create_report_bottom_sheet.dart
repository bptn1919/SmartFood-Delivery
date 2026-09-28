import 'dart:io';

import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../common/app_components.dart';
import '../../profile/repository/attachment_repository.dart';
import '../models/report_category.dart';
import '../repositories/report_repository.dart';

class CreateReportBottomSheet extends StatefulWidget {
  final String? orderUid;
  final int? chefId;
  final String? dishUid;

  const CreateReportBottomSheet({
    super.key,
    this.orderUid,
    this.chefId,
    this.dishUid,
  });

  @override
  State<CreateReportBottomSheet> createState() =>
      _CreateReportBottomSheetState();
}

class _CreateReportBottomSheetState extends State<CreateReportBottomSheet> {
  final ReportRepository _repository = ReportRepository();
  final AttachmentRepository _attachmentRepository = AttachmentRepository();
  final ImagePicker _imagePicker = ImagePicker();
  final TextEditingController _descriptionController = TextEditingController();
  final GlobalKey<FormState> _formKey = GlobalKey<FormState>();

  ReportCategory? _category;
  File? _evidenceFile;
  bool _submitting = false;

  static const Color _primaryRed = Color(0xFFE55866);
  static const Color _textBrown = Color(0xFF4A3225);
  static const Color _lightPink = Color(0xFFFFF9FA);

  @override
  void dispose() {
    _descriptionController.dispose();
    super.dispose();
  }

  String get _contextLabel {
    if (widget.orderUid != null) return 'Reporting this order';
    if (widget.chefId != null) return 'Reporting this chef';
    if (widget.dishUid != null) return 'Reporting this dish';
    return 'Reporting an issue';
  }

  Future<void> _pickEvidenceImage() async {
    if (_submitting) return;

    final picked = await _imagePicker.pickImage(source: ImageSource.gallery);
    if (picked == null) return;

    setState(() {
      _evidenceFile = File(picked.path);
    });
  }

  Future<String> _requireToken() async {
    final prefs = await SharedPreferences.getInstance();
    final token = prefs.getString('token');
    if (token == null || token.isEmpty) {
      throw Exception('Missing authentication token.');
    }
    return token;
  }

  Future<void> _submit() async {
    if (_submitting) return;
    if (!_formKey.currentState!.validate()) return;

    final category = _category;
    if (category == null) {
      showAppSnackBar(
        context,
        'Please choose a report category.',
        type: SnackBarType.warning,
      );
      return;
    }

    setState(() => _submitting = true);

    try {
      String? evidenceUid;
      final evidenceFile = _evidenceFile;
      if (evidenceFile != null) {
        final token = await _requireToken();
        evidenceUid = await _attachmentRepository.uploadAndGetAttachmentId(
          evidenceFile,
          token,
          'REPORT',
        );
        if (evidenceUid == null) {
          throw Exception('Failed to upload evidence image.');
        }
      }

      await _repository.createReport(
        orderUid: widget.orderUid,
        chefId: widget.chefId,
        dishUid: widget.dishUid,
        category: category.apiValue,
        description: _descriptionController.text.trim(),
        evidenceUid: evidenceUid,
      );

      if (!mounted) return;
      Navigator.pop(context, true);
    } catch (e) {
      if (!mounted) return;
      showAppSnackBar(
        context,
        'Failed to submit report: $e',
        type: SnackBarType.error,
      );
      setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: EdgeInsets.only(
          left: 20,
          right: 20,
          top: 20,
          bottom: MediaQuery.of(context).viewInsets.bottom + 20,
        ),
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Center(
                child: Container(
                  width: 42,
                  height: 4,
                  decoration: BoxDecoration(
                    color: Colors.grey.shade300,
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
              ),
              const SizedBox(height: 20),
              const Text(
                'Report an issue',
                style: TextStyle(
                  color: _textBrown,
                  fontSize: 20,
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 6),
              Text(
                _contextLabel,
                style: TextStyle(color: Colors.grey.shade600, fontSize: 13),
              ),
              const SizedBox(height: 18),
              DropdownButtonFormField<ReportCategory>(
                initialValue: _category,
                decoration: InputDecoration(
                  labelText: 'Category',
                  filled: true,
                  fillColor: _lightPink,
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(14),
                  ),
                ),
                items: ReportCategory.values
                    .map(
                      (category) => DropdownMenuItem(
                        value: category,
                        child: Text(category.label),
                      ),
                    )
                    .toList(),
                onChanged: _submitting
                    ? null
                    : (value) => setState(() => _category = value),
                validator: (value) =>
                    value == null ? 'Please choose a category.' : null,
              ),
              const SizedBox(height: 14),
              TextFormField(
                controller: _descriptionController,
                enabled: !_submitting,
                maxLines: 5,
                minLines: 3,
                textInputAction: TextInputAction.newline,
                decoration: InputDecoration(
                  labelText: 'Description',
                  hintText: 'Describe what happened...',
                  alignLabelWithHint: true,
                  filled: true,
                  fillColor: _lightPink,
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(14),
                  ),
                ),
                validator: (value) {
                  if ((value ?? '').trim().isEmpty) {
                    return 'Please describe the issue.';
                  }
                  return null;
                },
              ),
              const SizedBox(height: 12),
              _EvidencePicker(
                evidenceFile: _evidenceFile,
                isSubmitting: _submitting,
                onPickImage: _pickEvidenceImage,
              ),
              const SizedBox(height: 18),
              SizedBox(
                height: 48,
                child: ElevatedButton(
                  onPressed: _submitting ? null : _submit,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: _primaryRed,
                    foregroundColor: Colors.white,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(24),
                    ),
                  ),
                  child: _submitting
                      ? const SizedBox(
                          width: 20,
                          height: 20,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: Colors.white,
                          ),
                        )
                      : const Text(
                          'Submit report',
                          style: TextStyle(fontWeight: FontWeight.bold),
                        ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _EvidencePicker extends StatelessWidget {
  final File? evidenceFile;
  final bool isSubmitting;
  final VoidCallback onPickImage;

  const _EvidencePicker({
    required this.evidenceFile,
    required this.isSubmitting,
    required this.onPickImage,
  });

  @override
  Widget build(BuildContext context) {
    final file = evidenceFile;

    return InkWell(
      borderRadius: BorderRadius.circular(14),
      onTap: isSubmitting ? null : onPickImage,
      child: Ink(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: const Color(0xFFFFF9FA),
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: const Color(0xFFF3D5D8)),
        ),
        child: Row(
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(10),
              child: SizedBox(
                width: 52,
                height: 52,
                child: file == null
                    ? Container(
                        color: Colors.white,
                        child: const Icon(
                          Icons.image_outlined,
                          color: Color(0xFFE55866),
                        ),
                      )
                    : Image.file(file, fit: BoxFit.cover),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Evidence image',
                    style: TextStyle(
                      color: Color(0xFF4A3225),
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    file == null
                        ? 'Optional. Add a photo from gallery.'
                        : file.path.split(Platform.pathSeparator).last,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(color: Colors.grey.shade600, fontSize: 12),
                  ),
                ],
              ),
            ),
            Icon(
              file == null ? Icons.add_photo_alternate_outlined : Icons.edit,
              color: const Color(0xFFE55866),
            ),
          ],
        ),
      ),
    );
  }
}
