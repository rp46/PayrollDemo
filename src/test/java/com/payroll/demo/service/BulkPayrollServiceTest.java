package com.payroll.demo.service;

import com.payroll.demo.config.HrmsProperties;
import com.payroll.demo.domain.ComponentType;
import com.payroll.demo.domain.EmployeeStatus;
import com.payroll.demo.domain.IngestionFailureKind;
import com.payroll.demo.domain.IngestionRecordType;
import com.payroll.demo.domain.IngestionStatus;
import com.payroll.demo.domain.IngestionTrigger;
import com.payroll.demo.dto.BulkUpsertResult;
import com.payroll.demo.dto.BulkWriteCounts;
import com.payroll.demo.dto.HrmsDtos.HrmsPage;
import com.payroll.demo.dto.HrmsDtos.HrmsPayslip;
import com.payroll.demo.dto.HrmsDtos.HrmsPayslipLine;
import com.payroll.demo.dto.HrmsDtos.HrmsWorker;
import com.payroll.demo.dto.PayrollRows.PayrollBatch;
import com.payroll.demo.dto.PayrollRows.PeriodKey;
import com.payroll.demo.hrms.HrmsApiException;
import com.payroll.demo.hrms.HrmsClient;
import com.payroll.demo.hrms.HrmsFailureKind;
import com.payroll.demo.hrms.HrmsRetryExecutor;
import com.payroll.demo.repository.BulkPayrollRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The transform tier: HRMS wire records in, payroll rows out, with no database involved.
 */
class BulkPayrollServiceTest {

    private static final LocalDate START = LocalDate.of(2026, 8, 1);
    private static final LocalDate END = LocalDate.of(2026, 8, 31);
    private static final long RUN_ID = 77L;

    private HrmsClient client;
    private BulkPayrollRepository repository;
    private IngestionRunRecorder recorder;
    private BulkPayrollService service;

    @BeforeEach
    void setUp() {
        client = mock(HrmsClient.class);
        repository = mock(BulkPayrollRepository.class);
        recorder = mock(IngestionRunRecorder.class);

        HrmsProperties properties = new HrmsProperties();
        properties.setPageSize(2);
        properties.setMaxPages(10);
        properties.getRetry().setMaxAttempts(2);

        when(recorder.start(any(), any(), any(), any())).thenReturn(RUN_ID);
        when(repository.findExistingPeriods(any())).thenReturn(Set.of());
        when(repository.findExistingEmployeeCodes(any())).thenReturn(Set.of());
        when(repository.applyBatch(any())).thenReturn(BulkWriteCounts.none());

        HrmsPageReader pages = new HrmsPageReader(client,
                new HrmsRetryExecutor(properties.getRetry(), delay -> {
                }), properties);
        service = new BulkPayrollService(pages, new HrmsRecordMapper(), repository, recorder);
    }

    private static HrmsWorker worker(String id, String dept, String salary) {
        return new HrmsWorker(id, "Priya", "Raman", id + "@example.com",
                LocalDate.of(2023, 4, 1), null, "Active", dept, "Dept " + dept, new BigDecimal(salary));
    }

    private static HrmsPayslip payslip(String id, String workerId, LocalDate payDate,
                                       List<HrmsPayslipLine> lines) {
        return new HrmsPayslip(id, workerId, START, END, payDate,
                new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("90"), "paid", lines);
    }

    private static <T> HrmsPage<T> page(List<T> items) {
        return new HrmsPage<>(items, 0, items.size(), (long) items.size(), false);
    }

    private void stubWorkers(List<HrmsWorker> workers) {
        when(client.fetchWorkers(anyInt(), anyInt(), isNull())).thenReturn(page(workers));
    }

    private void stubPayslips(List<HrmsPayslip> payslips) {
        when(client.fetchPayslips(any(), any(), anyInt(), anyInt())).thenReturn(page(payslips));
    }

