import 'package:flutter/material.dart';
import 'package:flutter_rating_bar/flutter_rating_bar.dart';

class ReviewBottomSheet extends StatefulWidget {
  final String dishName;
  final double? initialRating;    // <--- ADDED: Old rating (if any)
  final String? initialComment;   // <--- ADDED: Old comment (if any)
  final bool isEditMode;          // <--- ADDED: Flag to indicate Edit mode

  const ReviewBottomSheet({
    super.key, 
    required this.dishName,
    this.initialRating,
    this.initialComment,
    this.isEditMode = false,      // Default is Create mode
  });

  @override
  State<ReviewBottomSheet> createState() => _ReviewBottomSheetState();
}

class _ReviewBottomSheetState extends State<ReviewBottomSheet> {
  late double _rating;
  late TextEditingController _commentController;

  @override
  void initState() {
    super.initState();
    // Initialize data: Use old data if available, otherwise default to 5 stars and empty text field
    _rating = widget.initialRating ?? 5.0;
    _commentController = TextEditingController(text: widget.initialComment ?? "");
  }

  @override
  void dispose() {
    _commentController.dispose();
    super.dispose();
  }

  void _submitReview() {
    FocusScope.of(context).unfocus();
    Navigator.pop(context, {
      'rating': _rating.toInt(),
      'comment': _commentController.text.trim(),
    });
  }

  @override
  Widget build(BuildContext context) {
    final bottomPadding = MediaQuery.of(context).viewInsets.bottom;

    return Container(
      padding: EdgeInsets.only(left: 24, right: 24, top: 20, bottom: bottomPadding + 24),
      decoration: const BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(width: 40, height: 4, decoration: BoxDecoration(color: Colors.grey[300], borderRadius: BorderRadius.circular(2))),
          const SizedBox(height: 20),
          
          // Flexible Title based on Mode
          Text(
            widget.isEditMode ? "Edit review" : "Rate this dish",
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 8),
          Text(widget.dishName, style: TextStyle(fontSize: 15, color: Colors.grey[600]), textAlign: TextAlign.center),
          const SizedBox(height: 24),

          RatingBar.builder(
            initialRating: _rating,
            minRating: 1,
            direction: Axis.horizontal,
            allowHalfRating: false,
            itemCount: 5,
            itemPadding: const EdgeInsets.symmetric(horizontal: 4.0),
            itemBuilder: (context, _) => const Icon(Icons.star_rounded, color: Colors.amber),
            onRatingUpdate: (rating) => setState(() => _rating = rating),
          ),
          const SizedBox(height: 12),
          Text(_getRatingText(_rating), style: const TextStyle(color: Color(0xFFE84D67), fontWeight: FontWeight.bold)),
          const SizedBox(height: 24),

          TextField(
            controller: _commentController,
            maxLines: 4, maxLength: 500,
            decoration: InputDecoration(
              hintText: "Share your thoughts about this dish...",
              filled: true, fillColor: Colors.grey[100],
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: BorderSide.none),
              contentPadding: const EdgeInsets.all(16),
            ),
          ),
          const SizedBox(height: 24),

          SizedBox(
            width: double.infinity, height: 50,
            child: ElevatedButton(
              style: ElevatedButton.styleFrom(
                backgroundColor: const Color(0xFFE84D67),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(25)), elevation: 0,
              ),
              onPressed: _submitReview,
              child: Text(
                widget.isEditMode ? "Update review" : "Submit review", 
                style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Colors.white)
              ),
            ),
          ),
        ],
      ),
    );
  }

  String _getRatingText(double rating) {
    if (rating == 5) return "Excellent";
    if (rating == 4) return "Very good";
    if (rating == 3) return "Average";
    if (rating == 2) return "Not good";
    return "Poor";
  }
}