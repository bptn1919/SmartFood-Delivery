enum ReportCategory {
  foodSafety('FOOD_SAFETY', 'Food safety'),
  foodQuality('FOOD_QUALITY', 'Food quality'),
  wrongItem('WRONG_ITEM', 'Wrong item'),
  missingItem('MISSING_ITEM', 'Missing item'),
  hygiene('HYGIENE', 'Hygiene'),
  financial('FINANCIAL', 'Financial'),
  paymentIssue('PAYMENT_ISSUE', 'Payment issue'),
  refundIssue('REFUND_ISSUE', 'Refund issue'),
  impersonation('IMPERSONATION', 'Impersonation'),
  fakeBusiness('FAKE_BUSINESS', 'Fake business'),
  inappropriate('INAPPROPRIATE', 'Inappropriate'),
  fraud('FRAUD', 'Fraud'),
  policyViolation('POLICY_VIOLATION', 'Policy violation'),
  illegalActivity('ILLEGAL_ACTIVITY', 'Illegal activity');

  const ReportCategory(this.apiValue, this.label);

  final String apiValue;
  final String label;
}
