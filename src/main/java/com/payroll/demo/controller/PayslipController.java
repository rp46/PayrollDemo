package com.payroll.demo.controller;

import com.payroll.demo.domain.PayslipStatus;
import com.payroll.demo.dto.PayrollDtos.PayDetailsView;
import com.payroll.demo.dto.PayrollDtos.PayslipView;
import com.payroll.demo.service.PayslipQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Read access to payslips and to an employee's pay details.
 *
 * <p>Separate from {@link PayrollController} so payslips have one owner: the employee,
 * period and register views are all the same resource seen from different directions.
 *
 * <p>An unknown employee, payslip or period is a 404 rather than an empty result — asking
 * for the payslips of someone who does not exist is a different situation from asking for
 * the payslips of someone who has none, and the caller should be able to tell them apart.
 */
@RestController
@RequestMapping("/api")
public class PayslipController {

    private final PayslipQueryService payslipQueryService;

    public PayslipController(PayslipQueryService payslipQueryService) {
        this.payslipQueryService = payslipQueryService;
    }

    /**
     * An employee's payslips, newest period first.
     *
     * @param from   optional; include payslips whose period ends on or after this date
     * @param to     optional; include payslips whose period starts on or before this date
     * @param status optional; only payslips in this status
     */
    @GetMapping("/employees/{employeeCode}/payslips")
    public List<PayslipView> payslipsForEmployee(
            @PathVariable String employeeCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) PayslipStatus status) {
        return payslipQueryService.listPayslipsFor(employeeCode, from, to, status);
    }

    /** The most recent payslip for an employee. 404 when they have none yet. */
    @GetMapping("/employees/{employeeCode}/payslips/latest")
    public PayslipView latestPayslip(@PathVariable String employeeCode) {
        return payslipQueryService.latestPayslipFor(employeeCode);
    }

    /**
     * An employee's pay details: standing compensation plus totals over the payslips in scope.
     *
     * <p>Takes the same optional filters as the payslip listing, so the totals can be
     * scoped to a financial year or to what has actually been paid. The filters are echoed
     * back on the response.
     */
    @GetMapping("/employees/{employeeCode}/pay-details")
    public PayDetailsView payDetails(
            @PathVariable String employeeCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) PayslipStatus status) {
        return payslipQueryService.payDetailsFor(employeeCode, from, to, status);
    }

    /** A single payslip by id. */
    @GetMapping("/payslips/{id}")
    public PayslipView payslip(@PathVariable Long id) {
        return payslipQueryService.findPayslip(id);
    }

    /** Every payslip in a pay period: the payroll register for that run. */
    @GetMapping("/pay-periods/{payPeriodId}/payslips")
    public List<PayslipView> payslipsForPeriod(@PathVariable Long payPeriodId) {
        return payslipQueryService.listPayslipsForPeriod(payPeriodId);
    }
}
