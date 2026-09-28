package com.amomeal.marketplace.admin.repository;

import com.amomeal.marketplace.payment.entity.CustomerPaymentInfo;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * Django: {@code AdminService.get_customers_bank_accounts}. payment's {@link CustomerPaymentInfo} keeps
 * the owner as a plain {@code user_id} column (no association), so the join to the user (for the email /
 * username / name search and the response) is a theta join here.
 */
@Repository
public class AdminCustomerBankQueries {

    public record Row(CustomerPaymentInfo info, CustomUser user) {
    }

    public record PageResult(List<Row> rows, long total) {
    }

    @PersistenceContext
    private EntityManager em;

    private static String like(String search) {
        String escaped = search.toLowerCase().replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }

    private static String where(Boolean status, String search) {
        StringBuilder sb = new StringBuilder(" from CustomerPaymentInfo b, CustomUser u where u.id = b.userId");
        if (status != null) {
            sb.append(" and b.verified = :status");
        }
        if (search != null && !search.isEmpty()) {
            sb.append(" and (lower(u.email) like :q escape '!' or lower(u.username) like :q escape '!'"
                    + " or lower(u.firstName) like :q escape '!' or lower(u.lastName) like :q escape '!')");
        }
        return sb.toString();
    }

    private static <T> TypedQuery<T> bind(TypedQuery<T> q, Boolean status, String search) {
        if (status != null) {
            q.setParameter("status", status);
        }
        if (search != null && !search.isEmpty()) {
            q.setParameter("q", like(search));
        }
        return q;
    }

    /** {@code offset}/{@code limit} are already validated by the caller. */
    public PageResult page(Boolean status, String search, int offset, int limit) {
        String where = where(status, search);
        long total = bind(em.createQuery("select count(b)" + where, Long.class), status, search).getSingleResult();
        List<Object[]> raw = bind(em.createQuery("select b, u" + where + " order by b.createdAt desc, b.id desc",
                Object[].class), status, search).setFirstResult(offset).setMaxResults(limit).getResultList();
        List<Row> rows = new ArrayList<>();
        for (Object[] r : raw) {
            rows.add(new Row((CustomerPaymentInfo) r[0], (CustomUser) r[1]));
        }
        return new PageResult(rows, total);
    }
}
