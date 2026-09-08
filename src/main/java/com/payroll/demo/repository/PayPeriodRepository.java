package com.payroll.demo.repository;

import com.payroll.demo.domain.PayPeriod;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PayPeriodRepository extends JpaRepository<PayPeriod, Long> {

    List<PayPeriod> findAllByOrderByPeriodStartDesc();

}
