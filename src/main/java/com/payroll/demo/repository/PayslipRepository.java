package com.payroll.demo.repository;

import com.payroll.demo.domain.Payslip;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PayslipRepository extends JpaRepository<Payslip, Long> {

    @EntityGraph(attributePaths = {"employee", "payPeriod", "lines"})
    List<Payslip> findByEmployeeEmployeeCode(String employeeCode);

}
