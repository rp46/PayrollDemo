package com.payroll.demo.service;

import com.payroll.demo.domain.ComponentType;
import com.payroll.demo.domain.EmployeeStatus;
import com.payroll.demo.domain.PayslipStatus;
import com.payroll.demo.dto.HrmsDtos.HrmsPayslip;
import com.payroll.demo.dto.HrmsDtos.HrmsPayslipLine;
import com.payroll.demo.dto.HrmsDtos.HrmsWorker;
import com.payroll.demo.exception.IngestionRecordException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The corners of the validator: every synonym, every column-width guard, every way a feed
 * can be wrong that the happy-path tests do not reach.
 */
class HrmsRecordMapperEdgeCaseTest {

    private final HrmsRecordMapper mapper = new HrmsRecordMapper();

    private static final LocalDate START = LocalDate.of(2026, 8, 1);
    private static final LocalDate END = LocalDate.of(2026, 8, 31);

    private static HrmsWorker worker(String field, Object value) {
        String first = "Priya";
        String last = "Raman";
        String email = "p@example.com";
        String deptCode = "ENG";
        String deptName = "Engineering";
        String status = "ACTIVE";
        BigDecimal salary = new BigDecimal("100.00");

        return switch (field) {
            case "firstName" -> new HrmsWorker("E-1", (String) value, last, email,
                    START, null, status, deptCode, deptName, salary);
            case "lastName" -> new HrmsWorker("E-1", first, (String) value, email,
                    START, null, status, deptCode, deptName, salary);
            case "email" -> new HrmsWorker("E-1", first, last, (String) value,
                    START, null, status, deptCode, deptName, salary);
            case "departmentCode" -> new HrmsWorker("E-1", first, last, email,
                    START, null, status, (String) value, deptName, salary);
            case "departmentName" -> new HrmsWorker("E-1", first, last, email,
                    START, null, status, deptCode, (String) value, salary);
            default -> throw new IllegalArgumentException(field);
        };
    }

    private static HrmsPayslip payslip(String gross, String deductions, String net,
                                       List<HrmsPayslipLine> lines) {
        return new HrmsPayslip("PS-1", "E-1", START, END, LocalDate.of(2026, 9, 1),
                new BigDecimal(gross), new BigDecimal(deductions), new BigDecimal(net), "PAID", lines);
    }

