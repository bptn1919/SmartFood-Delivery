package com.amomeal.marketplace.admin.repository;

import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.order.entity.PaymentStatus;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.voucher.entity.VoucherReservationStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mirrors ../../backend/admin/queries.py::Query (the aggregation half). Read-only JPQL over the other
 * modules' entities; the one native query (revenue per day) uses {@code AT TIME ZONE 'UTC'} so the day
 * bucketing is Django's {@code TruncDate} under {@code TIME_ZONE=UTC} regardless of the DB session zone
 * (see the recommendation module's note on {@code jdbc.time_zone}).
 */
@Repository
public class AdminDashboardQueries {

    /** Django: {@code PaymentStatus.SUCCESS / HOLDING / RELEASED} - "money actually collected". */
    public static final List<PaymentStatus> COLLECTED =
            List.of(PaymentStatus.SUCCESS, PaymentStatus.HOLDING, PaymentStatus.RELEASED);

    @PersistenceContext
    private EntityManager em;

    private final JdbcTemplate jdbc;

    public AdminDashboardQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ overview

    public BigDecimal totalRevenue() {
        BigDecimal sum = em.createQuery("select sum(o.totalPrice) from Order o "
                + "where o.status = :s and o.paymentStatus in :ps", BigDecimal.class)
                .setParameter("s", OrderStatus.COMPLETED).setParameter("ps", COLLECTED).getSingleResult();
        return sum == null ? BigDecimal.ZERO : sum;
    }

    public long totalOrders() {
        return em.createQuery("select count(o) from Order o", Long.class).getSingleResult();
    }

    public long cancelledOrders() {
        return em.createQuery("select count(o) from Order o where o.status = :s", Long.class)
                .setParameter("s", OrderStatus.CANCELLED).getSingleResult();
    }

    /** Users in CUSTOMER or CHEF joined at/after {@code since}. */
    public long newUsers(Instant since) {
        return em.createQuery("select count(u) from CustomUser u where u.dateJoined >= :since "
                        + "and (:cust member of u.roles or :chef member of u.roles)", Long.class)
                .setParameter("since", since).setParameter("cust", UserRole.CUSTOMER)
                .setParameter("chef", UserRole.CHEF).getSingleResult();
    }

    /** CHEF-group users that are the chef of at least one order (any status). */
    public long activeChefs() {
        return em.createQuery("select count(u) from CustomUser u where :chef member of u.roles "
                        + "and exists (select 1 from Order o where o.chef = u)", Long.class)
                .setParameter("chef", UserRole.CHEF).getSingleResult();
    }

    // ------------------------------------------------------------------ revenue chart

    public record DailyRevenue(LocalDate date, BigDecimal revenue, long orders) {
    }

    /** Completed + collected orders per UTC day in {@code [from, to]} (inclusive dates), by day. */
    public List<DailyRevenue> dailyRevenue(LocalDate from, LocalDate to) {
        OffsetDateTime lower = from.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime upper = to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        String sql = "select cast(created_at at time zone 'UTC' as date) as d, sum(total_price) as revenue, "
                + "count(uid) as orders from \"order\" "
                + "where status = 'COMPLETED' and payment_status in ('SUCCESS','HOLDING','RELEASED') "
                + "and created_at >= ? and created_at < ? group by d order by d";
        return jdbc.query(sql, (rs, i) -> new DailyRevenue(rs.getObject("d", LocalDate.class),
                rs.getBigDecimal("revenue"), rs.getLong("orders")), lower, upper);
    }

    // ------------------------------------------------------------------ payment methods

    public long collectedTransactionCount() {
        return em.createQuery("select count(s) from PaymentTransactionState s where s.status in :st", Long.class)
                .setParameter("st", COLLECTED).getSingleResult();
    }

    public record PaymentMethodRow(String method, long count, BigDecimal totalAmount) {
    }

    public List<PaymentMethodRow> paymentMethodStats() {
        List<Object[]> rows = em.createQuery("select t.paymentMethod, count(t), sum(t.amount) "
                        + "from PaymentTransaction t, PaymentTransactionState s "
                        + "where s.paymentTransactionId = t.id and s.status in :st "
                        + "group by t.paymentMethod order by count(t) desc, t.paymentMethod", Object[].class)
                .setParameter("st", COLLECTED).getResultList();
        List<PaymentMethodRow> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new PaymentMethodRow(String.valueOf(r[0]), (Long) r[1], (BigDecimal) r[2]));
        }
        return out;
    }

    // ------------------------------------------------------------------ order status

    public record StatusRow(String status, long count) {
    }

    public List<StatusRow> orderStatusStats() {
        List<Object[]> rows = em.createQuery("select o.status, count(o) from Order o group by o.status "
                + "order by count(o) desc, o.status", Object[].class).getResultList();
        List<StatusRow> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new StatusRow(String.valueOf(r[0]), (Long) r[1]));
        }
        return out;
    }

    // ------------------------------------------------------------------ top chefs

    public record TopChefRow(long chefId, String username, String email, String firstName, String lastName,
                             long totalOrders, BigDecimal totalRevenue) {
    }

    public List<TopChefRow> topChefs(int limit) {
        List<Object[]> rows = em.createQuery("select c.id, c.username, c.email, c.firstName, c.lastName, "
                        + "count(o), sum(o.totalPrice) from Order o join o.chef c where o.status = :s "
                        + "group by c.id, c.username, c.email, c.firstName, c.lastName "
                        + "order by count(o) desc, c.id", Object[].class)
                .setParameter("s", OrderStatus.COMPLETED).setMaxResults(limit).getResultList();
        List<TopChefRow> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new TopChefRow((Long) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4],
                    (Long) r[5], (BigDecimal) r[6]));
        }
        return out;
    }

    // ------------------------------------------------------------------ success orders by district

    public record DistrictRow(String district, long count) {
    }

    private static String districtFilter(LocalDate from, LocalDate to) {
        StringBuilder sb = new StringBuilder(" from Order o join o.checkout c join c.deliveryAddress a "
                + "where o.status = :s");
        if (from != null) {
            sb.append(" and o.createdAt >= :from");
        }
        if (to != null) {
            sb.append(" and o.createdAt < :to");
        }
        return sb.toString();
    }

    private <T> TypedQuery<T> bind(TypedQuery<T> q, LocalDate from, LocalDate to) {
        q.setParameter("s", OrderStatus.COMPLETED);
        if (from != null) {
            q.setParameter("from", from.atStartOfDay().toInstant(ZoneOffset.UTC));
        }
        if (to != null) {
            q.setParameter("to", to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        }
        return q;
    }

    public long completedWithAddressCount(LocalDate from, LocalDate to) {
        return bind(em.createQuery("select count(o)" + districtFilter(from, to), Long.class), from, to)
                .getSingleResult();
    }

    public List<DistrictRow> successOrdersByDistrict(LocalDate from, LocalDate to) {
        List<Object[]> rows = bind(em.createQuery("select a.district, count(o)" + districtFilter(from, to)
                + " group by a.district order by count(o) desc, a.district", Object[].class), from, to)
                .getResultList();
        List<DistrictRow> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new DistrictRow((String) r[0], (Long) r[1]));
        }
        return out;
    }

    // ------------------------------------------------------------------ order voucher codes

    /**
     * Django resolve_voucher_code: comma-joined codes of the order's RESERVED/USED applied vouchers
     * (id order). Orders without any are absent from the map (= null in the response).
     */
    public Map<UUID, String> voucherCodesByOrder(Collection<UUID> orderUids) {
        Map<UUID, String> out = new HashMap<>();
        if (orderUids.isEmpty()) {
            return out;
        }
        List<Object[]> rows = em.createQuery("select a.orderUid, v.code from AppliedVoucher a join a.voucher v "
                        + "where a.orderUid in :uids and a.status in :st order by a.id", Object[].class)
                .setParameter("uids", orderUids)
                .setParameter("st", List.of(VoucherReservationStatus.RESERVED, VoucherReservationStatus.USED))
                .getResultList();
        for (Object[] r : rows) {
            out.merge((UUID) r[0], (String) r[1], (a, b) -> a + ", " + b);
        }
        return out;
    }
}
