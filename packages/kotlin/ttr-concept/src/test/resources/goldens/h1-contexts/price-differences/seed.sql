-- CN-P3 S3.7 — invented rows for the live row-set check of `price-differences` (A-CN-15): AU's island (`au.sql`) and the
-- `MispricedLine` rendering (`expected.sql`), both translated to PostgreSQL, must return the same rows on this seed.
-- Idempotent; run inside the live suite's own schema (S3.8 T4). The world's tables, the columns the two queries read.

DROP TABLE IF EXISTS "order";
CREATE TABLE "order" (
    order_number         integer PRIMARY KEY,
    channel              text,
    customer             integer,
    placed_date          date,
    delivered_date       date,
    fulfilling_warehouse integer,
    value                numeric,
    state                text
);
INSERT INTO "order" (order_number, channel, customer, placed_date, delivered_date, fulfilling_warehouse, value, state) VALUES
    (1001, 'web',   1, DATE '2026-03-10', NULL, 5, 50.00, 'placed'),
    (1002, 'store', 2, DATE '2026-03-11', NULL, 5, 60.00, 'held'),
    (1003, 'web',   1, DATE '2026-03-12', NULL, 5, 10.00, 'shipped');   -- not pending: none of its lines counts

DROP TABLE IF EXISTS sales_line;
CREATE TABLE sales_line (
    order_number integer,
    line_no      integer,
    item         integer,
    quantity     integer,
    unit_price   numeric,
    warehouse    integer,
    sold_date    date,
    customer     integer,
    channel      text,
    PRIMARY KEY (order_number, line_no)
);
INSERT INTO sales_line (order_number, line_no, item, quantity, unit_price, warehouse, sold_date, customer, channel) VALUES
    (1001, 1, 10, 2,  9.99,  5, DATE '2026-03-10', 1, 'web'),    -- the individual price, exactly: fine
    (1001, 2, 11, 1, 20.00,  5, DATE '2026-03-10', 1, 'web'),    -- no individual price; list 19.50: MISPRICED
    (1001, 3, 12, 1,  5.004, 5, DATE '2026-03-10', 1, 'web'),    -- list 5.00, off by 0.004 < tolerance: fine
    (1002, 1, 10, 3, 10.50,  5, DATE '2026-03-11', 2, 'store'),  -- list 10.00: MISPRICED
    (1002, 2, 13, 1,  7.00,  5, DATE '2026-03-11', 2, 'store'),  -- no price at all: MISPRICED (list_price is null)
    (1002, 3, 11, 1, 18.00,  5, DATE '2026-03-11', 2, 'store'),  -- 18.00 expired 02-28, 18.50 from 03-01: MISPRICED
    (1003, 1, 10, 1,  1.00,  5, DATE '2026-03-12', 1, 'web');    -- order not pending

DROP TABLE IF EXISTS customer_price;
CREATE TABLE customer_price (
    item       integer,
    customer   integer,
    valid_from date,
    price      numeric,
    valid_to   date,
    PRIMARY KEY (item, customer, valid_from)
);
INSERT INTO customer_price (item, customer, valid_from, price, valid_to) VALUES
    (10, 1, DATE '2026-01-01',  9.99, NULL),
    (11, 2, DATE '2025-01-01', 17.00, DATE '2025-12-31');          -- expired before the order: not applicable

DROP TABLE IF EXISTS price_list;
CREATE TABLE price_list (
    item       integer,
    channel    text,
    valid_from date,
    price      numeric,
    valid_to   date,
    PRIMARY KEY (item, channel, valid_from)
);
INSERT INTO price_list (item, channel, valid_from, price, valid_to) VALUES
    (10, 'web',   DATE '2026-01-01',  9.50, NULL),
    (10, 'store', DATE '2026-01-01', 10.00, NULL),
    (11, 'web',   DATE '2026-01-01', 19.50, NULL),
    (11, 'store', DATE '2025-06-01', 18.00, DATE '2026-02-28'),
    (11, 'store', DATE '2026-03-01', 18.50, NULL),
    (12, 'web',   DATE '2026-01-01',  5.00, NULL);

-- Expected (tolerance = 0.01), both queries — order_number, line_no, item, unit_price, list_price:
--   1001, 2, 11, 20.00, 19.50
--   1002, 1, 10, 10.50, 10.00
--   1002, 2, 13,  7.00, NULL
--   1002, 3, 11, 18.00, 18.50
