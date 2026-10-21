-- The hartland sample for the AU-core-P2 recipe execution spec (RecipeLivePgSpec): a few rows per table, planted so
-- every route of the four `17` recipes takes a known set of keys at `:as_of` = 2026-11-02 08:00. The expected keys
-- are in RecipeLivePgSpec; the reasoning per row is beside it here.

-- Calendar 2025-09-01 … 2026-12-31: the TPC-DS day number as the sk, Saturday + Sunday the weekend, 2026-10-28 (a
-- Czech holiday) a holiday — so the working days from 2026-10-25 to 2026-11-02 are 26, 27, 29, 30 Oct and 2 Nov.
-- Spelled for Postgres and DuckDB alike (RecipeDuckDbSpec loads this same file): no TO_CHAR, no integer `/`.
INSERT INTO date_dim (d_date_sk, d_date, d_year, d_moy, d_qoy, d_week_seq, d_day_name, d_holiday, d_weekend, d_working_day)
SELECT (d - DATE '1900-01-02') + 2415022, d, EXTRACT(YEAR FROM d), EXTRACT(MONTH FROM d), EXTRACT(QUARTER FROM d),
       FLOOR((d - DATE '1900-01-02') / 7.0) + 1,
       CASE EXTRACT(ISODOW FROM d) WHEN 1 THEN 'Monday' WHEN 2 THEN 'Tuesday' WHEN 3 THEN 'Wednesday'
           WHEN 4 THEN 'Thursday' WHEN 5 THEN 'Friday' WHEN 6 THEN 'Saturday' ELSE 'Sunday' END,
       CASE WHEN d = DATE '2026-10-28' THEN 'Y' ELSE 'N' END,
       CASE WHEN EXTRACT(ISODOW FROM d) IN (6, 7) THEN 'Y' ELSE 'N' END,
       EXTRACT(ISODOW FROM d) NOT IN (6, 7) AND d <> DATE '2026-10-28'
FROM (SELECT CAST(g.day AS DATE) AS d
      FROM generate_series(DATE '2025-09-01', DATE '2026-12-31', INTERVAL '1 day') AS g(day)) x;

-- Customers: 10 and 20 and 50, all served from distribution center 1.
INSERT INTO customer (c_customer_sk, c_customer_id, c_birth_year, c_center_sk) VALUES
    (10, 'C10', 1980, 1), (20, 'C20', 1975, 1), (50, 'C50', 1990, 1);

