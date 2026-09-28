package com.amomeal.marketplace.order.service;

import com.amomeal.marketplace.cart.entity.Cart;
import com.amomeal.marketplace.cart.entity.CartItem;
import com.amomeal.marketplace.cart.exception.CartItemNotFoundException;
import com.amomeal.marketplace.cart.service.CartService;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.exception.DishNotFoundInOrderException;
import com.amomeal.marketplace.dish.exception.DishSuspendedException;
import com.amomeal.marketplace.order.exception.ChefSuspendedException;
import com.amomeal.marketplace.profile.entity.ChefSuspensionLevel;
import com.amomeal.marketplace.dish.service.StockReservationService;
import com.amomeal.marketplace.order.dto.ApplyPlatformVoucherRequest;
import com.amomeal.marketplace.order.dto.CheckoutResponse;
import com.amomeal.marketplace.order.dto.ChefInfoResponse;
import com.amomeal.marketplace.order.dto.OrderFilterRequest;
import com.amomeal.marketplace.order.dto.OrderListResponse;
import com.amomeal.marketplace.order.dto.OrderResponseWithInfo;
import com.amomeal.marketplace.order.dto.PersonalInfoRequest;
import com.amomeal.marketplace.order.dto.SubOrderDeliveryRequest;
import com.amomeal.marketplace.order.dto.UpdateDeliveryTypesRequest;
import com.amomeal.marketplace.order.entity.Checkout;
import com.amomeal.marketplace.order.entity.DeliveryType;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderItem;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.order.entity.PaymentMethod;
import com.amomeal.marketplace.order.exception.OrderHttpException;
import com.amomeal.marketplace.order.exception.OrderNotFoundException;
import com.amomeal.marketplace.order.repository.CheckoutRepository;
import com.amomeal.marketplace.order.repository.OrderAppliedVoucherRepository;
import com.amomeal.marketplace.order.repository.OrderItemRepository;
import com.amomeal.marketplace.order.repository.OrderRepository;
import com.amomeal.marketplace.order.service.OrderPaymentGateway.PaymentSession;
import com.amomeal.marketplace.order.service.OrderPaymentGateway.RefundResult;
import com.amomeal.marketplace.order.service.OrderStateMachine.ValidationOutcome;
import com.amomeal.marketplace.profile.entity.ChefPaymentInfo;
import com.amomeal.marketplace.profile.entity.ChefProfile;
import com.amomeal.marketplace.profile.entity.CustomerAddress;
import com.amomeal.marketplace.profile.exception.CustomerAddressNotFoundException;
import com.amomeal.marketplace.profile.repository.ChefPaymentInfoRepository;
import com.amomeal.marketplace.profile.repository.ChefProfileRepository;
import com.amomeal.marketplace.profile.repository.CustomerAddressRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.voucher.entity.AppliedVoucher;
import com.amomeal.marketplace.voucher.entity.Voucher;
import com.amomeal.marketplace.voucher.entity.VoucherType;
import com.amomeal.marketplace.voucher.exception.VoucherInvalidException;
import com.amomeal.marketplace.voucher.service.OrderSubtotal;
import com.amomeal.marketplace.voucher.service.PlatformCheckoutSnapshot;
import com.amomeal.marketplace.voucher.service.VoucherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Port of ../../backend/order/services/__init__.py::OrderService — checkout,
 * place-order, the chef lifecycle and cancellation — plus the handful of state
 * transitions Django performs inline in {@code order/api.py}
 * ({@code start_processing}/{@code start_delivery} and the chef-ownership 403s).
 *
 * <h2>Transaction boundaries — read before editing (CLAUDE.md §8b)</h2>
 * Every method's boundary mirrors Django's, because the compensation logic
 * depends on it:
 * <ul>
 *   <li><b>{@link #placeOrder} is deliberately NOT {@code @Transactional}.</b>
 *       Django's {@code place_order} runs under autocommit: each
 *       {@code reserve()}/{@code confirm()}/{@code release()} and each
 *       {@code save()} commits on its own, and the except-branches <i>compensate</i>
 *       by releasing holds and reverting PENDING-&gt;DRAFT. Wrapping it in one
 *       transaction would roll those compensating {@code release()} writes back
 *       together with the failure — the Postgres ledger would say RESERVED while
 *       the Redis counter had been credited back (oversell), or the reverse. Each
 *       step here therefore runs in its own transaction (the dish service's own
 *       {@code @Transactional} methods and targeted {@code @Modifying} updates).</li>
 *   <li>{@link #cancelOrder}, {@link #chefConfirmOrder},
 *       {@link #completeOrderWithRelease}, both voucher-apply methods and
 *       {@link #editDeliverAddressOfCheckout} are {@code @transaction.atomic} in
 *       Django and {@code @Transactional} here.</li>
 *   <li>{@link #checkout} is NOT atomic in Django (only its per-chef
 *       {@code create_order_draft} is). It is {@code @Transactional} here — a
 *       deliberate, safer deviation: it has no compensation logic, and a partial
 *       failure in Django only leaves orphan DRAFT orders that the next
 *       {@code checkout()} deletes anyway.</li>
 * </ul>
 *
 * <h2>Cross-module wiring</h2>
 * <ul>
 *   <li>{@code cart}: {@link CartService#getSelectedCartItemsByUser},
 *       {@link CartService#getDeliveryDatesOfSelectedItems},
 *       {@link CartService#clearSelectedItems} — the three seam methods {@code cart}
 *       left for exactly this caller.</li>
 *   <li>{@code dish}: {@link StockReservationService#reserve}/{@code confirm}/
 *       {@code release} — reserve at place-order, confirm immediately for COD,
 *       release on cancel or on any place-order failure.</li>
 *   <li>{@code voucher}: {@link VoucherService}'s primitive-ized reservation-apply
 *       methods; every AppliedVoucher status transition (RESERVED-&gt;USED on COD,
 *       -&gt;CANCELLED on cancel, -&gt;EXPIRED in the sweep) is written by
 *       {@code order} itself via {@link OrderAppliedVoucherRepository}.</li>
 *   <li>{@code payment}: {@link OrderPaymentGateway} (not ported — see its javadoc).</li>
 * </ul>
 *
 * <h2>Dropped: mongo_chat push notifications</h2>
 * Every chef/customer action endpoint in Django's {@code order/api.py} also calls
 * {@code mongo_chat.notifications.send_order_notification(...)} (Firebase push),
 * inside try/except. {@code mongo_chat} is out of scope (CLAUDE.md §0.4), so
 * those calls are omitted; they never affect the response in Django either.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    static final BigDecimal TAX_RATE = new BigDecimal("0.10");
    static final BigDecimal FREE_SHIPPING_THRESHOLD = new BigDecimal("200000");
    static final BigDecimal REDUCED_DELIVERY_FEE = new BigDecimal("15000");
    static final BigDecimal STANDARD_DELIVERY_FEE = new BigDecimal("30000");

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CheckoutRepository checkoutRepository;
    private final OrderAppliedVoucherRepository appliedVoucherRepository;
    private final CartService cartService;
    private final StockReservationService stockReservationService;
    private final VoucherService voucherService;
    private final CustomerAddressRepository customerAddressRepository;
    private final ChefPaymentInfoRepository chefPaymentInfoRepository;
    private final ChefProfileRepository chefProfileRepository;
    private final OrderPaymentGateway paymentGateway;
    private final ShippingFeeEstimator shippingFeeEstimator;
    private final OrderMapper mapper;

    // =====================================================================
    // Lookups
    // =====================================================================

    private Order requireOrder(UUID uid) {
        return orderRepository.findDetailedByUid(uid).orElseThrow(OrderNotFoundException::new);
    }

    private List<Order> requireCheckoutOrders(UUID checkoutUid) {
        List<Order> orders = orderRepository.findDetailedByCheckoutUid(checkoutUid);
        if (orders.isEmpty()) {
            throw new OrderNotFoundException();
        }
        return orders;
    }

    // =====================================================================
    // Suspension enforcement (post-port decision 2026-09-25; Django never read the flags)
    // =====================================================================

    /** A chef that is not accepting orders (self-toggled or FULL_LOCK) cannot receive new ones. */
    private void assertChefAcceptingOrders(CustomUser chef) {
        if (chef == null) {
            return;
        }
        chefProfileRepository.findByUser(chef)
                .filter(p -> !p.isAcceptingOrders() || p.getSuspensionLevel() == ChefSuspensionLevel.SUSPENDED)
                .ifPresent(p -> {
                    throw new ChefSuspendedException();
                });
    }

    private static void assertDishNotSuspended(Dish dish) {
        if (dish != null && dish.isSuspended()) {
            throw new DishSuspendedException();
        }
    }

    // =====================================================================
    // checkout — cart -> DRAFT orders
    // =====================================================================

    /**
     * Django: {@code OrderService.checkout(user)}. Turns the user's SELECTED cart items
     * into one {@link Checkout} + one DRAFT {@link Order} per chef. Touches no stock
     * and no voucher — holds are only taken at {@link #placeOrder}.
     */
    @Transactional
    public CheckoutResponse checkout(CustomUser user) {
        List<CartItem> cartItems = cartService.getSelectedCartItemsByUser(user);
        if (cartItems.isEmpty()) {
            throw new CartItemNotFoundException();
        }
        Set<Long> checkedChefs = new HashSet<>();
        for (CartItem item : cartItems) {
            assertDishNotSuspended(item.getDish());
            CustomUser owner = item.getDish().getOwner();
            if (owner != null && checkedChefs.add(owner.getId())) {
                assertChefAcceptingOrders(owner);
            }
        }
        Cart cart = cartService.getOrCreateCart(user);
        LocalDate deliveryDate = cartService.getDeliveryDatesOfSelectedItems(cart).orElse(null);
        // Django: (timezone.now() + timedelta(minutes=30)).time() — USE_TZ=True, TIME_ZONE='UTC'.
        LocalTime deliveryTime = LocalTime.now(ZoneOffset.UTC).plusMinutes(30).truncatedTo(ChronoUnit.MICROS);

        // Django: CustomerAddress.objects.filter(user=user).first() — lowest pk, NOT the selected one.
        CustomerAddress deliveryAddress = customerAddressRepository.findAllByUser(user).stream()
                .min(Comparator.comparing(CustomerAddress::getId))
                .orElse(null);
        if (deliveryAddress == null) {
            // PORT-NOTE: Django passes "Please add a delivery address before checkout" as the
            // exception's `detail` (APIException.__init__(detail)); profile's ported class has
            // only a no-arg constructor and profile is out of scope, so `data` is null here.
            throw new CustomerAddressNotFoundException();
        }
        selectAddress(user, deliveryAddress.getId());

        // Clean up old DRAFT orders (never-placed checkouts). Django's DB-level CASCADE also
        // deletes their AppliedVoucher rows — emulated explicitly (no FK in this port).
        List<Order> oldDrafts = orderRepository.findByOwnerAndStatus(user, OrderStatus.DRAFT);
        if (!oldDrafts.isEmpty()) {
            List<UUID> draftUids = oldDrafts.stream().map(Order::getUid).toList();
            appliedVoucherRepository.deleteByOrderUidIn(draftUids);
            orderRepository.deleteAllInBatch(orderRepository.findAllById(draftUids));
            // The bulk DELETE above clears the persistence context (clearAutomatically), which
            // detaches the cart items loaded earlier — re-read them (Django's queryset is lazy
            // and is only iterated here anyway, so this is also the faithful order).
            cartItems = cartService.getSelectedCartItemsByUser(user);
        }

        // Group by chef, preserving first-seen order (Python dict insertion order).
        Map<Long, List<CartItem>> groupedByChef = new LinkedHashMap<>();
        Map<Long, CustomUser> chefs = new HashMap<>();
        for (CartItem item : cartItems) {
            CustomUser chef = item.getDish().getOwner();
            Long key = chef == null ? null : chef.getId();
            groupedByChef.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
            chefs.put(key, chef);
        }

        Checkout checkout = checkoutRepository.save(Checkout.builder()
                .owner(user)
                .fullName(OrderMapper.fullName(user))
                .phoneNumber(user.getPhoneNumber() == null ? "" : user.getPhoneNumber())
                .deliveryDate(deliveryDate)
                .deliveryTime(deliveryTime)
                .deliveryAddress(deliveryAddress)
                .paymentMethod(PaymentMethod.COD)
                .build());

        List<Order> orders = new ArrayList<>();
        BigDecimal totalSub = BigDecimal.ZERO;
        BigDecimal totalTax = BigDecimal.ZERO;
        BigDecimal totalDelivery = BigDecimal.ZERO;
        for (Map.Entry<Long, List<CartItem>> entry : groupedByChef.entrySet()) {
            Order order = createOrderDraft(user, entry.getValue(), chefs.get(entry.getKey()), checkout);
            orders.add(order);
            totalSub = totalSub.add(order.getSubTotal());
            totalTax = totalTax.add(order.getTaxAndFees());
            totalDelivery = totalDelivery.add(order.getDeliveryFee());
        }

        checkout.setSubTotal(totalSub);
        checkout.setTaxAndFees(totalTax);
        checkout.setDeliveryFee(totalDelivery);
        checkout.setTotalPrice(totalSub.add(totalTax).add(totalDelivery));
        checkout.setOrders(orders);
        checkoutRepository.save(checkout);

        return mapper.toCheckoutResponse(orders);
    }

    /**
     * Django: {@code OrderORM.create_order_draft} — prices are snapshotted from the dish
     * NOW; tax 10%; delivery fee 15k when sub_total &gt;= 200k else 30k.
     */
    private Order createOrderDraft(CustomUser user, List<CartItem> cartItems, CustomUser chef, Checkout checkout) {
        Order order = Order.builder()
                .checkout(checkout)
                .owner(user)
                .chef(chef)
                .status(OrderStatus.DRAFT)
                .build();
        BigDecimal subTotal = BigDecimal.ZERO;
        for (CartItem cartItem : cartItems) {
            Dish dish = cartItem.getDish();
            subTotal = subTotal.add(dish.getPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity())));
            order.getItems().add(OrderItem.builder()
                    .order(order)
                    .dish(dish)
                    .dishName(dish.getName())
                    .dishImageUrl(dish.getAttachment() != null ? dish.getAttachment().getPublicUrl() : null)
                    .quantity(cartItem.getQuantity())
                    .price(dish.getPrice())
                    .build());
        }
        // PORT-NOTE: Django keeps the 4-dp product in memory but the DecimalField(2dp)
        // persists it quantized ROUND_HALF_EVEN; persisted value used throughout here.
        BigDecimal tax = TAX_RATE.multiply(subTotal).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal deliveryFee = subTotal.compareTo(FREE_SHIPPING_THRESHOLD) >= 0
                ? REDUCED_DELIVERY_FEE : STANDARD_DELIVERY_FEE;
        order.setSubTotal(subTotal);
        order.setTaxAndFees(tax);
        order.setDeliveryFee(deliveryFee);
        order.setTotalPrice(subTotal.add(tax).add(deliveryFee));
        return orderRepository.save(order);
    }

    /** Django: {@code CustomerORM.selected_address} — select one, deselect the rest. */
    private void selectAddress(CustomUser user, Long addressId) {
        List<CustomerAddress> all = customerAddressRepository.findAllByUser(user);
        for (CustomerAddress a : all) {
            a.setSelected(a.getId().equals(addressId));
        }
        customerAddressRepository.saveAll(all);
    }

    // =====================================================================
    // checkout edits
    // =====================================================================

    /** Django: {@code edit_profile_of_checkout}. Ownership enforced by the controller (post-port fix). */
    @Transactional
    public CheckoutResponse editProfileOfCheckout(UUID checkoutUid, PersonalInfoRequest payload) {
        List<Order> orders = requireCheckoutOrders(checkoutUid);
        Checkout checkout = orders.get(0).getCheckout();
        checkout.setFullName(payload.fullName());
        checkout.setPhoneNumber(payload.phoneNumber());
        checkoutRepository.save(checkout);
        return mapper.toCheckoutResponse(orders);
    }

    /**
     * Django: {@code edit_payment_method_of_checkout}. Switching to PAYOS requires every
     * chef in the checkout to have a VERIFIED {@code ChefPaymentInfo} — otherwise
     * {@code HttpError(400, ...)}, since a PayOS payout could never reach them.
     */
    @Transactional
    public CheckoutResponse editPaymentMethodOfCheckout(UUID checkoutUid, PaymentMethod method) {
        List<Order> orders = requireCheckoutOrders(checkoutUid);
        if (method == PaymentMethod.PAYOS) {
            Set<Long> chefIds = new HashSet<>();
            Set<Long> verified = new HashSet<>();
            for (Order order : orders) {
                if (order.getChef() == null) {
                    continue;
                }
                chefIds.add(order.getChef().getId());
                chefPaymentInfoRepository.findByUser(order.getChef())
                        .filter(ChefPaymentInfo::isVerified)
                        .ifPresent(info -> verified.add(order.getChef().getId()));
            }
            if (!chefIds.isEmpty() && !verified.containsAll(chefIds)) {
                throw new OrderHttpException(HttpStatus.BAD_REQUEST,
                        "Some chefs are not verified for bank transfer. Please choose COD payment method.");
            }
        }
        Checkout checkout = orders.get(0).getCheckout();
        checkout.setPaymentMethod(method);
        checkoutRepository.save(checkout);
        return mapper.toCheckoutResponse(orders);
    }

    /** Django: {@code edit_delivery_time_of_checkout} (tz info is stripped — LocalTime has none). */
    @Transactional
    public CheckoutResponse editDeliveryTimeOfCheckout(UUID checkoutUid, LocalTime deliveryTime) {
        List<Order> orders = requireCheckoutOrders(checkoutUid);
        Checkout checkout = orders.get(0).getCheckout();
        checkout.setDeliveryTime(deliveryTime);
        checkoutRepository.save(checkout);
        return mapper.toCheckoutResponse(orders);
    }

    /**
     * Django: {@code edit_deliver_address_of_checkout} (atomic). PORT-NOTE: the address
     * lookup is Django's {@code CustomerAddress.objects.get(user=user, id=address_id)} —
     * a missing/foreign id raises an uncaught {@code DoesNotExist} (500) before the
     * service's own {@code if not addr: raise CustomerAddressNotFoundException} (dead
     * code) is reached. Preserved as an uncaught {@code NoSuchElementException}.
     */
    @Transactional
    public CheckoutResponse editDeliverAddressOfCheckout(CustomUser user, UUID checkoutUid, Long addressId) {
        List<Order> orders = requireCheckoutOrders(checkoutUid);
        CustomerAddress address = customerAddressRepository.findByUserAndId(user, addressId).orElseThrow();
        selectAddress(user, addressId);
        Checkout checkout = orders.get(0).getCheckout();
        checkout.setDeliveryAddress(address);
        checkoutRepository.save(checkout);
        return mapper.toCheckoutResponse(orders);
    }

    /**
     * Django: {@code edit_delivery_types_of_checkout}. Phase 1 quotes fees (external
     * Ahamove call, no DB lock); phase 2 writes them. Only orders whose chef is in the
     * payload are updated; the checkout's delivery_fee/total_price are re-summed.
     * PORT-NOTE (preserved): platform SHIPPING discounts are NOT re-allocated after a
     * fee change, and checkout.total_discount is untouched.
     */
    @Transactional
    public CheckoutResponse editDeliveryTypesOfCheckout(UUID checkoutUid, UpdateDeliveryTypesRequest payload) {
        List<Order> orders = requireCheckoutOrders(checkoutUid);
        Map<Long, DeliveryType> deliveryMap = new HashMap<>();
        for (SubOrderDeliveryRequest sub : payload.subOrders()) {
            deliveryMap.put(sub.chefId(), sub.deliveryType());
        }
        Checkout checkout = orders.get(0).getCheckout();

        Map<UUID, BigDecimal> estimatedFees = new HashMap<>();
        for (Order order : orders) {
            Long chefId = order.getChef() == null ? null : order.getChef().getId();
            DeliveryType newType = deliveryMap.get(chefId);
            if (newType == DeliveryType.THIRD_PARTY) {
                if (checkout.getDeliveryAddress() == null) {
                    throw new IllegalStateException("Checkout missing delivery address"); // Django ValueError -> 500
                }
                // Django: order.chef.chef_profile.* — RelatedObjectDoesNotExist (500) if missing.
                ChefProfile profile = chefProfileRepository.findByUser(order.getChef()).orElseThrow();
                int fee = shippingFeeEstimator.estimateFee(
                        profile.getKitchenLatitude(), profile.getKitchenLongitude(),
                        checkout.getDeliveryAddress().getLatitude(), checkout.getDeliveryAddress().getLongitude());
                estimatedFees.put(order.getUid(), BigDecimal.valueOf(fee));
            } else if (newType == DeliveryType.SELF_PICKUP) {
                estimatedFees.put(order.getUid(), BigDecimal.ZERO);
            } else {
                estimatedFees.put(order.getUid(), order.getDeliveryFee());
            }
        }

        BigDecimal newCheckoutDelivery = BigDecimal.ZERO;
        BigDecimal newCheckoutTotal = BigDecimal.ZERO;
        for (Order order : orders) {
            Long chefId = order.getChef() == null ? null : order.getChef().getId();
            if (deliveryMap.containsKey(chefId)) {
                order.setDeliveryType(deliveryMap.get(chefId));
                order.setDeliveryFee(estimatedFees.get(order.getUid()));
                order.setTotalPrice(order.getSubTotal().add(order.getTaxAndFees())
                        .add(order.getDeliveryFee()).subtract(order.getTotalDiscount()));
                orderRepository.save(order);
            }
            newCheckoutDelivery = newCheckoutDelivery.add(order.getDeliveryFee());
            newCheckoutTotal = newCheckoutTotal.add(order.getTotalPrice());
        }
        checkout.setDeliveryFee(newCheckoutDelivery);
        checkout.setTotalPrice(newCheckoutTotal);
        checkoutRepository.save(checkout);
        return mapper.toCheckoutResponse(orders);
    }

    // =====================================================================
    // place_order — reserve stock, branch on payment method
    // =====================================================================

    /** One held item, snapshotted so compensation never depends on entity state. */
    private record Hold(long itemId, UUID orderUid, Dish dish, LocalDate date, int quantity) {
    }

    /**
     * Django: {@code OrderService.place_order(uid, bank_code)}. <b>Not transactional</b> —
     * see the class javadoc; every step commits independently, exactly like Django's
     * autocommit, so the compensating releases in the failure branches are durable.
     *
     * <ol>
     *   <li>Snapshot delivery name/phone/address/lat/lng onto each order (in memory).</li>
     *   <li>RESERVE every item (Redis Lua check-and-decrement + RESERVED ledger row).
     *       Any failure: release the holds taken so far in this call, rethrow — nothing
     *       else has been written.</li>
     *   <li>COD: vouchers RESERVED-&gt;USED; create the COD payment; orders -&gt;
     *       PENDING; CONFIRM every hold (ledger CONFIRMED + permanent DishAvailability
     *       decrement, one transaction per item); clear the selected cart items. Any
     *       failure: release all holds (a confirmed one is CONFIRMED-&gt;CANCELLED and
     *       its DishAvailability restored), PENDING-&gt;DRAFT, rethrow.</li>
     *   <li>PAYOS: create the payment session; orders -&gt; PENDING; holds stay
     *       RESERVED for the webhook (or the TTL sweep); clear cart. Any failure:
     *       release holds, PENDING-&gt;DRAFT, cancel the session, rethrow.</li>
     * </ol>
     *
     * PORT-NOTE (preserved): no order-status guard — Django never checks the orders are
     * still DRAFT. A repeat call on an already-placed checkout is still safe for
     * inventory ({@code reserve()} refuses to reopen a RESERVED/CONFIRMED row and
     * compensates its own Redis decrement), but a CANCELLED order's items can be
     * re-reserved and the order moved back to PENDING.
     *
     * PORT-NOTE (preserved, money-adjacent): in the COD branch the voucher
     * RESERVED-&gt;USED update commits BEFORE the payment/confirm block and is NOT reverted
     * by that block's failure handler.
     */
    public CheckoutResponse placeOrder(UUID checkoutUid, String bankCode) {
        List<Order> orders = requireCheckoutOrders(checkoutUid);
        for (Order order : orders) {
            assertChefAcceptingOrders(order.getChef());
            for (OrderItem item : order.getItems()) {
                assertDishNotSuspended(item.getDish());
            }
        }
        Checkout checkout = orders.get(0).getCheckout();
        CustomerAddress address = checkout.getDeliveryAddress();
        if (address == null) {
            throw new IllegalStateException("Checkout missing delivery address"); // Django ValueError -> 500
        }
        for (Order order : orders) {
            order.setDeliveryName(checkout.getFullName());
            order.setDeliveryPhone(checkout.getPhoneNumber());
            order.setDeliveryAddressText(address.fullAddress());
            order.setDeliveryLatitude(address.getLatitude());
            order.setDeliveryLongitude(address.getLongitude());
        }

        // ---- RESERVE INVENTORY FIRST ----
        List<Hold> reservedHolds = new ArrayList<>();
        try {
            for (Order order : orders) {
                for (OrderItem item : order.getItems()) {
                    stockReservationService.reserve(item.getId(), order.getUid(), item.getDish(),
                            checkout.getDeliveryDate(), item.getQuantity());
                    reservedHolds.add(new Hold(item.getId(), order.getUid(), item.getDish(),
                            checkout.getDeliveryDate(), item.getQuantity()));
                }
            }
        } catch (RuntimeException ex) {
            releaseAll(reservedHolds);
            throw ex;
        }

        List<UUID> orderUids = orders.stream().map(Order::getUid).toList();

        if (checkout.getPaymentMethod() == PaymentMethod.COD) {
            appliedVoucherRepository.markReservedAsUsed(checkout.getUid(), orderUids);
            try {
                paymentGateway.createCodPayment(checkout.getUid());
                for (Order order : orders) {
                    markPlaced(order);
                }
                // COD has no "waiting for payment" window — the sale is final now.
                for (Hold hold : reservedHolds) {
                    stockReservationService.confirm(hold.itemId(), hold.dish(), hold.date(), hold.quantity());
                }
                cartService.clearSelectedItems(orders.get(0).getOwner());
                return mapper.toCheckoutResponse(orders);
            } catch (RuntimeException ex) {
                releaseAll(reservedHolds);
                revertPendingToDraft(orders);
                throw ex;
            }
        } else if (checkout.getPaymentMethod() == PaymentMethod.PAYOS) {
            PaymentSession payment = null;
            try {
                payment = paymentGateway.createPayosPayment(checkout.getUid(), bankCode);
                for (Order order : orders) {
                    markPlaced(order);
                }
                // Holds stay RESERVED; confirmed by the payment webhook, or expired by the sweep.
                cartService.clearSelectedItems(orders.get(0).getOwner());
                return mapper.toCheckoutResponse(orders).withPayment(payment);
            } catch (RuntimeException ex) {
                releaseAll(reservedHolds);
                revertPendingToDraft(orders);
                if (payment != null) {
                    paymentGateway.cancelPayment(payment.paymentUid(),
                            "Order placement failed after payment session creation");
                }
                throw ex;
            }
        }
        throw new IllegalStateException("Unsupported payment method: " + checkout.getPaymentMethod());
    }

    private void releaseAll(List<Hold> holds) {
        for (Hold hold : holds) {
            stockReservationService.release(hold.itemId(), hold.dish(), hold.date(), hold.quantity(), false);
        }
    }

    /** Django: {@code OrderORM.place_order(order)}. */
    private void markPlaced(Order order) {
        order.setStatus(OrderStatus.PENDING);
        orderRepository.markPlaced(order.getUid(), OrderStatus.PENDING, order.getDeliveryName(),
                order.getDeliveryPhone(), order.getDeliveryAddressText(),
                order.getDeliveryLatitude(), order.getDeliveryLongitude());
    }

    /** Django: {@code if order.status == PENDING: order.status = DRAFT; save(update_fields=["status"])}. */
    private void revertPendingToDraft(List<Order> orders) {
        for (Order order : orders) {
            if (order.getStatus() == OrderStatus.PENDING) {
                order.setStatus(OrderStatus.DRAFT);
                orderRepository.updateStatus(order.getUid(), OrderStatus.DRAFT);
            }
        }
    }

    // =====================================================================
    // reads
    // =====================================================================

    /** Django's "sync payment for DRAFT orders" block, wrapped in try/except + log. */
    private Order syncIfDraft(Order order) {
        if (order.getStatus() != OrderStatus.DRAFT || order.getCheckout() == null) {
            return order;
        }
        try {
            paymentGateway.syncPaymentStatus(order.getCheckout().getUid());
            return orderRepository.findDetailedByUid(order.getUid()).orElse(order);
        } catch (RuntimeException ex) {
            log.warn("[Order] Failed to sync payment status: {}", ex.toString());
            return order;
        }
    }

    /**
     * Django: {@code get_order_by_uid}. Ownership is enforced by the controller (post-port fix; Django had NO ownership
     * check — any authenticated user can read any order by uid.
     */
    @Transactional(readOnly = true)
    public OrderResponseWithInfo getOrderByUid(UUID uid) {
        return mapper.toResponseWithInfo(syncIfDraft(requireOrder(uid)));
    }

    /** Django: {@code get_order_by_uid_not_transfer}. */
    @Transactional(readOnly = true)
    public Order getOrderEntity(UUID uid) {
        return requireOrder(uid);
    }

    private Sort sortFor(OrderFilterRequest filter) {
        Sort.Direction dir = filter != null && filter.ascending() ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(new Sort.Order(dir, "createdAt"), Sort.Order.desc("uid"));
    }

    private Specification<Order> filterSpec(Specification<Order> base, OrderFilterRequest filter) {
        Specification<Order> spec = base;
        if (filter != null) {
            Specification<Order> status = OrderSpecifications.hasStatus(filter.status());
            Specification<Order> search = OrderSpecifications.searchDishName(filter.search());
            if (status != null) {
                spec = spec.and(status);
            }
            if (search != null) {
                spec = spec.and(search);
            }
        }
        return spec;
    }

    /**
     * Django: {@code OrderORM.get_my_orders} role scoping via {@code get_user_role}:
     * CHEF -&gt; orders where chef=user; CUSTOMER -&gt; owner=user; anything else
     * (ADMIN, or — the preserved quirk — a user in NO group) -&gt; every order.
     */
    private List<Order> findMyOrders(CustomUser user, OrderFilterRequest filter) {
        Specification<Order> base = OrderSpecifications.notDraft();
        UserRole role = user.primaryRole();
        if (role == UserRole.CUSTOMER) {
            base = base.and(OrderSpecifications.ownedBy(user));
        } else if (role == UserRole.CHEF) {
            base = base.and(OrderSpecifications.chefIs(user));
        }
        return orderRepository.findAll(filterSpec(base, filter), sortFor(filter));
    }

    private List<OrderListResponse> wrapOnePerOrder(List<Order> orders) {
        List<OrderListResponse> result = new ArrayList<>();
        for (Order order : orders) {
            Order synced = syncIfDraft(order);
            if (synced.getStatus() == OrderStatus.DRAFT) {
                continue;
            }
            ChefInfoResponse chefInfo = new ChefInfoResponse(
                    synced.getChef() != null ? synced.getChef().getId() : null,
                    synced.getChef() != null ? OrderMapper.fullName(synced.getChef()) : "Unknown");
            result.add(new OrderListResponse(chefInfo, List.of(mapper.toResponseWithInfo(synced))));
        }
        return result;
    }

    /** Django: {@code get_my_orders} (GET /api/orders/). */
    @Transactional(readOnly = true)
    public List<OrderListResponse> getMyOrders(CustomUser user, OrderFilterRequest filter) {
        return wrapOnePerOrder(findMyOrders(user, filter));
    }

    /** Django: {@code get_customer_orders} (GET /api/orders/customer) — always owner-scoped. */
    @Transactional(readOnly = true)
    public List<OrderListResponse> getCustomerOrders(CustomUser user, OrderFilterRequest filter) {
        Specification<Order> base = OrderSpecifications.notDraft().and(OrderSpecifications.ownedBy(user));
        return wrapOnePerOrder(orderRepository.findAll(filterSpec(base, filter), sortFor(filter)));
    }

    /**
     * Django: {@code get_all_orders_of_chef} — which is literally {@code get_my_orders(user=chef)},
     * so it inherits the same role scoping (a CUSTOMER calling the chef endpoint sees their
     * own customer orders; an ADMIN sees all). No per-order wrapper here.
     */
    @Transactional(readOnly = true)
    public List<OrderResponseWithInfo> getAllOrdersOfChef(CustomUser chef, OrderFilterRequest filter) {
        return findMyOrders(chef, filter).stream().map(this::syncIfDraft).map(mapper::toResponseWithInfo).toList();
    }

    // =====================================================================
    // Chef ownership (Django: inline in order/api.py::ChefOrderController)
    // =====================================================================

    /**
     * Django: {@code order_obj = get_order_by_uid(uid); if not order_obj: raise
     * OrderNotFoundException; if order_obj.chef != request.user: raise HttpError(403, msg)}.
     * Strict: no ADMIN bypass in Django.
     */
    @Transactional(readOnly = true)
    public void assertChefOwns(UUID orderUid, CustomUser user, String forbiddenMessage) {
        Order order = requireOrder(orderUid);
        if (order.getChef() == null || !Objects.equals(order.getChef().getId(), user.getId())) {
            throw new OrderHttpException(HttpStatus.FORBIDDEN, forbiddenMessage);
        }
    }

    // =====================================================================
    // Ownership guards (post-port decision 2026-09-25: fix Django's missing checks)
    // =====================================================================

    private static boolean sameUser(CustomUser a, CustomUser b) {
        return a != null && b != null && Objects.equals(a.getId(), b.getId());
    }

    /** Read access: the order's customer, the order's chef, or ADMIN. Unknown uid -&gt; 404 first. */
    @Transactional(readOnly = true)
    public void assertCanReadOrder(UUID orderUid, CustomUser user) {
        Order order = requireOrder(orderUid);
        if (!(user.isAdmin() || sameUser(order.getOwner(), user) || sameUser(order.getChef(), user))) {
            throw new OrderHttpException(HttpStatus.FORBIDDEN, "You don't have permission to view this order");
        }
    }

    /** Customer-side order action (cancel, apply voucher): only the owning customer. */
    @Transactional(readOnly = true)
    public void assertOwnsOrder(UUID orderUid, CustomUser user, String forbiddenMessage) {
        Order order = requireOrder(orderUid);
        if (!sameUser(order.getOwner(), user)) {
            throw new OrderHttpException(HttpStatus.FORBIDDEN, forbiddenMessage);
        }
    }

    /** Checkout edit/place/voucher: only the customer who owns the checkout. Unknown uid -&gt; 404 first. */
    @Transactional(readOnly = true)
    public void assertOwnsCheckout(UUID checkoutUid, CustomUser user) {
        List<Order> orders = requireCheckoutOrders(checkoutUid);
        if (!sameUser(orders.get(0).getCheckout().getOwner(), user)) {
            throw new OrderHttpException(HttpStatus.FORBIDDEN, "You don't have permission to modify this checkout");
        }
    }

    // =====================================================================
    // State machine: confirm / processing / delivering / complete / cancel
    // =====================================================================

    private static void requireValidTransition(OrderStatus from, OrderStatus to) {
        ValidationOutcome outcome = OrderStateMachine.validateTransition(from, to);
        if (!outcome.valid()) {
            // Django: raise ValueError(error_msg) -> generic 500 CONTACT_ADMIN_FOR_SUPPORT.
            throw new IllegalStateException(outcome.errorMessage());
        }
    }

    /**
     * Django: {@code chef_confirm_order} (atomic). COD must be PENDING; PayOS must be
     * CONFIRMED_SYSTEM (paid) — then the generic transition check to CONFIRMED_SHOP.
     * Touches no stock (COD holds were already confirmed at place-order; PayOS holds at
     * the webhook).
     */
    @Transactional
    public OrderResponseWithInfo chefConfirmOrder(UUID orderUid) {
        Order order = requireOrder(orderUid);
        PaymentMethod method = order.getCheckout().getPaymentMethod();
        if (method == PaymentMethod.COD && order.getStatus() != OrderStatus.PENDING) {
            throw new IllegalStateException("COD order can only be confirmed at PENDING status. Current status: "
                    + order.getStatus());
        }
        if (method == PaymentMethod.PAYOS && order.getStatus() != OrderStatus.CONFIRMED_SYSTEM) {
            throw new IllegalStateException("PayOS order can only be confirmed at CONFIRMED_SYSTEM status "
                    + "(after payment). Current status: " + order.getStatus() + ". Please wait for payment confirmation.");
        }
        requireValidTransition(order.getStatus(), OrderStatus.CONFIRMED_SHOP);
        order.setStatus(OrderStatus.CONFIRMED_SHOP);
        orderRepository.save(order);
        return mapper.toResponseWithInfo(order);
    }

    /** Django: {@code ChefOrderController.start_processing} (inline in api.py). */
    @Transactional
    public OrderResponseWithInfo startProcessing(UUID orderUid) {
        return simpleTransition(orderUid, OrderStatus.PROCESSING);
    }

    /** Django: {@code ChefOrderController.start_delivery} (inline in api.py). */
    @Transactional
    public OrderResponseWithInfo startDelivery(UUID orderUid) {
        return simpleTransition(orderUid, OrderStatus.DELIVERING);
    }

    private OrderResponseWithInfo simpleTransition(UUID orderUid, OrderStatus target) {
        Order order = requireOrder(orderUid);
        requireValidTransition(order.getStatus(), target);
        order.setStatus(target);
        orderRepository.save(order);
        return mapper.toResponseWithInfo(order);
    }

    /**
     * Django: {@code complete_order_with_release} (atomic): DELIVERING -&gt; COMPLETED,
     * then (via {@link OrderPaymentGateway}) the settlement record (10% platform fee) and,
     * for PayOS, the escrow release into the chef's wallet / for COD the payment -&gt;
     * SUCCESS. Settlement errors are logged, never abort completion (Django try/except).
     * PORT-NOTE: {@code recommendation.sync_order_meal_logs} on-commit hook omitted
     * (recommendation not ported).
     */
    @Transactional
    public OrderResponseWithInfo completeOrderWithRelease(UUID orderUid) {
        Order order = requireOrder(orderUid);
        requireValidTransition(order.getStatus(), OrderStatus.COMPLETED);
        order.setStatus(OrderStatus.COMPLETED);
        orderRepository.save(order);

        BigDecimal chefPayout = BigDecimal.ZERO;
        try {
            BigDecimal payout = paymentGateway.createSettlementRecord(orderUid, order.getChef()).chefPayoutAmount();
            chefPayout = payout == null ? BigDecimal.ZERO : payout;
        } catch (RuntimeException ex) {
            log.warn("[Order] Warning settlement error: {}", ex.toString());
        }
        // Django writes the payment transaction's state, not order.payment_status.
        paymentGateway.settleCompletedOrder(order, chefPayout);
        return mapper.toResponseWithInfo(order);
    }

    /**
     * Django: {@code cancel_order} (atomic). Permission by actor:
     * <ul>
     *   <li>{@code "customer"}: only DRAFT/PENDING/CONFIRMED_SYSTEM
     *       ({@link OrderStateMachine#canCustomerCancel});</li>
     *   <li>{@code "chef"}: only PENDING/CONFIRMED_SYSTEM;</li>
     *   <li>anything else: NO check at all (preserved; no endpoint passes another value).</li>
     * </ul>
     * Then: refund (via gateway, failure only logged) -&gt; status CANCELLED -&gt;
     * {@code release(expired=false)} every item (RESERVED-&gt;RELEASED + Redis credit, or
     * CONFIRMED-&gt;CANCELLED + DishAvailability restored, or no-op if already terminal)
     * -&gt; AppliedVoucher RESERVED/USED -&gt; CANCELLED -&gt; sync payment_status.
     *
     * PORT-NOTE: bypasses {@link OrderStateMachine#validateTransition}, exactly like
     * Django — e.g. a DRAFT order cancelled by the customer is legal via
     * {@code can_customer_cancel} regardless of the transition table.
     */
    @Transactional
    public OrderResponseWithInfo cancelOrder(UUID orderUid, String reason, String cancelledBy) {
        Order order = requireOrder(orderUid);
        if ("customer".equals(cancelledBy)) {
            if (!OrderStateMachine.canCustomerCancel(order.getStatus())) {
                throw new IllegalStateException("Customer cannot cancel order after CONFIRMED_SHOP. Current status: "
                        + order.getStatus());
            }
        } else if ("chef".equals(cancelledBy)) {
            if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.CONFIRMED_SYSTEM) {
                throw new IllegalStateException("Chef can only reject at PENDING or CONFIRMED_SYSTEM. Current status: "
                        + order.getStatus());
            }
        }

        RefundResult refund = paymentGateway.handleOrderCancellationRefund(
                orderUid, reason != null ? reason : "Order cancelled by " + cancelledBy);
        if (!refund.success()) {
            log.warn("[Order] Refund warning: {}", refund.error());
        }

        // Snapshot BEFORE any release(): its bulk UPDATE clears the persistence context.
        LocalDate date = order.getCheckout().getDeliveryDate();
        List<Hold> holds = order.getItems().stream()
                .map(i -> new Hold(i.getId(), orderUid, i.getDish(), date, i.getQuantity()))
                .toList();

        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.saveAndFlush(order);

        releaseAll(holds);
        int cancelledVouchers = appliedVoucherRepository.cancelForOrder(orderUid);
        log.info("[Order] Cancelled {} voucher(s)", cancelledVouchers);

        paymentGateway.currentPaymentStatus(order.getCheckout().getUid()).ifPresent(ps -> {
            order.setPaymentStatus(ps);
            orderRepository.updatePaymentStatus(orderUid, ps);
        });
        return mapper.toResponseWithInfo(order);
    }

    // =====================================================================
    // vouchers
    // =====================================================================

    /**
     * Django: {@code apply_platform_voucher_to_checkout} (atomic): refuse a second
     * RESERVED/USED voucher of the same type on the checkout, delegate the reservation to
     * {@link VoucherService#applyPlatformVoucherReservation}, then re-allocate every
     * order's discounts and the checkout totals from the AppliedVoucher ledger.
     */
    @Transactional
    public CheckoutResponse applyPlatformVoucherToCheckout(CustomUser user, UUID checkoutUid,
                                                           ApplyPlatformVoucherRequest payload) {
        List<Order> orders = requireCheckoutOrders(checkoutUid);
        Checkout checkout = orders.get(0).getCheckout();
        VoucherType type = payload.voucherType();
        if (appliedVoucherRepository.existsByCheckoutUidAndVoucherTypeAndStatusIn(
                checkout.getUid(), type, OrderMapper.ACTIVE_VOUCHER_STATUSES)) {
            throw new VoucherInvalidException("Đã apply " + type + " voucher cho checkout này rồi");
        }
        BigDecimal netSubtotal = voucherService.calculateNetSubtotal(orders.stream()
                .map(o -> new OrderSubtotal(o.getUid(), o.getSubTotal())).toList());
        AppliedVoucher reservation = voucherService.applyPlatformVoucherReservation(
                user, checkout.getUid(), payload.voucherCode(), type,
                new PlatformCheckoutSnapshot(netSubtotal, checkout.getDeliveryFee(), checkout.getSubTotal()));
        log.info("[Voucher] Applied {} voucher {} to checkout {} - Discount: {}",
                type, payload.voucherCode(), checkout.getUid(), reservation.getDiscountAmount());
        recalculateCheckoutTotal(checkout, orders);
        return mapper.toCheckoutResponse(orders);
    }

    /**
     * Django: {@code apply_shop_voucher_to_order} (atomic). Post-port: ownership enforced by the controller (Django: no
     * ownership check — the reservation is charged to the CALLER, on any order.
     */
    @Transactional
    public OrderResponseWithInfo applyShopVoucherToOrder(CustomUser user, UUID orderUid, String voucherCode) {
        Order order = requireOrder(orderUid);
        if (appliedVoucherRepository.existsByOrderUidAndVoucherTypeAndStatusIn(
                orderUid, VoucherType.SHOP_VOUCHER, OrderMapper.ACTIVE_VOUCHER_STATUSES)) {
            throw new VoucherInvalidException("Order đã có shop voucher rồi");
        }
        Voucher voucher = voucherService.getVoucherByCode(voucherCode);
        if (order.getSubTotal().compareTo(voucher.getMinOrderAmount()) < 0) {
            throw new VoucherInvalidException("Giá trị của đơn hàng không đủ điều kiện áp voucher. "
                    + "Yêu cầu tối thiểu: " + voucher.getMinOrderAmount()
                    + ", Giá trị hiện tại: " + order.getSubTotal());
        }
        // checkoutUidHint = order.checkout: Django's create_voucher_reservation derives it, and
        // _recalculate_order_total filters AppliedVoucher by checkout — without it the shop
        // discount would never be counted.
        voucherService.applyShopVoucherReservation(user, orderUid, order.getCheckout().getUid(),
                order.getChef(), order.getSubTotal(), voucherCode);

        Checkout checkout = order.getCheckout();
        List<Order> siblings = orderRepository.findDetailedByCheckoutUid(checkout.getUid());
        recalculateCheckoutTotal(checkout, siblings);
        Order refreshed = siblings.stream().filter(o -> o.getUid().equals(orderUid)).findFirst().orElse(order);
        // Django re-sums and saves the checkout a second time here (idempotent).
        checkout.setTotalPrice(siblings.stream().map(Order::getTotalPrice).reduce(BigDecimal.ZERO, BigDecimal::add));
        checkout.setTotalDiscount(siblings.stream().map(Order::getTotalDiscount).reduce(BigDecimal.ZERO, BigDecimal::add));
        checkoutRepository.save(checkout);
        return mapper.toResponseWithInfo(refreshed);
    }

    /**
     * Django: {@code _recalculate_order_total}. Never recomputes a voucher discount —
     * reads {@code AppliedVoucher.discount_amount}. Shop discount first (it lowers the
     * net subtotal); platform SUBTOTAL discount allocated pro-rata by net subtotal;
     * platform SHIPPING discount pro-rata by delivery fee; each allocation quantized to
     * 0.01 (Python {@code quantize}, ROUND_HALF_EVEN).
     */
    void recalculateOrderTotal(Order order, List<Order> orders, List<AppliedVoucher> applied) {
        BigDecimal platformSubtotalDiscount = BigDecimal.ZERO;
        BigDecimal platformShippingDiscount = BigDecimal.ZERO;
        Map<UUID, BigDecimal> shopDiscountMap = new HashMap<>();
        for (AppliedVoucher av : applied) {
            if (av.getVoucherType() == VoucherType.PLATFORM_SUBTOTAL) {
                platformSubtotalDiscount = platformSubtotalDiscount.add(av.getDiscountAmount());
            } else if (av.getVoucherType() == VoucherType.PLATFORM_SHIPPING) {
                platformShippingDiscount = platformShippingDiscount.add(av.getDiscountAmount());
            } else if (av.getVoucherType() == VoucherType.SHOP_VOUCHER && av.getOrderUid() != null) {
                shopDiscountMap.merge(av.getOrderUid(), av.getDiscountAmount(), BigDecimal::add);
            }
        }

        Map<UUID, BigDecimal> netSubtotalMap = new HashMap<>();
        BigDecimal totalNetSubtotal = BigDecimal.ZERO;
        for (Order o : orders) {
            BigDecimal net = o.getSubTotal().subtract(shopDiscountMap.getOrDefault(o.getUid(), BigDecimal.ZERO))
                    .max(BigDecimal.ZERO);
            netSubtotalMap.put(o.getUid(), net);
            totalNetSubtotal = totalNetSubtotal.add(net);
        }
        BigDecimal totalDeliveryFee = orders.stream().map(Order::getDeliveryFee).reduce(BigDecimal.ZERO, BigDecimal::add);

        order.setShopDiscount(shopDiscountMap.getOrDefault(order.getUid(), BigDecimal.ZERO));
        if (totalNetSubtotal.signum() > 0) {
            order.setPlatformSubtotalDiscount(platformSubtotalDiscount
                    .multiply(netSubtotalMap.get(order.getUid()))
                    .divide(totalNetSubtotal, MathContext.DECIMAL128)
                    .setScale(2, RoundingMode.HALF_EVEN));
        } else {
            order.setPlatformSubtotalDiscount(BigDecimal.ZERO);
        }
        if (totalDeliveryFee.signum() > 0) {
            order.setPlatformShippingDiscount(platformShippingDiscount
                    .multiply(order.getDeliveryFee())
                    .divide(totalDeliveryFee, MathContext.DECIMAL128)
                    .setScale(2, RoundingMode.HALF_EVEN));
        } else {
            order.setPlatformShippingDiscount(BigDecimal.ZERO);
        }
        order.setTotalDiscount(order.getShopDiscount()
                .add(order.getPlatformSubtotalDiscount())
                .add(order.getPlatformShippingDiscount()));
        order.setTotalPrice(order.getSubTotal().add(order.getTaxAndFees()).add(order.getDeliveryFee())
                .subtract(order.getTotalDiscount()));
        orderRepository.save(order);
    }

    /** Django: {@code _recalculate_checkout_total}. */
    void recalculateCheckoutTotal(Checkout checkout, List<Order> orders) {
        List<AppliedVoucher> applied = appliedVoucherRepository.findByCheckoutUidAndStatusIn(
                checkout.getUid(), OrderMapper.ACTIVE_VOUCHER_STATUSES);
        BigDecimal totalPrice = BigDecimal.ZERO;
        BigDecimal totalDiscount = BigDecimal.ZERO;
        for (Order order : orders) {
            recalculateOrderTotal(order, orders, applied);
            totalPrice = totalPrice.add(order.getTotalPrice());
            totalDiscount = totalDiscount.add(order.getTotalDiscount());
        }
        checkout.setTotalPrice(totalPrice);
        checkout.setTotalDiscount(totalDiscount);
        checkoutRepository.save(checkout);
    }

    /** Django: {@code check_dish_in_order} (used by review, not by any order endpoint). */
    @Transactional(readOnly = true)
    public boolean checkDishInOrder(UUID orderUid, UUID dishUid) {
        if (!orderItemRepository.existsByOrderUidAndDishUid(orderUid, dishUid)) {
            throw new DishNotFoundInOrderException();
        }
        return true;
    }
}
