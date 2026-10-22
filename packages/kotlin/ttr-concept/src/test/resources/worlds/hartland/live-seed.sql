-- CN-P3 S3.4 T7 — invented rows for the live run of the Hartland er goldens (`ConceptLiveSpec`): the tables are created
-- from the world's db tier, these rows go in after (Hartland's public demo names; nothing from a client). The live
-- suite binds `as_of` to 2026-03-15, `window_lo` to 2025-03-15, `as_of_hi` to 2026-03-16 and `tolerance` to 0.01.

INSERT INTO customer (sk, customer_id) VALUES (1, 'C-001'), (2, 'C-002'), (3, 'C-003');
INSERT INTO item (sk, item_id) VALUES (10, 'I-010'), (11, 'I-011'), (12, 'I-012'), (13, 'I-013');
INSERT INTO warehouse (sk, warehouse_name) VALUES (5, 'Hartland North'), (6, 'Hartland South');

INSERT INTO "order" (order_number, channel, customer, placed_date, delivered_date, fulfilling_warehouse, value, state) VALUES
    (1001, 'web',   1, DATE '2026-03-10', NULL,              5, 50.00, 'placed'),
    (1002, 'store', 2, DATE '2026-03-11', NULL,              6, 60.00, 'held'),
    (1003, 'web',   1, DATE '2025-01-12', DATE '2025-01-15', 5, 10.00, 'shipped');

INSERT INTO sales_line (order_number, line_no, item, quantity, unit_price, warehouse, sold_date, customer, channel) VALUES
    (1001, 1, 10, 2,  9.99, 5, DATE '2026-03-10', 1, 'web'),     -- the individual price: fine
    (1001, 2, 11, 5, 20.00, 5, DATE '2026-03-10', 1, 'web'),     -- list 19.50: mispriced; 2 on hand of 5: short
    (1002, 1, 10, 3, 10.50, 6, DATE '2026-03-11', 2, 'store'),   -- list 10.00: mispriced; warehouse 6 is out today
    (1003, 1, 12, 1,  5.00, 5, DATE '2025-01-12', 1, 'web');     -- the order is not pending

INSERT INTO inventory (as_of_date, item, warehouse, quantity_on_hand) VALUES
    (DATE '2026-03-01', 10, 5, 1),
    (DATE '2026-03-14', 10, 5, 4),                               -- the latest for (10, 5)
    (DATE '2026-03-14', 11, 5, 2),
    (DATE '2026-03-14', 10, 6, 9);

INSERT INTO warehouse_outage (warehouse, from_date, to_date, reason) VALUES
    (6, DATE '2026-03-14', DATE '2026-03-16', 'flood'),          -- contains 2026-03-15 (the last day included)
    (5, DATE '2026-02-01', DATE '2026-02-02', 'power');

INSERT INTO price_list (item, channel, valid_from, price, valid_to) VALUES
    (10, 'web',   DATE '2026-01-01',  9.50, NULL),
    (10, 'store', DATE '2026-01-01', 10.00, NULL),
    (11, 'web',   DATE '2026-01-01', 19.50, NULL);

INSERT INTO customer_price (item, customer, valid_from, price, valid_to) VALUES
    (10, 1, DATE '2026-01-01', 9.99, NULL);

INSERT INTO invoice (invoice_id, customer, order_number, issued, due, amount, paid, status) VALUES
    (501, 1, 1003, DATE '2025-01-15', DATE '2025-02-15', 10.00, 0.00, 'open'),     -- overdue: customer 1 is a debtor
    (502, 2, 1002, DATE '2026-03-11', DATE '2026-04-11', 60.00, 0.00, 'open'),     -- not due yet
    (503, 3, 1003, DATE '2025-01-15', DATE '2025-02-15', 10.00, 10.00, 'paid');

INSERT INTO channel_sales (sale_id, channel, customer, item, sold_date, ext_sales_price, quantity) VALUES
    (1, 'web',   1, 10, DATE '2026-03-10', 19.98, 2),
    (2, 'store', 2, 10, DATE '2026-03-11', 31.50, 3);

INSERT INTO return_request (request_id, order_number, customer, requested_date, state) VALUES
    (901, 1003, 1, DATE '2025-02-01', 'open');

INSERT INTO return_request_line (request_id, line_no, item, return_quantity, return_unit_price, reason, line_state, amount) VALUES
    (901, 1, 12, 1, 5.00, 'damaged',    'open', 5.00),            -- bought on the order
    (901, 2, 13, 1, 7.00, 'wrong item', 'open', 7.00);            -- not on the order: a foreign return line
