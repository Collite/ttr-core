-- review-154 — invented rows for the er lane's live checks on the parity world (`ErStatementLaneSpec`): the tables are
-- created from the world's db tier, these rows go in after. Sale 5 has no channel: `revenue where WebSale` plus
-- `revenue where not WebSale` must still be the total (E4), so the NULL row counts as "not web".

INSERT INTO customer (sk, name, region) VALUES (1, 'Alder', 'west'), (2, 'Birch', 'east'), (3, 'Cedar', NULL);

INSERT INTO sale (sale_id, channel, customer, sold_on, due_on, created_at, ext_price, quantity, unit_price, stock, top_price, refund) VALUES
    (1, 'web',   1, DATE '2026-01-05', DATE '2026-02-05', TIMESTAMP '2026-01-05 09:30:00', 100.00, 1, 100.00, 7, 100.00, 0),
    (2, 'store', 2, DATE '2026-01-10', DATE '2026-02-10', TIMESTAMP '2026-01-10 14:00:00', 250.00, 2, 125.00, 5, 125.00, 10),
    (3, 'web',   3, DATE '2026-02-01', DATE '2026-03-01', TIMESTAMP '2026-02-01 08:15:00',  40.00, 4,  10.00, 9,  10.00, 0),
    (4, 'store', 1, DATE '2026-02-20', DATE '2026-03-20', TIMESTAMP '2026-02-20 17:45:00',  60.00, 3,  20.00, 2,  20.00, 5),
    (5, NULL,    2, DATE '2026-03-01', DATE '2026-04-01', TIMESTAMP '2026-03-01 11:00:00',  75.00, 1,  75.00, 4,  75.00, 0),
    (6, 'web',   2, DATE '2025-12-30', DATE '2026-01-30', TIMESTAMP '2025-12-30 10:00:00', 999.00, 9, 111.00, 1, 111.00, 0);

INSERT INTO shipment (shipment_id, sale_id, carrier) VALUES (1, 1, 'dhl'), (2, 1, 'ups'), (3, 2, 'dhl');
