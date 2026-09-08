package com.payroll.demo.dto;

/**
 * Rows written per table by one bulk upsert.
 *
 * <p>Lives in {@code dto} rather than nested in the repository so the service and the API
 * response can both name it without either depending on the persistence tier.
 */
public record BulkWriteCounts(int departments,
                              int employees,
                              int payPeriods,
                              int payslips,
                              int payslipLines) {

    public static BulkWriteCounts none() {
        return new BulkWriteCounts(0, 0, 0, 0, 0);
    }

    @Override
    public String toString() {
        return "%d department(s), %d employee(s), %d pay period(s), %d payslip(s), %d line(s)"
                .formatted(departments, employees, payPeriods, payslips, payslipLines);
    }
}