    // ------------------------------------------------------ status synonyms

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "A", "EMPLOYED", "HIRED", "active", " Active "})
    void activeSynonyms(String raw) {
        assertThat(mapper.toEmployeeStatus("E-1", raw)).isEqualTo(EmployeeStatus.ACTIVE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ON_LEAVE", "ON-LEAVE", "on leave", "LEAVE", "LOA", "SUSPENDED"})
    void onLeaveSynonyms(String raw) {
        assertThat(mapper.toEmployeeStatus("E-1", raw)).isEqualTo(EmployeeStatus.ON_LEAVE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TERMINATED", "TERM", "INACTIVE", "SEPARATED"})
    void terminatedSynonyms(String raw) {
        assertThat(mapper.toEmployeeStatus("E-1", raw)).isEqualTo(EmployeeStatus.TERMINATED);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("an absent status means active, which is unambiguous for a roster feed")
    void absentStatusDefaultsToActive(String raw) {
        assertThat(mapper.toEmployeeStatus("E-1", raw)).isEqualTo(EmployeeStatus.ACTIVE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "PENDING", "IN_PROGRESS", "CALCULATED", "in-progress"})
    void draftSynonyms(String raw) {
        assertThat(mapper.toPayslipStatus("PS-1", raw)).isEqualTo(PayslipStatus.DRAFT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "CONFIRMED", "COMPLETE", "COMPLETED"})
    void approvedSynonyms(String raw) {
        assertThat(mapper.toPayslipStatus("PS-1", raw)).isEqualTo(PayslipStatus.APPROVED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAID", "SETTLED", "DISBURSED"})
    void paidSynonyms(String raw) {
        assertThat(mapper.toPayslipStatus("PS-1", raw)).isEqualTo(PayslipStatus.PAID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EARNING", "EARNINGS", "CREDIT", "PAY", "ALLOWANCE", "earnings"})
    void earningSynonyms(String raw) {
        assertThat(mapper.toComponentType("PS-1", raw, "X")).isEqualTo(ComponentType.EARNING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEDUCTION", "DEDUCTIONS", "DEBIT", "TAX", "CONTRIBUTION"})
    void deductionSynonyms(String raw) {
        assertThat(mapper.toComponentType("PS-1", raw, "X")).isEqualTo(ComponentType.DEDUCTION);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "REIMBURSEMENT", "UNKNOWN"})
    @DisplayName("an unrecognised component type is rejected, never guessed")
    void unknownComponentTypeIsRejected(String raw) {
        assertThatThrownBy(() -> mapper.toComponentType("PS-1", raw, "MISC"))
                .isInstanceOf(IngestionRecordException.class)
                .hasMessageContaining("unrecognised componentType");
    }

    // -------------------------------------------------- required worker text

    @ParameterizedTest
    @ValueSource(strings = {"firstName", "lastName", "email"})
    @DisplayName("every required name field is required")
    void requiredWorkerFields(String field) {
        assertThatThrownBy(() -> mapper.validateWorker(worker(field, null)))
                .isInstanceOf(IngestionRecordException.class)
                .hasMessageContaining(field + " is required");

        assertThatThrownBy(() -> mapper.validateWorker(worker(field, "   ")))
                .isInstanceOf(IngestionRecordException.class)
                .hasMessageContaining(field + " is required");
    }

    @ParameterizedTest
    @CsvSource({"firstName,60", "lastName,60", "email,160"})
    void workerFieldsRespectColumnWidths(String field, int max) {
        assertThatCode(() -> mapper.validateWorker(worker(field, "x".repeat(max))))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> mapper.validateWorker(worker(field, "x".repeat(max + 1))))
                .isInstanceOf(IngestionRecordException.class)
                .hasMessageContaining("exceeds " + max + " characters");
    }

    @Test
    void departmentCodeRespectsItsColumnWidth() {
        assertThatThrownBy(() -> mapper.validateWorker(worker("departmentCode", "D".repeat(21))))
                .isInstanceOf(IngestionRecordException.class)
                .hasMessageContaining("departmentCode exceeds 20 characters");
    }

    @Test
    @DisplayName("a missing department is allowed; the employee simply has none")
    void departmentIsOptional() {
        assertThatCode(() -> mapper.validateWorker(worker("departmentCode", null)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a long department name is cut to the column rather than rejected")
    void departmentNameIsTruncatedNotRejected() {
        String name = mapper.departmentNameFor(worker("departmentName", "N".repeat(250)));
        assertThat(name).hasSize(100);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void departmentNameFallsBackToTheCode(String blank) {
        assertThat(mapper.departmentNameFor(worker("departmentName", blank))).isEqualTo("ENG");
    }

    // ------------------------------------------------------------- payslips

    @Test
    @DisplayName("a null entry in the lines list is rejected rather than dereferenced")
    void nullLineIsRejected() {
        List<HrmsPayslipLine> lines = new ArrayList<>();
        lines.add(null);

        assertThatThrownBy(() -> mapper.validatePayslip(payslip("0.00", "0.00", "0.00", lines), true))
                .isInstanceOf(IngestionRecordException.class)
                .hasMessageContaining("null line on payslip");
    }

    @Test
    void lineComponentCodeIsRequiredAndBounded() {
        assertThatThrownBy(() -> mapper.validatePayslip(payslip("10.00", "0.00", "10.00",
                List.of(new HrmsPayslipLine(null, "EARNING", "d", new BigDecimal("10.00")))), true))
                .isInstanceOf(IngestionRecordException.class)
                .hasMessageContaining("componentCode is required");

        assertThatThrownBy(() -> mapper.validatePayslip(payslip("10.00", "0.00", "10.00",
                List.of(new HrmsPayslipLine("C".repeat(31), "EARNING", "d", new BigDecimal("10.00")))), true))
                .isInstanceOf(IngestionRecordException.class)
                .hasMessageContaining("componentCode exceeds 30 characters");
    }

    @Test
    void lineDescriptionRespectsItsColumnWidth() {
        assertThatThrownBy(() -> mapper.validatePayslip(payslip("10.00", "0.00", "10.00",
                List.of(new HrmsPayslipLine("BASIC", "EARNING", "d".repeat(201),
                        new BigDecimal("10.00")))), true))
                .isInstanceOf(IngestionRecordException.class)
                .hasMessageContaining("description exceeds 200 characters");
    }

    @Test
    @DisplayName("a description at exactly the column width is fine, and null is fine too")
    void descriptionBoundaries() {
        assertThatCode(() -> mapper.validatePayslip(payslip("10.00", "0.00", "10.00",
                List.of(new HrmsPayslipLine("BASIC", "EARNING", "d".repeat(200),
                        new BigDecimal("10.00")))), true)).doesNotThrowAnyException();

        assertThatCode(() -> mapper.validatePayslip(payslip("10.00", "0.00", "10.00",
                List.of(new HrmsPayslipLine("BASIC", "EARNING", null,
                        new BigDecimal("10.00")))), true)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("deduction lines that do not add up are caught as well as earnings")
    void deductionLinesMustBalanceToo() {
        List<HrmsPayslipLine> lines = List.of(
                new HrmsPayslipLine("BASIC", "EARNING", "Basic", new BigDecimal("100.00")),
                new HrmsPayslipLine("TDS", "DEDUCTION", "Tax", new BigDecimal("5.00")));

        assertThatThrownBy(() -> mapper.validatePayslip(payslip("100.00", "10.00", "90.00", lines), true))
                .isInstanceOf(IngestionRecordException.class)
                .hasMessageContaining("deduction lines total 5.00 but totalDeductions is 10.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"grossPay", "totalDeductions", "netPay"})
    @DisplayName("every money field on a payslip is required")
    void payslipAmountsAreRequired(String field) {
        BigDecimal gross = field.equals("grossPay") ? null : new BigDecimal("10.00");
        BigDecimal ded = field.equals("totalDeductions") ? null : new BigDecimal("0.00");
        BigDecimal net = field.equals("netPay") ? null : new BigDecimal("10.00");

        HrmsPayslip broken = new HrmsPayslip("PS-1", "E-1", START, END,
                LocalDate.of(2026, 9, 1), gross, ded, net, "PAID", List.of());

        assertThatThrownBy(() -> mapper.validatePayslip(broken, true))
                .isInstanceOf(IngestionRecordException.class)
                .hasMessageContaining(field + " is required");
    }

    @Test
    void negativePayslipAmountsAreRejected() {
        assertThatThrownBy(() -> mapper.validatePayslip(
                payslip("-1.00", "0.00", "-1.00", List.of()), true))
                .isInstanceOf(IngestionRecordException.class)
                .hasMessageContaining("grossPay is negative");
    }

    @Test
    @DisplayName("a missing workerId is caught before anything tries to resolve it")
    void payslipWorkerIdIsRequired() {
        HrmsPayslip broken = new HrmsPayslip("PS-1", null, START, END, LocalDate.of(2026, 9, 1),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "PAID", List.of());

        assertThatThrownBy(() -> mapper.validatePayslip(broken, true))
                .isInstanceOf(IngestionRecordException.class)
                .hasMessageContaining("workerId is required");
    }

    @Test
    @DisplayName("a period with either end missing is rejected")
    void payslipPeriodBoundsAreRequired() {
        for (LocalDate[] bounds : Arrays.asList(
                new LocalDate[]{null, END}, new LocalDate[]{START, null}, new LocalDate[]{null, null})) {
            HrmsPayslip broken = new HrmsPayslip("PS-1", "E-1", bounds[0], bounds[1],
                    LocalDate.of(2026, 9, 1), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    "PAID", List.of());

            assertThatThrownBy(() -> mapper.validatePayslip(broken, true))
                    .isInstanceOf(IngestionRecordException.class)
                    .hasMessageContaining("periodStart and periodEnd are both required");
        }
    }

    @Test
    @DisplayName("a single-day period is valid; start and end may be equal")
    void singleDayPeriodIsValid() {
        HrmsPayslip oneDay = new HrmsPayslip("PS-1", "E-1", START, START, LocalDate.of(2026, 9, 1),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "PAID", List.of());

        assertThatCode(() -> mapper.validatePayslip(oneDay, true)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a termination on the hire date is valid; only earlier is not")
    void terminationOnHireDateIsValid() {
        HrmsWorker sameDay = new HrmsWorker("E-1", "A", "B", "a@example.com",
                START, START, "TERMINATED", "ENG", "Engineering", BigDecimal.TEN);

        assertThatCode(() -> mapper.validateWorker(sameDay)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the rejection carries the record type and id, so the audit row can name it")
    void rejectionIdentifiesTheRecord() {
        assertThatThrownBy(() -> mapper.validateWorker(worker("email", null)))
                .isInstanceOfSatisfying(IngestionRecordException.class, ex -> {
                    assertThat(ex.getRecordType())
                            .isEqualTo(com.payroll.demo.domain.IngestionRecordType.WORKER);
                    assertThat(ex.getExternalId()).isEqualTo("E-1");
                });
    }
}
