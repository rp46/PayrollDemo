package com.payroll.demo.repository;

import com.payroll.demo.domain.Payslip;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PayslipRepository extends JpaRepository<Payslip, Long> {

    @EntityGraph(attributePaths = {"employee", "payPeriod", "lines"})
    List<Payslip> findByEmployeeEmployeeCode(String employeeCode);

    /** Natural key of a payslip: one per employee per period. */
    @EntityGraph(attributePaths = "lines")
    Optional<Payslip> findByEmployeeIdAndPayPeriodId(Long employeeId, Long payPeriodId);
}
