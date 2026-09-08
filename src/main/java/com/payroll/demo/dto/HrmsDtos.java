package com.payroll.demo.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Wire shapes for the HRMS API, kept deliberately separate from the JPA entities.
 *
 * <p>Everything is nullable and every field is validated on the way in: an upstream
 * payroll feed is not a trusted source, and a missing amount must surface as a rejected
 * record rather than a {@code NullPointerException} halfway through a page.
 *
 * <p>Unknown properties are ignored so an additive change upstream does not break ingestion.
 */
public final class HrmsDtos {

    private HrmsDtos() {
    }

    /** One page of results. Providers differ, so paging is inferred defensively. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HrmsPage<T>(List<T> items, Integer page, Integer size, Long totalItems, Boolean hasMore) {

        public List<T> safeItems() {
            return items == null ? List.of() : items;
        }

        /**
         * Whether another page should be requested.
         *
         * <p>Prefers the explicit {@code hasMore} flag; falls back to "a full page came
         * back, so there is probably another" when the provider omits it.
         */
        public boolean moreAvailable(int requestedSize) {
            if (hasMore != null) {
                return hasMore;
            }
            int received = safeItems().size();
            return received > 0 && received >= requestedSize;
        }
    }

    /** A worker record; maps onto {@code employees} (+ {@code departments}). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HrmsWorker(
            String workerId,
            String firstName,
            String lastName,
            String email,
            LocalDate hireDate,
            LocalDate terminationDate,
            String employmentStatus,
            String departmentCode,
            String departmentName,
            BigDecimal baseSalary) {
    }

    /** A payslip; maps onto {@code payslips} (+ {@code pay_periods}, {@code payslip_lines}). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HrmsPayslip(
            String payslipId,
            String workerId,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate payDate,
            BigDecimal grossPay,
            BigDecimal totalDeductions,
            BigDecimal netPay,
            String status,
            List<HrmsPayslipLine> lines) {

        public List<HrmsPayslipLine> safeLines() {
            return lines == null ? List.of() : lines;
        }
    }

    /** A single earning or deduction on a payslip. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HrmsPayslipLine(
            String componentCode,
            String componentType,
            String description,
            BigDecimal amount) {
    }
}
