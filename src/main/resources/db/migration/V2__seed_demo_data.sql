-- DEMO DATA ONLY. Delete this migration before using against a real environment.

INSERT INTO departments (code, name) VALUES
    ('ENG', 'Engineering'),
    ('FIN', 'Finance'),
    ('HR',  'Human Resources');

INSERT INTO employees (employee_code, first_name, last_name, email, hire_date, status, department_id, base_salary)
VALUES
    ('E-1001', 'Asha',   'Menon',   'asha.menon@example.com',   DATE '2023-02-13', 'ACTIVE',   (SELECT id FROM departments WHERE code = 'ENG'), 1450000.00),
    ('E-1002', 'Rahul',  'Iyer',    'rahul.iyer@example.com',   DATE '2022-07-01', 'ACTIVE',   (SELECT id FROM departments WHERE code = 'ENG'), 1820000.00),
    ('E-1003', 'Meera',  'Nair',    'meera.nair@example.com',   DATE '2024-01-08', 'ACTIVE',   (SELECT id FROM departments WHERE code = 'FIN'),  980000.00),
    ('E-1004', 'Vikram', 'Shetty',  'vikram.shetty@example.com', DATE '2021-11-15', 'ON_LEAVE', (SELECT id FROM departments WHERE code = 'HR'),   760000.00);

INSERT INTO pay_periods (period_start, period_end, pay_date, status) VALUES
    (DATE '2026-08-01', DATE '2026-08-31', DATE '2026-09-01', 'PAID'),
    (DATE '2026-09-01', DATE '2026-09-30', DATE '2026-10-01', 'OPEN');

-- One worked example: August payslip for E-1001.
INSERT INTO payslips (employee_id, pay_period_id, gross_pay, total_deductions, net_pay, status)
VALUES (
    (SELECT id FROM employees WHERE employee_code = 'E-1001'),
    (SELECT id FROM pay_periods WHERE period_start = DATE '2026-08-01'),
    128333.33, 24166.67, 104166.66, 'PAID'
);

INSERT INTO payslip_lines (payslip_id, component_code, component_type, description, amount)
SELECT p.id, v.code, v.type, v.descr, v.amt
FROM payslips p
JOIN employees e ON e.id = p.employee_id AND e.employee_code = 'E-1001'
CROSS JOIN (VALUES
    ('BASIC',   'EARNING',   'Basic pay',           120833.33),
    ('HRA',     'EARNING',   'House rent allowance',  7500.00),
    ('PF',      'DEDUCTION', 'Provident fund',       14500.00),
    ('TDS',     'DEDUCTION', 'Tax deducted at source', 9666.67)
) AS v(code, type, descr, amt);
