-- CN-P3 S3.3 T7 — ActiveCustomer's live rows (E-0's, on top of ttrp-conform's md_seed.sql): the composed semi-join
-- (⚑CN-P9) runs on PostgreSQL. Every January 2026 buyer is active as of 2026-01-31 (the window covers January), so the
-- answer is January 2026's net over all customers — the sum of the per-customer sums the composition re-aggregates.
INSERT INTO f_sales (customer_name, sale_date, net, gross) VALUES
    ('Kaufland', DATE '2026-01-05', 120.00, 140.00),  -- counted
    ('Kaufland', DATE '2026-01-28',  30.50,  36.00),  -- counted
    ('Kaufland', DATE '2026-02-02', 400.00, 450.00),  -- excluded: time.month (February)
    ('Kaufland', DATE '2025-01-15', 900.00, 990.00),  -- excluded: time.year (January 2025)
    ('Lidl',     DATE '2026-01-05',  75.00,  80.00);  -- counted   → SUM = 225.50
INSERT INTO d_calendar (cal_date, cal_month) VALUES
    (DATE '2026-01-05', 1),
    (DATE '2026-01-28', 1),
    (DATE '2026-02-02', 2),
    (DATE '2025-01-15', 1);
-- Expected: January 2026's net of the customers active as of 2026-01-31 = 120.00 + 30.50 + 75.00 = 225.50
