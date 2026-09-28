package com.amomeal.marketplace.report.repository;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.report.entity.ChefReport;
import com.amomeal.marketplace.report.entity.ReportCategory;
import com.amomeal.marketplace.report.entity.ReportStatus;
import com.amomeal.marketplace.users.entity.CustomUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Mirrors ../../backend/report/queries.py (report half) + the ChefReport.objects.* calls in the services. */
public interface ChefReportRepository extends JpaRepository<ChefReport, Long>, JpaSpecificationExecutor<ChefReport> {

    Optional<ChefReport> findByUid(UUID uid);

    /** Django: check_customer_rate_limit's count (created_at >= cutoff, deleted=False). */
    long countByReporterIdAndCreatedAtGreaterThanEqualAndDeletedFalse(Long reporterId, Instant cutoff);

    /**
     * Django: filter(reporter=, order=, dish=).exists(). A null order/dish means IS NULL (Django's
     * filter(order=None) semantics; Spring Data derives IS NULL for a null simple-property argument).
     * No deleted filter, exactly as Django.
     */
    boolean existsByReporterAndOrderAndDish(CustomUser reporter, Order order, Dish dish);

    long countByReporterIdAndStatus(Long reporterId, ReportStatus status);

    long countByReporterIdAndStatusIn(Long reporterId, Collection<ReportStatus> statuses);

    /** Django: get_reports_for_chef. */
    List<ChefReport> findByChefIdAndDeletedFalseOrderByCreatedAtDesc(Long chefId);

    /** Django: get_reports_by_customer. */
    List<ChefReport> findByReporterIdAndDeletedFalseOrderByCreatedAtDesc(Long reporterId);

    /** The analysis window: chef reports in the given categories/statuses since the cutoff (deleted=False). */
    List<ChefReport> findByChefIdAndCategoryInAndCreatedAtGreaterThanEqualAndDeletedFalseAndStatusIn(
            Long chefId, Collection<ReportCategory> categories, Instant cutoff, Collection<ReportStatus> statuses);
}