-- ---- release_order (H-1) — orders 1–8 ------------------------------------------------------------------------
-- 1 release (in stock, list price, value 100) · 2 release_confirmed (value 60 000 > 50 000) · 3 hold_sales (item 102:
-- 3 on hand at the LATEST snapshot, 5 ordered) · 4 hold_sales (held; 12.00 against the list's 10.00) · 5 hold_ar
-- (customer 50's invoice 7001 fell due 2026-10-15) · 6 released, no candidate · 7 hold_sales (center 2 is out
-- 2026-11-01…05: an outage, though 100 are on hand) · 8 release (customer 20's own price 9.00 is the 9.00 charged).
-- Orders 11–14 are delivered orders the returns below refer to.
INSERT INTO sales_order (so_order_number, so_channel, so_customer_sk, so_placed_date, so_delivered_date, so_warehouse_sk, so_value, so_state) VALUES
    (1, 'web', 10, DATE '2026-11-01', NULL, 1, 100.00, 'placed'),
    (2, 'web', 10, DATE '2026-11-01', NULL, 1, 60000.00, 'placed'),
    (3, 'web', 10, DATE '2026-11-01', NULL, 1, 200.00, 'placed'),
    (4, 'web', 10, DATE '2026-11-01', NULL, 1, 30.00, 'held'),
    (5, 'web', 50, DATE '2026-11-01', NULL, 1, 40.00, 'placed'),
    (6, 'web', 10, DATE '2026-10-30', NULL, 1, 50.00, 'released'),
    (7, 'web', 10, DATE '2026-11-01', NULL, 2, 20.00, 'placed'),
    (8, 'web', 20, DATE '2026-11-01', NULL, 1, 9.00, 'placed'),
    (11, 'web', 10, DATE '2026-10-10', DATE '2026-10-20', 1, 30.00, 'released'),
    (12, 'web', 10, DATE '2026-08-01', DATE '2026-09-01', 1, 10.00, 'released'),
    (13, 'web', 10, DATE '2026-06-01', DATE '2026-06-05', 1, 2000.00, 'released'),
    (14, 'web', 20, DATE '2026-10-20', DATE '2026-10-25', 1, 20.00, 'released');

INSERT INTO sales_line (sl_order_number, sl_line_no, sl_item_sk, sl_quantity, sl_unit_price, sl_ext_sales_price, sl_warehouse_sk, sl_sold_date, sl_customer_sk, sl_channel) VALUES
    (1, 1, 101, 2, 10.00, 20.00, 1, DATE '2026-11-01', 10, 'web'),
    (2, 1, 101, 1, 10.00, 10.00, 1, DATE '2026-11-01', 10, 'web'),
    (3, 1, 102, 5, 20.00, 100.00, 1, DATE '2026-11-01', 10, 'web'),
    (4, 1, 101, 1, 12.00, 12.00, 1, DATE '2026-11-01', 10, 'web'),
    (5, 1, 101, 1, 10.00, 10.00, 1, DATE '2026-11-01', 50, 'web'),
    (6, 1, 101, 1, 10.00, 10.00, 1, DATE '2026-10-30', 10, 'web'),
    (7, 1, 103, 1, 5.00, 5.00, 2, DATE '2026-11-01', 10, 'web'),
    (8, 1, 101, 1, 9.00, 9.00, 1, DATE '2026-11-01', 20, 'web'),
    (11, 1, 101, 1, 10.00, 10.00, 1, DATE '2026-10-10', 10, 'web'),
    (11, 2, 102, 1, 20.00, 20.00, 1, DATE '2026-10-10', 10, 'web'),
    (12, 1, 101, 1, 10.00, 10.00, 1, DATE '2026-08-01', 10, 'web'),
    (13, 1, 103, 400, 5.00, 2000.00, 1, DATE '2026-06-01', 10, 'web'),
    (14, 1, 101, 2, 10.00, 20.00, 1, DATE '2026-10-20', 20, 'web');

-- Inventory snapshots by date sk: an older and the latest — `latest` must read the latest (item 102: 10, then 3).
INSERT INTO inventory (inv_date_sk, inv_item_sk, inv_warehouse_sk, inv_quantity_on_hand) VALUES
    ((DATE '2026-10-26' - DATE '1900-01-02') + 2415022, 101, 1, 50),
    ((DATE '2026-11-02' - DATE '1900-01-02') + 2415022, 101, 1, 40),
    ((DATE '2026-10-26' - DATE '1900-01-02') + 2415022, 102, 1, 10),
    ((DATE '2026-11-02' - DATE '1900-01-02') + 2415022, 102, 1, 3),
    ((DATE '2026-11-02' - DATE '1900-01-02') + 2415022, 103, 2, 100),
    ((DATE '2026-11-02' - DATE '1900-01-02') + 2415022, 103, 1, 0);

INSERT INTO price_list (pl_item_sk, pl_channel, pl_valid_from, pl_price, pl_valid_to) VALUES
    (101, 'web', DATE '2026-01-01', 10.00, NULL),
    (102, 'web', DATE '2026-01-01', 20.00, NULL),
    (103, 'web', DATE '2026-01-01', 5.00, NULL);

INSERT INTO customer_price (cp_item_sk, cp_customer_sk, cp_valid_from, cp_price, cp_valid_to) VALUES
    (101, 20, DATE '2026-01-01', 9.00, NULL);

INSERT INTO invoice (inv_invoice_id, inv_customer_sk, inv_order_number, inv_issued, inv_due, inv_amount, inv_paid, inv_status) VALUES
    (7001, 50, NULL, DATE '2026-09-15', DATE '2026-10-15', 500.00, 0.00, 'open'),
    (7002, 10, NULL, DATE '2026-10-20', DATE '2026-11-20', 100.00, 0.00, 'open');

INSERT INTO warehouse_outage (wo_warehouse_sk, wo_from_date, wo_to_date, wo_reason) VALUES
    (2, DATE '2026-11-01', DATE '2026-11-05', 'flood');

-- ---- decide_return (R2a) — lines 901–906, `received` ---------------------------------------------------------
-- 901 refund_no_return (order 11, delivered 2026-10-20, damaged, 10.00 < 15) · 902 reject_late (order 12, delivered
-- 2026-09-01, before 2026-10-03) · 903 reject_not_ours (order 99 does not exist) · 904 refund_after_return (an
-- exchange for 103, of which center 1 has 0) · 905 to_customer_care (request 805 is worth 600 > 500; customer 10
-- returned 690 of 2 197 bought this year — 0.31) · 906 reject_abuse (customer 20 returns 20 of 29 bought — 0.69) ·
-- 907 reject_not_ours (order 11 exists and was delivered, but carries no item 103 — the LEFT JOIN's right key is NULL,
-- review-119 B1).
INSERT INTO return_request (rr_request_number, rr_customer_sk, rr_order_number, rr_channel, rr_requested_date, rr_state) VALUES
    (801, 10, 11, 'web', DATE '2026-10-25', 'received'),
    (802, 10, 12, 'web', DATE '2026-10-25', 'received'),
    (803, 10, 99, 'web', DATE '2026-10-25', 'received'),
    (804, 10, 11, 'web', DATE '2026-10-26', 'received'),
    (805, 10, 11, 'web', DATE '2026-10-27', 'received'),
    (806, 20, 14, 'web', DATE '2026-10-28', 'received'),
    (807, 10, 11, 'web', DATE '2026-10-28', 'received'),
    (811, 10, 11, 'web', DATE '2026-10-05', 'decided'),
    (812, 10, 11, 'web', DATE '2026-10-01', 'decided'),
    (813, 10, 11, 'web', DATE '2026-10-05', 'decided'),
    (814, 10, 11, 'web', DATE '2026-10-05', 'decided'),
    (815, 10, 11, 'web', DATE '2026-10-30', 'decided'),
    (816, 10, 11, 'web', DATE '2026-10-03', 'decided'),
    (821, 10, 11, 'web', DATE '2026-10-15', 'decided'),
    (822, 10, 11, 'web', DATE '2026-10-15', 'decided'),
    (823, 10, 11, 'web', DATE '2026-10-15', 'decided'),
    (824, 10, 11, 'web', DATE '2026-10-20', 'decided');

INSERT INTO return_request_line (rl_line_id, rl_request_number, rl_line_no, rl_item_sk, rl_quantity, rl_unit_price, rl_reason, rl_requested_resolution, rl_requested_variant_sk, rl_state, rl_way, rl_customer_sk) VALUES
    (901, 801, 1, 101, 1, 10.00, 'damaged', 'refund', NULL, 'received', NULL, 10),
    (902, 802, 1, 101, 1, 10.00, 'wrong_size', 'refund', NULL, 'received', NULL, 10),
    (903, 803, 1, 101, 1, 10.00, 'damaged', 'refund', NULL, 'received', NULL, 10),
    (904, 804, 1, 102, 2, 20.00, 'wrong_size', 'exchange', 103, 'received', NULL, 10),
    (905, 805, 1, 101, 60, 10.00, 'wrong_size', 'refund', NULL, 'received', NULL, 10),
    (906, 806, 1, 101, 2, 10.00, 'wrong_size', 'refund', NULL, 'received', NULL, 20),
    (907, 807, 1, 103, 1, 10.00, 'wrong_size', 'refund', NULL, 'received', NULL, 10),
    -- monitor_inbound (R2b): awaiting the goods back, each with an open `inbound` obligation (below)
    (911, 811, 1, 101, 1, 10.00, 'wrong_size', 'refund', NULL, 'awaiting_goods', 'refund_after_return', 10),
    (912, 812, 1, 101, 1, 10.00, 'wrong_size', 'refund', NULL, 'awaiting_goods', 'refund_after_return', 10),
    (913, 813, 1, 101, 1, 10.00, 'wrong_size', 'exchange', 103, 'awaiting_goods', 'exchange', 10),
    (914, 814, 1, 101, 1, 10.00, 'wrong_size', 'refund', NULL, 'awaiting_goods', 'refund_after_return', 10),
    (915, 815, 1, 101, 1, 10.00, 'wrong_size', 'refund', NULL, 'awaiting_goods', 'refund_after_return', 10),
    (916, 816, 1, 101, 1, 10.00, 'wrong_size', 'refund', NULL, 'awaiting_goods', 'refund_after_return', 10),
    -- settle (R2c): settling, or the goods back
    (921, 821, 1, 101, 1, 10.00, 'damaged', 'refund', NULL, 'settling', 'refund_no_return', 10),
    (922, 822, 1, 101, 1, 10.00, 'damaged', 'replacement', NULL, 'settling', 'replacement_no_return', 10),
    (923, 823, 1, 101, 1, 10.00, 'wrong_size', 'refund', NULL, 'goods_received', 'refund_after_return', 10),
    (924, 824, 1, 101, 1, 10.00, 'damaged', 'refund', NULL, 'settling', 'refund_no_return', 10);

-- ---- monitor_inbound (R2b) — lines 911–915 -------------------------------------------------------------------
-- 911 remind (open 28 days > 14, never reminded) · 912 expired (open 32 days > 30) · 913 complete (1 of 1 back ok) ·
-- 914 damaged (the one back is damaged) · 915 nothing (open 3 days: `else: none`, no verdict row) · 916 remind (created
-- at MIDNIGHT 2026-10-03: the days in (10-03, 11-02] are 30, not > 30 — counting 10-03 too, `[a, b]`, would make it
-- 31 and expired; review-119 m1).
-- ---- settle (R2c) — lines 921–923 ----------------------------------------------------------------------------
-- 921 money_overdue (a refund with no payment; its credit-note obligation fell due 2026-10-25 — 5 working days
-- late) · 922 goods_shipped + closed (its replacement shipped 2026-10-31) · 923 credit_note_issued + money_received +
-- closed (note 3001, paid 2026-11-01) · 924 money_overdue (its credit note fell due Tuesday 2026-10-27, a WORKING
-- day: 29, 30 Oct and 2 Nov — 3 working days late; counting the 27th too, `[a, b]`, would make it 4; review-119 m1).
INSERT INTO obligation (id, recipe, run, entity, key, kind, due, state, created_at) VALUES
    ('ob-911', 'decide_return', 'r0', 'return_request_line', '911', 'inbound', DATE '2026-11-10', 'open', TIMESTAMP '2026-10-05 09:00'),
    ('ob-912', 'decide_return', 'r0', 'return_request_line', '912', 'inbound', DATE '2026-11-10', 'open', TIMESTAMP '2026-10-01 09:00'),
    ('ob-913', 'decide_return', 'r0', 'return_request_line', '913', 'inbound', DATE '2026-11-10', 'open', TIMESTAMP '2026-10-05 09:00'),
    ('ob-914', 'decide_return', 'r0', 'return_request_line', '914', 'inbound', DATE '2026-11-10', 'open', TIMESTAMP '2026-10-05 09:00'),
    ('ob-915', 'decide_return', 'r0', 'return_request_line', '915', 'inbound', DATE '2026-11-10', 'open', TIMESTAMP '2026-10-30 09:00'),
    ('ob-916', 'decide_return', 'r0', 'return_request_line', '916', 'inbound', DATE '2026-11-10', 'open', TIMESTAMP '2026-10-03 00:00'),
    ('ob-921', 'decide_return', 'r0', 'return_request_line', '921', 'credit_note', DATE '2026-10-25', 'open', TIMESTAMP '2026-10-15 09:00'),
    ('ob-922', 'decide_return', 'r0', 'return_request_line', '922', 'replacement', DATE '2026-11-05', 'open', TIMESTAMP '2026-10-15 09:00'),
    ('ob-923', 'monitor_inbound', 'r0', 'return_request_line', '923', 'payment', DATE '2026-11-03', 'open', TIMESTAMP '2026-10-30 09:00'),
    ('ob-924', 'decide_return', 'r0', 'return_request_line', '924', 'credit_note', DATE '2026-10-27', 'open', TIMESTAMP '2026-10-20 09:00');

INSERT INTO return_receipt (rc_receipt_id, rc_line_id, rc_received_date, rc_item_sk, rc_quantity, rc_condition) VALUES
    (1, 913, DATE '2026-10-30', 101, 1, 'ok'),
    (2, 914, DATE '2026-10-31', 101, 1, 'damaged');

INSERT INTO credit_note (cn_credit_note_id, cn_line_id, cn_customer_sk, cn_issued_date, cn_amount, cn_status) VALUES
    (3001, 923, 10, DATE '2026-10-30', 10.00, 'issued');

INSERT INTO refund_payment (rp_payment_id, rp_credit_note_id, rp_paid_date, rp_amount) VALUES
    (4001, 3001, DATE '2026-11-01', 10.00);

INSERT INTO replacement_shipment (rs_shipment_id, rs_line_id, rs_item_sk, rs_quantity, rs_shipped_date) VALUES
    (5001, 922, 101, 1, DATE '2026-10-31');
