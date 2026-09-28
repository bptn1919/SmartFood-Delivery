package com.amomeal.marketplace.order.service;

import com.amomeal.marketplace.cart.entity.Cart;
import com.amomeal.marketplace.cart.entity.CartItem;
import com.amomeal.marketplace.cart.exception.CartItemNotFoundException;
import com.amomeal.marketplace.cart.service.CartService;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.exception.StockReservationException;
import com.amomeal.marketplace.dish.service.StockReservationService;
import com.amomeal.marketplace.order.dto.CheckoutResponse;
import com.amomeal.marketplace.order.entity.Checkout;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderItem;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.order.entity.PaymentMethod;
import com.amomeal.marketplace.order.exception.OrderNotFoundException;
import com.amomeal.marketplace.order.repository.CheckoutRepository;
import com.amomeal.marketplace.order.repository.OrderAppliedVoucherRepository;
import com.amomeal.marketplace.order.repository.OrderItemRepository;
import com.amomeal.marketplace.order.repository.OrderRepository;
import com.amomeal.marketplace.order.service.OrderPaymentGateway.PaymentSession;
import com.amomeal.marketplace.order.service.OrderPaymentGateway.RefundResult;
import com.amomeal.marketplace.profile.entity.CustomerAddress;
import com.amomeal.marketplace.profile.exception.CustomerAddressNotFoundException;
import com.amomeal.marketplace.profile.repository.ChefPaymentInfoRepository;
import com.amomeal.marketplace.profile.repository.ChefProfileRepository;
import com.amomeal.marketplace.profile.repository.CustomerAddressRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.voucher.entity.AppliedVoucher;
import com.amomeal.marketplace.voucher.entity.VoucherReservationStatus;
import com.amomeal.marketplace.voucher.entity.VoucherType;
import com.amomeal.marketplace.voucher.service.VoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OrderService}'s ordering/compensation logic — the
 * money/inventory-critical sequencing of place_order and cancel_order — with
 * every collaborator mocked so the exact call order is observable. The same
 * flows run end-to-end against real Postgres + Redis in {@code OrderControllerTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceTest {

    @Mock OrderRepository orderRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock CheckoutRepository checkoutRepository;
    @Mock OrderAppliedVoucherRepository appliedVoucherRepository;
    @Mock CartService cartService;
    @Mock StockReservationService stockReservationService;
    @Mock VoucherService voucherService;
    @Mock CustomerAddressRepository customerAddressRepository;
    @Mock ChefPaymentInfoRepository chefPaymentInfoRepository;
    @Mock ChefProfileRepository chefProfileRepository;
    @Mock OrderPaymentGateway paymentGateway;
    @Mock ShippingFeeEstimator shippingFeeEstimator;
    @Mock OrderMapper mapper;

    @InjectMocks OrderService service;

    private CustomUser customer;
    private CustomUser chef;
    private Checkout checkout;
    private Order order;
    private OrderItem item1;
    private OrderItem item2;
    private Dish dishA;
    private Dish dishB;
    private final LocalDate date = LocalDate.now().plusDays(1);

    @BeforeEach
    void setUp() {
        customer = CustomUser.builder().id(1L).username("cus").email("c@x.test").build();
        chef = CustomUser.builder().id(2L).username("chef").email("h@x.test").build();
        dishA = Dish.builder().uid(UUID.randomUUID()).name("A").price(new BigDecimal("50000")).owner(chef).build();
        dishB = Dish.builder().uid(UUID.randomUUID()).name("B").price(new BigDecimal("70000")).owner(chef).build();
        CustomerAddress address = CustomerAddress.builder().id(9L).user(customer).address("1").street("s")
                .ward("w").district("d").city("c").latitude(10.0).longitude(106.0).build();
        checkout = Checkout.builder().uid(UUID.randomUUID()).fullName("Cus").phoneNumber("09")
                .deliveryDate(date).deliveryTime(LocalTime.NOON).deliveryAddress(address)
                .paymentMethod(PaymentMethod.COD).build();
        order = Order.builder().uid(UUID.randomUUID()).checkout(checkout).owner(customer).chef(chef)
                .status(OrderStatus.DRAFT).subTotal(new BigDecimal("120000")).build();
        item1 = OrderItem.builder().id(11L).order(order).dish(dishA).dishName("A").quantity(2).price(dishA.getPrice()).build();
        item2 = OrderItem.builder().id(12L).order(order).dish(dishB).dishName("B").quantity(1).price(dishB.getPrice()).build();
        order.getItems().add(item1);
        order.getItems().add(item2);

        when(orderRepository.findDetailedByCheckoutUid(checkout.getUid())).thenReturn(List.of(order));
        when(orderRepository.findDetailedByUid(order.getUid())).thenReturn(Optional.of(order));
        when(mapper.toCheckoutResponse(any())).thenReturn(emptyCheckoutResponse());
        when(paymentGateway.handleOrderCancellationRefund(any(), any())).thenReturn(RefundResult.skipped("n/a"));
        when(paymentGateway.createCodPayment(any())).thenReturn(PaymentSession.none());
        when(paymentGateway.createPayosPayment(any(), any()))
                .thenReturn(new PaymentSession(UUID.randomUUID(), "https://pay", "tx", "qr"));
        when(paymentGateway.currentPaymentStatus(any())).thenReturn(Optional.empty());
    }

    private CheckoutResponse emptyCheckoutResponse() {
        return new CheckoutResponse(checkout.getUid(), "Cus", "09", date, LocalTime.NOON, null, PaymentMethod.COD,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, List.of(), null, null, null, null);
    }

    // =====================================================================
    // place_order
    // =====================================================================

    @Test
    void placeOrder_cod_happyPath_runsDjangosExactSequence() {
        service.placeOrder(checkout.getUid(), null);

        InOrder inOrder = inOrder(stockReservationService, appliedVoucherRepository, paymentGateway,
                orderRepository, cartService);
        inOrder.verify(stockReservationService).reserve(11L, order.getUid(), dishA, date, 2);
        inOrder.verify(stockReservationService).reserve(12L, order.getUid(), dishB, date, 1);
        inOrder.verify(appliedVoucherRepository).markReservedAsUsed(checkout.getUid(), List.of(order.getUid()));
        inOrder.verify(paymentGateway).createCodPayment(checkout.getUid());
        inOrder.verify(orderRepository).markPlaced(eq(order.getUid()), eq(OrderStatus.PENDING), eq("Cus"), eq("09"),
                anyString(), eq(10.0), eq(106.0));
        inOrder.verify(stockReservationService).confirm(11L, dishA, date, 2);
        inOrder.verify(stockReservationService).confirm(12L, dishB, date, 1);
        inOrder.verify(cartService).clearSelectedItems(customer);
        verify(stockReservationService, never()).release(anyLong(), any(), any(), anyInt(), anyBoolean());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void placeOrder_secondItemOutOfStock_releasesOnlyTheFirstHold_andWritesNothingElse() {
        doThrow(StockReservationException.insufficientStock("B", date))
                .when(stockReservationService).reserve(eq(12L), any(), any(), any(), anyInt());

        assertThatThrownBy(() -> service.placeOrder(checkout.getUid(), null))
                .isInstanceOf(StockReservationException.class);

        verify(stockReservationService).release(11L, dishA, date, 2, false);
        verify(stockReservationService, never()).release(eq(12L), any(), any(), anyInt(), anyBoolean());
        verify(stockReservationService, never()).confirm(anyLong(), any(), any(), anyInt());
        verifyNoInteractions(appliedVoucherRepository, cartService);
        verify(paymentGateway, never()).createCodPayment(any());
        verify(orderRepository, never()).markPlaced(any(), any(), any(), any(), any(), any(), any());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
    }

    @Test
    void placeOrder_cod_paymentFailure_releasesEveryHold_butVoucherUsedMarkIsNotReverted() {
        when(paymentGateway.createCodPayment(any())).thenThrow(new RuntimeException("gateway down"));

        assertThatThrownBy(() -> service.placeOrder(checkout.getUid(), null)).hasMessage("gateway down");

        verify(stockReservationService).release(11L, dishA, date, 2, false);
        verify(stockReservationService).release(12L, dishB, date, 1, false);
        verify(stockReservationService, never()).confirm(anyLong(), any(), any(), anyInt());
        // PORT-NOTE preserved: vouchers were flipped to USED before the try block, and stay USED.
        verify(appliedVoucherRepository).markReservedAsUsed(any(), any());
        verifyNoMoreInteractions(appliedVoucherRepository);
        verify(cartService, never()).clearSelectedItems(any());
    }

    @Test
    void placeOrder_cod_failureAfterPartialConfirm_releasesAll_andRevertsPendingToDraft() {
        when(stockReservationService.confirm(eq(12L), any(), any(), anyInt()))
                .thenThrow(new RuntimeException("db hiccup"));

        assertThatThrownBy(() -> service.placeOrder(checkout.getUid(), null)).hasMessage("db hiccup");

        // release(expired=false) on the already-CONFIRMED item 11 is what restores its
        // DishAvailability (CONFIRMED -> CANCELLED branch inside dish's release()).
        verify(stockReservationService).release(11L, dishA, date, 2, false);
        verify(stockReservationService).release(12L, dishB, date, 1, false);
        verify(orderRepository).updateStatus(order.getUid(), OrderStatus.DRAFT);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
    }

    @Test
    void placeOrder_payos_leavesHoldsReserved_andReturnsThePaymentSession() {
        checkout.setPaymentMethod(PaymentMethod.PAYOS);

        CheckoutResponse response = service.placeOrder(checkout.getUid(), "VCB");

        verify(paymentGateway).createPayosPayment(checkout.getUid(), "VCB");
        verify(stockReservationService, never()).confirm(anyLong(), any(), any(), anyInt());
        verify(appliedVoucherRepository, never()).markReservedAsUsed(any(), any());
        verify(cartService).clearSelectedItems(customer);
        assertThat(response.paymentUrl()).isEqualTo("https://pay");
        assertThat(response.qrCode()).isEqualTo("qr");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void placeOrder_payos_failureAfterSessionCreated_releasesHolds_revertsOrders_andCancelsTheSession() {
        checkout.setPaymentMethod(PaymentMethod.PAYOS);
        doThrow(new RuntimeException("cart boom")).when(cartService).clearSelectedItems(any());

        assertThatThrownBy(() -> service.placeOrder(checkout.getUid(), null)).hasMessage("cart boom");

        verify(stockReservationService).release(11L, dishA, date, 2, false);
        verify(stockReservationService).release(12L, dishB, date, 1, false);
        verify(orderRepository).updateStatus(order.getUid(), OrderStatus.DRAFT);
        verify(paymentGateway).cancelPayment(any(), eq("Order placement failed after payment session creation"));
    }

    @Test
    void placeOrder_unknownCheckout_isOrderNotFound() {
        UUID missing = UUID.randomUUID();
        when(orderRepository.findDetailedByCheckoutUid(missing)).thenReturn(List.of());
        assertThatThrownBy(() -> service.placeOrder(missing, null)).isInstanceOf(OrderNotFoundException.class);
        verifyNoInteractions(stockReservationService);
    }

    @Test
    void placeOrder_checkoutWithoutAddress_isA500ValueError_beforeAnyReservation() {
        checkout.setDeliveryAddress(null);
        assertThatThrownBy(() -> service.placeOrder(checkout.getUid(), null))
                .isInstanceOf(IllegalStateException.class).hasMessage("Checkout missing delivery address");
        verifyNoInteractions(stockReservationService);
    }

    // =====================================================================
    // cancel_order
    // =====================================================================

    @Test
    void cancelOrder_runsRefundThenStatusThenReleaseThenVouchers() {
        order.setStatus(OrderStatus.PENDING);

        service.cancelOrder(order.getUid(), null, "customer");

        InOrder inOrder = inOrder(paymentGateway, orderRepository, stockReservationService, appliedVoucherRepository);
        inOrder.verify(paymentGateway).handleOrderCancellationRefund(order.getUid(), "Order cancelled by customer");
        inOrder.verify(orderRepository).saveAndFlush(order);
        inOrder.verify(stockReservationService).release(11L, dishA, date, 2, false);
        inOrder.verify(stockReservationService).release(12L, dishB, date, 1, false);
        inOrder.verify(appliedVoucherRepository).cancelForOrder(order.getUid());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancelOrder_customerAfterChefAccepted_isRefused_withNoSideEffects() {
        order.setStatus(OrderStatus.CONFIRMED_SHOP);
        assertThatThrownBy(() -> service.cancelOrder(order.getUid(), "x", "customer"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Customer cannot cancel order after CONFIRMED_SHOP");
        verifyNoInteractions(stockReservationService, appliedVoucherRepository);
        verify(paymentGateway, never()).handleOrderCancellationRefund(any(), any());
    }

    @Test
    void cancelOrder_chefOnlyAtPendingOrConfirmedSystem() {
        order.setStatus(OrderStatus.DRAFT);
        assertThatThrownBy(() -> service.cancelOrder(order.getUid(), null, "chef"))
                .hasMessageContaining("Chef can only reject at PENDING or CONFIRMED_SYSTEM");
        order.setStatus(OrderStatus.CONFIRMED_SYSTEM);
        service.cancelOrder(order.getUid(), null, "chef");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancelOrder_otherActor_hasNoPermissionCheckAtAll_djangoQuirk() {
        order.setStatus(OrderStatus.DELIVERING);
        service.cancelOrder(order.getUid(), null, "system");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancelOrder_refundFailure_isOnlyLogged_cancellationProceeds() {
        order.setStatus(OrderStatus.PENDING);
        when(paymentGateway.handleOrderCancellationRefund(any(), any())).thenReturn(new RefundResult(false, "nope"));
        service.cancelOrder(order.getUid(), "r", "customer");
        verify(stockReservationService, times(2)).release(anyLong(), any(), any(), anyInt(), eq(false));
    }

    // =====================================================================
    // chef lifecycle
    // =====================================================================

    @Test
    void chefConfirm_codRequiresPending_payosRequiresConfirmedSystem() {
        order.setStatus(OrderStatus.DRAFT);
        assertThatThrownBy(() -> service.chefConfirmOrder(order.getUid()))
                .hasMessageStartingWith("COD order can only be confirmed at PENDING status");

        checkout.setPaymentMethod(PaymentMethod.PAYOS);
        order.setStatus(OrderStatus.PENDING);
        assertThatThrownBy(() -> service.chefConfirmOrder(order.getUid()))
                .hasMessageStartingWith("PayOS order can only be confirmed at CONFIRMED_SYSTEM status");

        order.setStatus(OrderStatus.CONFIRMED_SYSTEM);
        service.chefConfirmOrder(order.getUid());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED_SHOP);
        verifyNoInteractions(stockReservationService);
    }

    @Test
    void processingDeliveringCompleting_followTheStateMachine() {
        order.setStatus(OrderStatus.PENDING);
        assertThatThrownBy(() -> service.startProcessing(order.getUid()))
                .hasMessageStartingWith("Cannot transition from PENDING to PROCESSING");
        order.setStatus(OrderStatus.CONFIRMED_SHOP);
        service.startProcessing(order.getUid());
        service.startDelivery(order.getUid());
        when(paymentGateway.createSettlementRecord(any(), any()))
                .thenReturn(new OrderPaymentGateway.SettlementResult(new BigDecimal("108000")));
        service.completeOrderWithRelease(order.getUid());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        verify(paymentGateway).settleCompletedOrder(order, new BigDecimal("108000"));
        assertThatThrownBy(() -> service.completeOrderWithRelease(order.getUid()))
                .hasMessage("Order is already in COMPLETED status");
    }

    @Test
    void complete_settlementError_isLogged_andCompletionStillHappens() {
        order.setStatus(OrderStatus.DELIVERING);
        when(paymentGateway.createSettlementRecord(any(), any())).thenThrow(new RuntimeException("ledger"));
        service.completeOrderWithRelease(order.getUid());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        verify(paymentGateway).settleCompletedOrder(order, BigDecimal.ZERO);
    }

    // =====================================================================
    // checkout()
    // =====================================================================

    @Test
    void checkout_noSelectedItems_isCartItemNotFound() {
        when(cartService.getSelectedCartItemsByUser(customer)).thenReturn(List.of());
        assertThatThrownBy(() -> service.checkout(customer)).isInstanceOf(CartItemNotFoundException.class);
    }

    @Test
    void checkout_noAddress_isCustomerAddressNotFound() {
        Cart cart = Cart.builder().owner(customer).build();
        when(cartService.getSelectedCartItemsByUser(customer)).thenReturn(List.of(
                CartItem.builder().cart(cart).dish(dishA).deliveryDate(date).quantity(1).selected(true).build()));
        when(cartService.getOrCreateCart(customer)).thenReturn(cart);
        when(cartService.getDeliveryDatesOfSelectedItems(cart)).thenReturn(Optional.of(date));
        when(customerAddressRepository.findAllByUser(customer)).thenReturn(List.of());
        assertThatThrownBy(() -> service.checkout(customer)).isInstanceOf(CustomerAddressNotFoundException.class);
        verify(checkoutRepository, never()).save(any());
    }

    @Test
    void checkout_groupsByChef_snapshotsPrices_andAppliesTaxAndDeliveryThresholds() {
        CustomUser chef2 = CustomUser.builder().id(3L).username("chef2").build();
        Dish dishC = Dish.builder().uid(UUID.randomUUID()).name("C").price(new BigDecimal("100000")).owner(chef2).build();
        Cart cart = Cart.builder().owner(customer).build();
        when(cartService.getSelectedCartItemsByUser(customer)).thenReturn(List.of(
                CartItem.builder().cart(cart).dish(dishA).deliveryDate(date).quantity(1).selected(true).build(),
                CartItem.builder().cart(cart).dish(dishC).deliveryDate(date).quantity(2).selected(true).build(),
                CartItem.builder().cart(cart).dish(dishB).deliveryDate(date).quantity(1).selected(true).build()));
        when(cartService.getOrCreateCart(customer)).thenReturn(cart);
        when(cartService.getDeliveryDatesOfSelectedItems(cart)).thenReturn(Optional.of(date));
        CustomerAddress addr = CustomerAddress.builder().id(5L).user(customer).build();
        when(customerAddressRepository.findAllByUser(customer)).thenReturn(List.of(addr));
        when(orderRepository.findByOwnerAndStatus(customer, OrderStatus.DRAFT)).thenReturn(List.of());
        when(checkoutRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.checkout(customer);

        org.mockito.ArgumentCaptor<List<Order>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(mapper).toCheckoutResponse(captor.capture());
        List<Order> orders = captor.getValue();
        assertThat(orders).hasSize(2);
        Order chef1Order = orders.get(0); // first-seen chef first
        assertThat(chef1Order.getChef()).isSameAs(chef);
        assertThat(chef1Order.getSubTotal()).isEqualByComparingTo("120000");
        assertThat(chef1Order.getTaxAndFees()).isEqualByComparingTo("12000");
        assertThat(chef1Order.getDeliveryFee()).isEqualByComparingTo("30000"); // < 200k
        assertThat(chef1Order.getTotalPrice()).isEqualByComparingTo("162000");
        assertThat(chef1Order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        Order chef2Order = orders.get(1);
        assertThat(chef2Order.getSubTotal()).isEqualByComparingTo("200000");
        assertThat(chef2Order.getDeliveryFee()).isEqualByComparingTo("15000"); // >= 200k
        assertThat(chef2Order.getItems()).singleElement().satisfies(i -> {
            assertThat(i.getPrice()).isEqualByComparingTo("100000");
            assertThat(i.getQuantity()).isEqualTo(2);
        });
        assertThat(addr.isSelected()).isTrue();
        // checkout() touches no stock and no voucher reservation
        verifyNoInteractions(stockReservationService, voucherService);
    }

    // =====================================================================
    // _recalculate_order_total
    // =====================================================================

    @Test
    void recalculate_shopFirst_thenPlatformSubtotalProRataByNet_thenShippingProRataByFee() {
        Order o1 = Order.builder().uid(UUID.randomUUID()).subTotal(new BigDecimal("100000"))
                .taxAndFees(new BigDecimal("10000")).deliveryFee(new BigDecimal("30000")).build();
        Order o2 = Order.builder().uid(UUID.randomUUID()).subTotal(new BigDecimal("200000"))
                .taxAndFees(new BigDecimal("20000")).deliveryFee(new BigDecimal("15000")).build();
        List<AppliedVoucher> applied = List.of(
                AppliedVoucher.builder().voucherType(VoucherType.SHOP_VOUCHER).orderUid(o2.getUid())
                        .discountAmount(new BigDecimal("50000")).status(VoucherReservationStatus.RESERVED).build(),
                AppliedVoucher.builder().voucherType(VoucherType.PLATFORM_SUBTOTAL)
                        .discountAmount(new BigDecimal("25000")).status(VoucherReservationStatus.RESERVED).build(),
                AppliedVoucher.builder().voucherType(VoucherType.PLATFORM_SHIPPING)
                        .discountAmount(new BigDecimal("10000")).status(VoucherReservationStatus.USED).build());
        List<Order> orders = List.of(o1, o2);

        service.recalculateOrderTotal(o1, orders, applied);
        service.recalculateOrderTotal(o2, orders, applied);

        // net: o1 = 100k, o2 = 200k - 50k = 150k, total 250k
        assertThat(o1.getShopDiscount()).isEqualByComparingTo("0");
        assertThat(o1.getPlatformSubtotalDiscount()).isEqualByComparingTo("10000.00");  // 25k * 100/250
        assertThat(o2.getPlatformSubtotalDiscount()).isEqualByComparingTo("15000.00");  // 25k * 150/250
        // shipping by fee: 10k * 30/45 = 6666.67, 10k * 15/45 = 3333.33
        assertThat(o1.getPlatformShippingDiscount()).isEqualByComparingTo("6666.67");
        assertThat(o2.getPlatformShippingDiscount()).isEqualByComparingTo("3333.33");
        assertThat(o2.getShopDiscount()).isEqualByComparingTo("50000");
        assertThat(o2.getTotalDiscount()).isEqualByComparingTo("68333.33");
        assertThat(o2.getTotalPrice()).isEqualByComparingTo("166666.67"); // 200k+20k+15k-68333.33
        assertThat(o1.getTotalPrice()).isEqualByComparingTo("123333.33"); // 140k - 16666.67
    }

    @Test
    void recalculate_allZeroFees_andSubtotals_noDivisionByZero() {
        Order o = Order.builder().uid(UUID.randomUUID()).subTotal(BigDecimal.ZERO)
                .taxAndFees(BigDecimal.ZERO).deliveryFee(BigDecimal.ZERO).build();
        service.recalculateOrderTotal(o, List.of(o), List.of(AppliedVoucher.builder()
                .voucherType(VoucherType.PLATFORM_SUBTOTAL).discountAmount(BigDecimal.TEN).build()));
        assertThat(o.getPlatformSubtotalDiscount()).isEqualByComparingTo("0");
        assertThat(o.getPlatformShippingDiscount()).isEqualByComparingTo("0");
    }
}
