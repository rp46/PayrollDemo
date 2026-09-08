package com.payroll.demo.service;

import com.payroll.demo.domain.IngestionRecordType;
import com.payroll.demo.domain.IngestionRunError;

import java.util.ArrayList;
import java.util.List;

/**
 * Running counters and rejected records for one ingestion run.
 *
 * <p>Held in memory during the run and flushed once at the end, so a long feed does not
 * generate a database write per rejected row. The error list is capped: a feed that is
 * broken end to end should not be able to insert a million audit rows on its way out.
 */
public class RunTally {

    /** Beyond this, further rejections are counted but not individually stored. */
    public static final int MAX_RECORDED_ERRORS = 500;

    private int employeesFetched;
    private int employeesWritten;
    private int payslipsFetched;
    private int payslipsWritten;
    private int recordsRejected;
    private int httpAttempts;

    private final List<IngestionRunError> errors = new ArrayList<>();

    public void countEmployeesFetched(int delta) {
        employeesFetched += delta;
    }

    /**
     * Records what the batch actually wrote.
     *
     * <p>Set from the repository's result rather than counted record by record: with one
     * transaction for the whole batch, nothing is "written" until it commits.
     */
    public void recordWritten(int employees, int payslips) {
        employeesWritten = employees;
        payslipsWritten = payslips;
    }

    public void countPayslipsFetched(int delta) {
        payslipsFetched += delta;
    }

    public void countHttpAttempt() {
        httpAttempts++;
    }

    public void reject(IngestionRecordType type, String externalId, String reason) {
        recordsRejected++;
        if (errors.size() < MAX_RECORDED_ERRORS) {
            errors.add(IngestionRunError.of(type, externalId, reason));
        }
    }

    public boolean hasRejections() {
        return recordsRejected > 0;
    }

    public int getEmployeesFetched() {
        return employeesFetched;
    }

    public int getEmployeesWritten() {
        return employeesWritten;
    }

    public int getPayslipsFetched() {
        return payslipsFetched;
    }

    public int getPayslipsWritten() {
        return payslipsWritten;
    }

    public int getRecordsRejected() {
        return recordsRejected;
    }

    public int getHttpAttempts() {
        return httpAttempts;
    }

    public List<IngestionRunError> getErrors() {
        return List.copyOf(errors);
    }
}
