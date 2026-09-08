package com.payroll.demo.service;

import com.payroll.demo.domain.ComponentType;
import com.payroll.demo.domain.EmployeeStatus;
import com.payroll.demo.domain.IngestionRecordType;
import com.payroll.demo.domain.PayslipStatus;
import com.payroll.demo.dto.HrmsDtos.HrmsPayslip;
import com.payroll.demo.dto.HrmsDtos.HrmsPayslipLine;
import com.payroll.demo.dto.HrmsDtos.HrmsWorker;
import com.payroll.demo.exception.IngestionRecordException;
import com.payroll.demo.util.MoneyUtils;
import com.payroll.demo.util.TextUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validates and normalises HRMS records before anything reaches the database.
 *
 * <p>Shared by both ingestion paths, so a record that one would reject is rejected by the
 * other for the same reason and with the same wording.
 *
 * <p>The bias is towards rejecting loudly. A payroll feed that quietly writes a null salary
 * as zero, invents a missing pay date, or rounds away a fraction of someone's pay produces
 * wrong money with no trace. Every rejection here becomes a visible
 * {@code ingestion_run_errors} row with the reason attached.
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
    private static final Set<String> LEAVE_STATUSES = Set.of("ON_LEAVE", "LEAVE", "LOA", "SUSPENDED");
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
        if (worker == null) {
            throw reject(IngestionRecordType.WORKER, null, "null worker record in page");
        }
        String id = worker.workerId();

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

        requireMoney(IngestionRecordType.WORKER, id, worker.baseSalary(), "baseSalary");

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
        if (payslip == null) {
            throw reject(IngestionRecordType.PAYSLIP, null, "null payslip record in page");
        }
        String id = payslip.payslipId();

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

        BigDecimal gross = requireMoney(IngestionRecordType.PAYSLIP, id, payslip.grossPay(), "grossPay");
        BigDecimal deductions =
                requireMoney(IngestionRecordType.PAYSLIP, id, payslip.totalDeductions(), "totalDeductions");
        BigDecimal net = requireMoney(IngestionRecordType.PAYSLIP, id, payslip.netPay(), "netPay");

        // Arithmetic, not a tolerance question: if this does not hold the feed is wrong.
        if (!MoneyUtils.sameValue(MoneyUtils.subtract(gross, deductions), net)) {
            throw reject(IngestionRecordType.PAYSLIP, id,
                    "netPay " + net + " does not equal grossPay " + gross + " minus totalDeductions " + deductions);
        }

        toPayslipStatus(id, payslip.status());

        List<BigDecimal> earnings = new ArrayList<>();
        List<BigDecimal> deductionLines = new ArrayList<>();

        for (HrmsPayslipLine line : payslip.safeLines()) {
            if (line == null) {
                throw reject(IngestionRecordType.PAYSLIP, id, "null line on payslip");
            }
            requireText(IngestionRecordType.PAYSLIP, id, line.componentCode(), "componentCode", MAX_COMPONENT_CODE);
            if (line.description() != null && line.description().length() > MAX_DESCRIPTION) {
                throw reject(IngestionRecordType.PAYSLIP, id,
                        "line " + line.componentCode() + " description exceeds " + MAX_DESCRIPTION + " characters");
            }
            BigDecimal amount = requireMoney(IngestionRecordType.PAYSLIP, id, line.amount(),
                    "line " + line.componentCode() + " amount");

            if (toComponentType(id, line.componentType(), line.componentCode()) == ComponentType.EARNING) {
                earnings.add(amount);
            } else {
                deductionLines.add(amount);
            }
        }

        // Only meaningful when the feed actually sent a breakdown.
        if (!payslip.safeLines().isEmpty()) {
            BigDecimal earningTotal = MoneyUtils.sum(earnings);
            BigDecimal deductionTotal = MoneyUtils.sum(deductionLines);

            if (!MoneyUtils.sameValue(earningTotal, gross)) {
                throw reject(IngestionRecordType.PAYSLIP, id,
                        "earning lines total " + earningTotal + " but grossPay is " + gross);
            }
            if (!MoneyUtils.sameValue(deductionTotal, deductions)) {
                throw reject(IngestionRecordType.PAYSLIP, id,
                        "deduction lines total " + deductionTotal + " but totalDeductions is " + deductions);
            }
        }
    }

    public EmployeeStatus toEmployeeStatus(String recordId, String raw) {
        String key = TextUtils.normaliseKey(raw);
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
        String key = TextUtils.normaliseKey(raw);
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
        String key = TextUtils.normaliseKey(raw);
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
        String name = TextUtils.blankToNull(worker.departmentName());
        return TextUtils.truncate(name == null ? TextUtils.strip(worker.departmentCode()) : name,
                MAX_DEPARTMENT_NAME);
    }

    /**
     * Validates one money field and returns it at the schema's scale.
     *
     * <p>Both callers then persist {@link MoneyUtils#normalize} of the same value, so the
     * amount that was checked is the amount that is stored.
     */
    private static BigDecimal requireMoney(IngestionRecordType type, String recordId,
                                           BigDecimal value, String field) {
        if (value == null) {
            throw reject(type, recordId, field + " is required");
        }
        if (MoneyUtils.isNegative(value)) {
            throw reject(type, recordId, field + " is negative: " + value);
        }
        if (MoneyUtils.hasExcessPrecision(value)) {
            // Storing it would silently round away part of someone's pay.
            throw reject(type, recordId, field + " has more than " + MoneyUtils.SCALE
                    + " decimal places and cannot be stored exactly: " + value);
        }
        if (MoneyUtils.exceedsColumnWidth(value)) {
            throw reject(type, recordId, field + " exceeds " + MoneyUtils.MAX_INTEGER_DIGITS
                    + " digits before the decimal point: " + value);
        }
        return MoneyUtils.normalize(value);
    }

    private static void requireText(IngestionRecordType type, String recordId,
                                    String value, String field, int maxLength) {
        if (TextUtils.isBlank(value)) {
            throw reject(type, recordId, field + " is required");
        }
        if (value.strip().length() > maxLength) {
            throw reject(type, recordId, field + " exceeds " + maxLength + " characters");
        }
    }

    private static IngestionRecordException reject(IngestionRecordType type, String recordId, String reason) {
        return new IngestionRecordException(type, recordId, reason);
    }
}
