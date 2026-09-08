package com.payroll.demo.repository;

import com.payroll.demo.domain.Payslip;
import com.payroll.demo.domain.PayslipStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Reads payslips together with the employee, period and line items a caller needs.
 *
 * <p>Every read here fetches those associations eagerly via an entity graph. They are lazy
 * on the entity and {@code open-in-view} is off, so anything not fetched inside the
 * repository's transaction is unreachable by the time the service maps it to a DTO.
 */
public interface PayslipRepository extends JpaRepository<Payslip, Long> {

    /**
     * One employee's payslips, newest period first, optionally narrowed.
     *
     * <p>Each filter is skipped when null, so the same query serves the unfiltered listing
     * and a scoped one (a financial year, only what has actually been paid). The range is
     * an overlap test rather than containment: a payslip counts if any part of its period
     * falls inside the window.
     */
    // Written with coalesce rather than the usual `(:param is null or ...)`. Postgres cannot
    // infer the type of a parameter that only ever appears next to NULL, and fails the
    // statement with "could not determine data type" once the driver promotes it to a
    // server-side prepared statement — which happens after a handful of executions, not on
    // the first. coalesce gives every parameter its type from the column beside it.
    //
    // The identity trick (`x >= coalesce(:from, x)`) relies on period_start, period_end and
    // status being NOT NULL, which V1 enforces.
    @Query("""
            select p from Payslip p
             where p.employee.employeeCode = :employeeCode
               and p.payPeriod.periodEnd >= coalesce(:from, p.payPeriod.periodEnd)
               and p.payPeriod.periodStart <= coalesce(:to, p.payPeriod.periodStart)
               and p.status = coalesce(:status, p.status)
             order by p.payPeriod.periodStart desc, p.id desc
            """)
    @EntityGraph(attributePaths = {"employee", "payPeriod", "lines"})
    List<Payslip> findForEmployee(@Param("employeeCode") String employeeCode,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to,
                                  @Param("status") PayslipStatus status);

    /** A single payslip with everything needed to render it. */
    @EntityGraph(attributePaths = {"employee", "payPeriod", "lines"})
    Optional<Payslip> findWithDetailById(Long id);

    /** Every payslip in a pay period: the payroll register for that run. */
    @EntityGraph(attributePaths = {"employee", "payPeriod", "lines"})
    List<Payslip> findByPayPeriodIdOrderByEmployeeEmployeeCodeAsc(Long payPeriodId);
}