    private PayrollBatch captureBatch() {
        ArgumentCaptor<PayrollBatch> captor = ArgumentCaptor.forClass(PayrollBatch.class);
        verify(repository).applyBatch(captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------------------- transform

    @Test
    @DisplayName("workers become employee rows and their departments are deduplicated")
    void transformsWorkersAndDedupesDepartments() {
        stubWorkers(List.of(worker("E-1", "ENG", "100"), worker("E-2", "ENG", "200"),
                worker("E-3", "FIN", "300")));

        BulkUpsertResult result = service.upsertAll(IngestionTrigger.MANUAL, null, null, null, false);

        PayrollBatch batch = captureBatch();
        assertThat(batch.employees()).extracting(e -> e.employeeCode())
                .containsExactly("E-1", "E-2", "E-3");
        assertThat(batch.employees().getFirst().status()).isEqualTo(EmployeeStatus.ACTIVE);
        assertThat(batch.departments()).extracting(d -> d.code())
                .as("three workers, two distinct departments")
                .containsExactly("ENG", "FIN");
        assertThat(result.status()).isEqualTo(IngestionStatus.SUCCEEDED);
        assertThat(result.committed()).isTrue();
    }

    @Test
    @DisplayName("a worker with no department maps to a null department rather than a fake one")
    void workerWithoutDepartment() {
        stubWorkers(List.of(worker("E-1", null, "100")));

        service.upsertAll(IngestionTrigger.MANUAL, null, null, null, false);

        PayrollBatch batch = captureBatch();
        assertThat(batch.departments()).isEmpty();
        assertThat(batch.employees().getFirst().departmentCode()).isNull();
    }

    @Test
    @DisplayName("payslips become rows with their lines and pay period attached")
    void transformsPayslips() {
        stubWorkers(List.of(worker("E-1", "ENG", "100")));
        stubPayslips(List.of(payslip("PS-1", "E-1", LocalDate.of(2026, 9, 1), List.of(
                new HrmsPayslipLine("BASIC", "EARNING", "Basic", new BigDecimal("100")),
                new HrmsPayslipLine("TDS", "TAX", "Tax", new BigDecimal("10"))))));

        service.upsertAll(IngestionTrigger.MANUAL, START, END, null, false);

        PayrollBatch batch = captureBatch();
        assertThat(batch.payPeriods()).singleElement()
                .satisfies(p -> assertThat(p.payDate()).isEqualTo(LocalDate.of(2026, 9, 1)));
        assertThat(batch.payslips()).singleElement().satisfies(p -> {
            assertThat(p.key().employeeCode()).isEqualTo("E-1");
            assertThat(p.lines()).extracting(l -> l.componentType())
                    .containsExactly(ComponentType.EARNING, ComponentType.DEDUCTION);
        });
        assertThat(batch.totalLines()).isEqualTo(2);
    }

    @Test
    @DisplayName("an invalid worker is rejected and the rest still make the batch")
    void invalidWorkerIsRejected() {
        HrmsWorker broken = new HrmsWorker("E-BAD", "No", "Salary", "bad@example.com",
                LocalDate.of(2023, 1, 1), null, "ACTIVE", "ENG", "Engineering", null);
        stubWorkers(List.of(worker("E-1", "ENG", "100"), broken));

        BulkUpsertResult result = service.upsertAll(IngestionTrigger.MANUAL, null, null, null, false);

        assertThat(captureBatch().employees()).extracting(e -> e.employeeCode()).containsExactly("E-1");
        assertThat(result.status()).isEqualTo(IngestionStatus.PARTIAL);
        assertThat(result.rejectedRecords()).singleElement().satisfies(r -> {
            assertThat(r.recordType()).isEqualTo(IngestionRecordType.WORKER);
            assertThat(r.externalId()).isEqualTo("E-BAD");
            assertThat(r.reason()).contains("baseSalary is required");
        });
    }

    @Test
    @DisplayName("a payslip for a worker in the same batch is accepted before that worker exists in the DB")
    void payslipForWorkerInSameBatch() {
        stubWorkers(List.of(worker("E-1", "ENG", "100")));
        stubPayslips(List.of(payslip("PS-1", "E-1", LocalDate.of(2026, 9, 1), List.of())));

        BulkUpsertResult result = service.upsertAll(IngestionTrigger.MANUAL, START, END, null, false);

        assertThat(captureBatch().payslips()).hasSize(1);
        assertThat(result.recordsRejected()).isZero();
    }

    @Test
    @DisplayName("a payslip for an unknown worker is rejected rather than left to break the transaction")
    void payslipForUnknownWorkerIsRejected() {
        stubWorkers(List.of(worker("E-1", "ENG", "100")));
        stubPayslips(List.of(payslip("PS-GHOST", "E-NOBODY", LocalDate.of(2026, 9, 1), List.of())));

        BulkUpsertResult result = service.upsertAll(IngestionTrigger.MANUAL, START, END, null, false);

        assertThat(captureBatch().payslips()).isEmpty();
        assertThat(result.rejectedRecords()).singleElement()
                .satisfies(r -> assertThat(r.reason()).contains("no employee with code E-NOBODY"));
    }

    @Test
    @DisplayName("a worker already in the database counts as known for payslip resolution")
    void payslipForWorkerAlreadyStored() {
        when(repository.findExistingEmployeeCodes(any())).thenReturn(Set.of("E-STORED"));
        stubWorkers(List.of());
        stubPayslips(List.of(payslip("PS-1", "E-STORED", LocalDate.of(2026, 9, 1), List.of())));

        BulkUpsertResult result = service.upsertAll(IngestionTrigger.MANUAL, START, END, null, false);

        assertThat(captureBatch().payslips()).hasSize(1);
        assertThat(result.recordsRejected()).isZero();
    }

    @Test
    @DisplayName("payDate is only demanded when the period does not already exist")
    void payDateOnlyRequiredForNewPeriods() {
        when(repository.findExistingEmployeeCodes(any())).thenReturn(Set.of("E-1"));
        stubWorkers(List.of());
        stubPayslips(List.of(payslip("PS-1", "E-1", null, List.of())));

        // Period is new: payDate is mandatory, so the record is rejected.
        BulkUpsertResult newPeriod = service.upsertAll(IngestionTrigger.MANUAL, START, END, null, false);
        assertThat(newPeriod.rejectedRecords()).singleElement()
                .satisfies(r -> assertThat(r.reason()).contains("payDate is required"));

        // Period already exists: its stored pay date stands and nothing is invented.
        when(repository.findExistingPeriods(any())).thenReturn(Set.of(new PeriodKey(START, END)));
        BulkUpsertResult existingPeriod = service.upsertAll(IngestionTrigger.MANUAL, START, END, null, false);
        assertThat(existingPeriod.recordsRejected()).isZero();
    }

    // ------------------------------------------------------- write and rollback

    @Test
    @DisplayName("strict mode writes nothing at all when any record is rejected")
    void strictModeSkipsTheWriteEntirely() {
        HrmsWorker broken = new HrmsWorker("E-BAD", "No", "Salary", "bad@example.com",
                LocalDate.of(2023, 1, 1), null, "ACTIVE", "ENG", "Engineering", null);
        stubWorkers(List.of(worker("E-1", "ENG", "100"), broken));

        BulkUpsertResult result = service.upsertAll(IngestionTrigger.MANUAL, null, null, null, true);

        verify(repository, never()).applyBatch(any());
        assertThat(result.status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(result.committed()).isFalse();
        assertThat(result.written()).isNull();
        assertThat(result.failureDetail()).contains("nothing was written");
    }

    @Test
    @DisplayName("strict mode still writes when every record is clean")
    void strictModeWritesWhenNothingIsRejected() {
        stubWorkers(List.of(worker("E-1", "ENG", "100")));

        BulkUpsertResult result = service.upsertAll(IngestionTrigger.MANUAL, null, null, null, true);

        verify(repository).applyBatch(any());
        assertThat(result.status()).isEqualTo(IngestionStatus.SUCCEEDED);
        assertThat(result.committed()).isTrue();
    }

    @Test
    @DisplayName("a rolled-back batch is reported as not committed")
    void rollbackIsReported() {
        stubWorkers(List.of(worker("E-1", "ENG", "100")));
        when(repository.applyBatch(any()))
                .thenThrow(new DataIntegrityViolationException("employees_base_salary_chk violated"));

        BulkUpsertResult result = service.upsertAll(IngestionTrigger.MANUAL, null, null, null, false);

        assertThat(result.committed()).isFalse();
        assertThat(result.status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(result.failureKind()).isEqualTo(IngestionFailureKind.INTERNAL_ERROR);
        assertThat(result.failureDetail()).contains("rolled back").contains("employees_base_salary_chk");

        // The audit row is written in its own transaction, so it survives the rollback.
        verify(recorder).finish(eq(RUN_ID), eq(IngestionStatus.FAILED),
                eq(IngestionFailureKind.INTERNAL_ERROR), any(), any());
    }

    @Test
    @DisplayName("an HRMS failure means the write is never attempted")
    void hrmsFailureSkipsTheWrite() {
        when(client.fetchWorkers(anyInt(), anyInt(), isNull())).thenThrow(
                new HrmsApiException(HrmsFailureKind.BAD_REQUEST, "HTTP 400", 400, "{}", null, null));

        BulkUpsertResult result = service.upsertAll(IngestionTrigger.MANUAL, START, END, null, false);

        verify(repository, never()).applyBatch(any());
        assertThat(result.committed()).isFalse();
        assertThat(result.failureKind()).isEqualTo(IngestionFailureKind.BAD_REQUEST);
    }

    @Test
    @DisplayName("the whole feed is fetched before the transaction opens")
    void fetchesEveryPageBeforeWriting() {
        when(client.fetchWorkers(eq(0), anyInt(), isNull()))
                .thenReturn(new HrmsPage<>(List.of(worker("E-1", "ENG", "100"), worker("E-2", "ENG", "200")),
                        0, 2, 3L, true));
        when(client.fetchWorkers(eq(1), anyInt(), isNull()))
                .thenReturn(new HrmsPage<>(List.of(worker("E-3", "ENG", "300")), 1, 1, 3L, false));

        BulkUpsertResult result = service.upsertAll(IngestionTrigger.MANUAL, null, null, null, false);

        assertThat(captureBatch().employees()).hasSize(3);
        assertThat(result.employeesFetched()).isEqualTo(3);
        assertThat(result.httpAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("payslips are skipped when no period is requested")
    void noPeriodMeansWorkersOnly() {
        stubWorkers(List.of(worker("E-1", "ENG", "100")));

        service.upsertAll(IngestionTrigger.MANUAL, null, null, null, false);

        verify(client, never()).fetchPayslips(any(), any(), anyInt(), anyInt());
        assertThat(captureBatch().payslips()).isEmpty();
    }

    @Test
    @DisplayName("a repeated worker id collapses to one row, since ON CONFLICT cannot hit a key twice")
    void duplicateWorkerIdCollapses() {
        stubWorkers(List.of(worker("E-1", "ENG", "100"), worker("E-1", "ENG", "999")));

        service.upsertAll(IngestionTrigger.MANUAL, null, null, null, false);

        PayrollBatch batch = captureBatch();
        assertThat(batch.employees()).hasSize(1);
        assertThat(batch.employees().getFirst().baseSalary())
                .as("last one in the feed wins")
                .isEqualByComparingTo("999");
    }

    @Test
    @DisplayName("the audit row records what the batch actually wrote, not zero")
    void auditRowCarriesTheWrittenCounts() {
        stubWorkers(List.of(worker("E-1", "ENG", "100"), worker("E-2", "ENG", "200")));
        when(repository.applyBatch(any())).thenReturn(new BulkWriteCounts(1, 2, 0, 0, 0));

        service.upsertAll(IngestionTrigger.MANUAL, null, null, null, false);

        ArgumentCaptor<RunTally> tally = ArgumentCaptor.forClass(RunTally.class);
        verify(recorder).finish(any(), any(), any(), any(), tally.capture());

        assertThat(tally.getValue().getEmployeesWritten())
                .as("counts come from the committed batch, not a per-record counter")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a rolled-back batch records nothing as written")
    void rolledBackBatchRecordsNothingWritten() {
        stubWorkers(List.of(worker("E-1", "ENG", "100")));
        when(repository.applyBatch(any()))
                .thenThrow(new DataIntegrityViolationException("constraint"));

        service.upsertAll(IngestionTrigger.MANUAL, null, null, null, false);

        ArgumentCaptor<RunTally> tally = ArgumentCaptor.forClass(RunTally.class);
        verify(recorder).finish(any(), any(), any(), any(), tally.capture());

        assertThat(tally.getValue().getEmployeesWritten()).isZero();
        assertThat(tally.getValue().getPayslipsWritten()).isZero();
    }
}
