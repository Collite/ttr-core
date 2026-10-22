-- CN-P3 S3.3 T7 — E-0's live rows, added on top of ttrp-conform's md_seed.sql (which leaves January 2026 empty). Invented
-- rows; the "excluded" ones prove each coordinate discriminates. Not in md_seed.sql itself: ttrp-conform's write round
-- trip spreads Kaufland's seed total (1885) and would read any added Kaufland row.
INSERT INTO f_sales (customer_name, sale_date, net, gross) VALUES
    ('Kaufland', DATE '2026-01-05', 120.00, 140.00),  -- counted
    ('Kaufland', DATE '2026-01-28',  30.50,  36.00),  -- counted   → SUM = 150.50
    ('Kaufland', DATE '2026-02-02', 400.00, 450.00),  -- excluded: time.month (February)
    ('Kaufland', DATE '2025-01-15', 900.00, 990.00),  -- excluded: time.year (January 2025)
    ('Lidl',     DATE '2026-01-05',  75.00,  80.00);  -- excluded: customer.name
INSERT INTO d_calendar (cal_date, cal_month) VALUES
    (DATE '2026-01-05', 1),
    (DATE '2026-01-28', 1),
    (DATE '2026-02-02', 2),
    (DATE '2025-01-15', 1);
-- Expected: sales[customer.name: "Kaufland", time.month: 1, time.year: 2026].net @ sum = 150.50
