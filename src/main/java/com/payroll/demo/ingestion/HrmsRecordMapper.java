package com.payroll.demo.ingestion;

import com.payroll.demo.domain.ComponentType;
import com.payroll.demo.domain.EmployeeStatus;
import com.payroll.demo.domain.PayslipStatus;
import com.payroll.demo.hrms.HrmsDtos.HrmsPayslip;
import com.payroll.demo.hrms.HrmsDtos.HrmsPayslipLine;
import com.payroll.demo.hrms.HrmsDtos.HrmsWorker;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

/**
 * Validates and normalises HRMS records before anything reaches the database.
 *
 * <p>The bias is towards rejecting loudly. A payroll feed that quietly writes a null
 * salary as zero, or invents a pay date because one was missing, produces wrong money
 * with no trace. Every rejection here becomes a visible {@code ingestion_run_errors} row
 * with the reason attached.
 */
@Component
public class HrmsRecordMapper {

    // Column widths from V1; catching these here gives a readable reason instead of a
    // driver-level constraint violation halfway through a batch.
    private static final int MAX_EMPLOYEE_CODE = 20;
    private static final int MAX_NAME = 60;
    private static final int MAX_EMAIL = 160;
    private static final int MAX_DEPARTMENT_CODE = 20;
    private static final int MAX_DEPARTMENT_NAME = 100;
    private static final int MAX_COMPONENT_CODE = 30;
    private static final int MAX_DESCRIPTION = 200;

    private static final Set<String> ACTIVE_STATUSES = Set.of("ACTIVE", "A", "EMPLOYED", "HIRED");
    private static final Set<String> LEAVE_STATUSES = Set.of("ON_LEAVE", "LEAVE", "LOA", "ON LEAVE", "SUSPENDED");
    private static final Set<String> TERMINATED_STATUSES = Set.of("TERMINATED", "TERM", "INACTIVE", "SEPARATED");

    private static final Set<String> DRAFT_STATUSES = Set.of("DRAFT", "PENDING", "IN_PROGRESS", "CALCULATED");
    private static final Set<String> APPROVED_STATUSES = Set.of("APPROVED", "CONFIRMED", "COMPLETE", "COMPLETED");
    private static final Set<String> PAID_STATUSES = Set.of("PAID", "SETTLED", "DISBURSED");

    private static final Set<String> EARNING_TYPES = Set.of("EARNING", "EARNINGS", "CREDIT", "PAY", "ALLOWANCE");
    private static final Set<String> DEDUCTION_TYPES = Set.of("DEDUCTION", "DEDUCTIONS", "DEBIT", "TAX", "CONTRIBUTION");

    /**
     * Checks a worker record end to end.
     *
     * @throws IngestionRecordException if the record cannot be stored as-is
     */
    public void validateWorker(HrmsWorker worker) {
        String id = worker == null ? null : worker.workerId();
        if (worker == null) {
            throw reject(IngestionRecordType.WORKER, null, "null worker record in page");
        }
        requireText(IngestionRecordType.WORKER, id, id, "workerId", MAX_EMPLOYEE_CODE);
        requireText(IngestionRecordType.WORKER, id, worker.firstName(), "firstName", MAX_NAME);
        requireText(IngestionRecordType.WORKER, id, worker.lastName(), "lastName", MAX_NAME);
        requireText(IngestionRecordType.WORKER, id, worker.email(), "email", MAX_EMAIL);

        if (worker.hireDate() == null) {
            throw reject(IngestionRecordType.WORKER, id, "hireDate is required");
        }
        if (worker.terminationDate() != null && worker.terminationDate().isBefore(worker.hireDate())) {
            throw reject(IngestionRecordType.WORKER, id,
                    "terminationDate " + worker.terminationDate() + " precedes hireDate " + worker.hireDate());
        }
        if (worker.baseSalary() == null) {
            throw reject(IngestionRecordType.WORKER, id, "baseSalary is required");
        }
        if (worker.baseSalary().signum() < 0) {
            throw reject(IngestionRecordType.WORKER, id, "baseSalary is negative: " + worker.baseSalary());
        }
        if (worker.departmentCode() != null && worker.departmentCode().length() > MAX_DEPARTMENT_CODE) {
            throw reject(IngestionRecordType.WORKER, id,
                    "departmentCode exceeds " + MAX_DEPARTMENT_CODE + " characters");
        }
        // Resolving the status also validates it.
        toEmployeeStatus(id, worker.employmentStatus());
    }

