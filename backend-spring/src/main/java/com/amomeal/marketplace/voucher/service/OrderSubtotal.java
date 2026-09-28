package com.amomeal.marketplace.voucher.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One order's uid + sub_total, as the future {@code order} module would pass in for
 * {@code VoucherService.calculateNetSubtotal} — see that method's javadoc (the "forward seam
 * for order" section of this class).
 */
public record OrderSubtotal(UUID orderUid, BigDecimal subTotal) {
}
