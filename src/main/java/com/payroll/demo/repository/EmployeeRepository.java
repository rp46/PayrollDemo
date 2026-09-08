package com.payroll.demo.repository;

import com.payroll.demo.domain.Employee;
import com.payroll.demo.domain.EmployeeStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    @EntityGraph(attributePaths = "department")
    List<Employee> findAllByOrderByEmployeeCodeAsc();

    @EntityGraph(attributePaths = "department")
    List<Employee> findByStatusOrderByEmployeeCodeAsc(EmployeeStatus status);

    @EntityGraph(attributePaths = "department")
    Optional<Employee> findByEmployeeCode(String employeeCode);
}
