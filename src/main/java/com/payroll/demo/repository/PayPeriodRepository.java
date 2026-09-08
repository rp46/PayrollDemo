package com.payroll.demo.repository;

import com.payroll.demo.domain.PayPeriod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PayPeriodRepository extends JpaRepository<PayPeriod, Long> {

    List<PayPeriod> findAllByOrderByPeriodStartDesc();

    Optional<PayPeriod> findByPeriodStartAndPeriodEnd(LocalDate periodStart, LocalDate periodEnd);
}
