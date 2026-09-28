package com.amomeal.marketplace.dish.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Default {@link ExpiredOrderHandler} until `order`/`voucher` are ported: the
 * stock side of the sweep is already complete and correct (holds released,
 * inventory credited back), so this only records which orders would have been
 * cancelled. Returns 0, matching Djangos own counter semantics for "nothing was
 * cancelled".
 *
 * <p><b>How this gets replaced:</b> {@code @ConditionalOnMissingBean} is NOT used
 * here on purpose - it is only evaluated for auto-configuration {@code @Bean}
 * methods, never for component-scanned {@code @Component}s (using it that way
 * silently never fires, and broke this application context once already). The
 * module that takes ownership therefore annotates its own implementation
 * {@code @Primary}, and this default quietly steps aside.
 */
@Component
@Slf4j
public class LoggingExpiredOrderHandler implements ExpiredOrderHandler {

    @Override
    public int cancelExpiredOrders(Set<UUID> orderUids) {
        log.warn("Stock hold sweep expired {} hold(s) for order(s) {} — order cancellation + customer "
                + "notification are not wired yet (order/voucher modules not ported). See "
                + "ExpiredOrderHandler javadoc.", orderUids.size(), orderUids);
        return 0;
    }
}