    /**
     * Checks a payslip and its lines.
     *
     * @param mustCreatePeriod whether the pay period does not exist yet, in which case a
     *                         {@code payDate} is mandatory rather than optional
     * @throws IngestionRecordException if the record cannot be stored as-is
     */
    public void validatePayslip(HrmsPayslip payslip, boolean mustCreatePeriod) {
        String id = payslip == null ? null : payslip.payslipId();
        if (payslip == null) {
            throw reject(IngestionRecordType.PAYSLIP, null, "null payslip record in page");
        }
        requireText(IngestionRecordType.PAYSLIP, id, payslip.workerId(), "workerId", MAX_EMPLOYEE_CODE);

        if (payslip.periodStart() == null || payslip.periodEnd() == null) {
            throw reject(IngestionRecordType.PAYSLIP, id, "periodStart and periodEnd are both required");
        }
        if (payslip.periodEnd().isBefore(payslip.periodStart())) {
            throw reject(IngestionRecordType.PAYSLIP, id,
                    "periodEnd " + payslip.periodEnd() + " precedes periodStart " + payslip.periodStart());
        }
        // A pay date is only invented-from-nothing risk when the period is new. If the
        // period already exists, its stored pay date stands.
        if (mustCreatePeriod && payslip.payDate() == null) {
            throw reject(IngestionRecordType.PAYSLIP, id,
                    "payDate is required to create pay period " + payslip.periodStart() + ".." + payslip.periodEnd());
        }

        BigDecimal gross = requireAmount(id, payslip.grossPay(), "grossPay");
        BigDecimal deductions = requireAmount(id, payslip.totalDeductions(), "totalDeductions");
        BigDecimal net = requireAmount(id, payslip.netPay(), "netPay");

        // Arithmetic, not a tolerance question: if this does not hold the feed is wrong.
        if (gross.subtract(deductions).compareTo(net) != 0) {
            throw reject(IngestionRecordType.PAYSLIP, id,
                    "netPay " + net + " does not equal grossPay " + gross + " minus totalDeductions " + deductions);
        }

        toPayslipStatus(id, payslip.status());

        BigDecimal earnings = BigDecimal.ZERO;
        BigDecimal deductionLines = BigDecimal.ZERO;
        for (HrmsPayslipLine line : payslip.safeLines()) {
            if (line == null) {
                throw reject(IngestionRecordType.PAYSLIP, id, "null line on payslip");
            }
            requireText(IngestionRecordType.PAYSLIP, id, line.componentCode(), "componentCode", MAX_COMPONENT_CODE);
            if (line.description() != null && line.description().length() > MAX_DESCRIPTION) {
                throw reject(IngestionRecordType.PAYSLIP, id,
                        "line " + line.componentCode() + " description exceeds " + MAX_DESCRIPTION + " characters");
            }
            BigDecimal amount = requireAmount(id, line.amount(), "line " + line.componentCode() + " amount");

            ComponentType type = toComponentType(id, line.componentType(), line.componentCode());
            if (type == ComponentType.EARNING) {
                earnings = earnings.add(amount);
            } else {
                deductionLines = deductionLines.add(amount);
            }
        }

        // Only meaningful when the feed actually sent a breakdown.
        if (!payslip.safeLines().isEmpty()) {
            if (earnings.compareTo(gross) != 0) {
                throw reject(IngestionRecordType.PAYSLIP, id,
                        "earning lines total " + earnings + " but grossPay is " + gross);
            }
            if (deductionLines.compareTo(deductions) != 0) {
                throw reject(IngestionRecordType.PAYSLIP, id,
                        "deduction lines total " + deductionLines + " but totalDeductions is " + deductions);
            }
        }
    }

    public EmployeeStatus toEmployeeStatus(String recordId, String raw) {
        String key = normalise(raw);
        if (key.isEmpty()) {
            // Absent status is common and unambiguous for an active roster feed.
            return EmployeeStatus.ACTIVE;
        }
        if (ACTIVE_STATUSES.contains(key)) {
            return EmployeeStatus.ACTIVE;
        }
        if (LEAVE_STATUSES.contains(key)) {
            return EmployeeStatus.ON_LEAVE;
        }
        if (TERMINATED_STATUSES.contains(key)) {
            return EmployeeStatus.TERMINATED;
        }
        throw reject(IngestionRecordType.WORKER, recordId, "unrecognised employmentStatus: " + raw);
    }

    public PayslipStatus toPayslipStatus(String recordId, String raw) {
        String key = normalise(raw);
        if (key.isEmpty()) {
            return PayslipStatus.DRAFT;
        }
        if (DRAFT_STATUSES.contains(key)) {
            return PayslipStatus.DRAFT;
        }
        if (APPROVED_STATUSES.contains(key)) {
            return PayslipStatus.APPROVED;
        }
        if (PAID_STATUSES.contains(key)) {
            return PayslipStatus.PAID;
        }
        throw reject(IngestionRecordType.PAYSLIP, recordId, "unrecognised payslip status: " + raw);
    }

    public ComponentType toComponentType(String recordId, String raw, String componentCode) {
        String key = normalise(raw);
        if (EARNING_TYPES.contains(key)) {
            return ComponentType.EARNING;
        }
        if (DEDUCTION_TYPES.contains(key)) {
            return ComponentType.DEDUCTION;
        }
        throw reject(IngestionRecordType.PAYSLIP, recordId,
                "unrecognised componentType on line " + componentCode + ": " + raw);
    }

    /** Falls back to the department code when the feed omits a display name. */
    public String departmentNameFor(HrmsWorker worker) {
        String name = worker.departmentName();
        if (name == null || name.isBlank()) {
            return truncate(worker.departmentCode(), MAX_DEPARTMENT_NAME);
        }
        return truncate(name.strip(), MAX_DEPARTMENT_NAME);
    }

    private static BigDecimal requireAmount(String recordId, BigDecimal value, String field) {
        if (value == null) {
            throw reject(IngestionRecordType.PAYSLIP, recordId, field + " is required");
        }
        if (value.signum() < 0) {
            throw reject(IngestionRecordType.PAYSLIP, recordId, field + " is negative: " + value);
        }
        return value;
    }

    private static void requireText(IngestionRecordType type, String recordId,
                                    String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw reject(type, recordId, field + " is required");
        }
        if (value.strip().length() > maxLength) {
            throw reject(type, recordId, field + " exceeds " + maxLength + " characters");
        }
    }

    private static String normalise(String raw) {
        return raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static IngestionRecordException reject(IngestionRecordType type, String recordId, String reason) {
        return new IngestionRecordException(type, recordId, reason);
    }
}
