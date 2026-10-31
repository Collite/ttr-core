-- The hartland sample's schema for the AU-core-P2 recipe execution spec (RecipeLivePgSpec): the physical tables the
-- recipe-checker fixture model (ttrp-graph `fixtures/recipes/model`, ttr-demo's public model) binds the four `17`
-- recipes' entities to — generated from its `db/*.ttrm` (only the columns the model declares) — plus the two
-- entities the world's automation storage holds, which no er2db binding reaches: the stdlib `obligation` and
-- monitor_inbound's own verdict entity, each read by its own name (LoadSource.unbound).

CREATE TABLE date_dim (
    d_date_sk integer,
    d_date date,
    d_year integer,
    d_moy integer,
    d_qoy integer,
    d_week_seq integer,
    d_day_name char(9),
    d_holiday char(1),
    d_weekend char(1),
    d_working_day boolean
);

CREATE TABLE customer (
    c_customer_sk integer,
    c_customer_id char(16),
    c_current_addr_sk integer,
    c_current_cdemo_sk integer,
    c_current_hdemo_sk integer,
    c_birth_year integer,
    c_center_sk integer,
    c_email varchar(64)
);

CREATE TABLE inventory (
    inv_date_sk integer,
    inv_item_sk integer,
    inv_warehouse_sk integer,
    inv_quantity_on_hand integer
);

CREATE TABLE sales_order (
    so_order_number integer,
    so_channel varchar,
    so_customer_sk integer,
    so_placed_date date,
    so_delivered_date date,
    so_warehouse_sk integer,
    so_value numeric(12,2),
    so_state varchar,
    so_held_for varchar,
    so_placed_by varchar
);

CREATE TABLE sales_line (
    sl_order_number integer,
    sl_line_no integer,
    sl_item_sk integer,
    sl_quantity integer,
    sl_unit_price numeric(12,2),
    sl_ext_sales_price numeric(12,2),
    sl_warehouse_sk integer,
    sl_sold_date date,
    sl_customer_sk integer,
    sl_channel varchar
);

CREATE TABLE price_list (
    pl_item_sk integer,
    pl_channel varchar,
    pl_valid_from date,
    pl_price numeric(12,2),
    pl_valid_to date
);

CREATE TABLE customer_price (
    cp_item_sk integer,
    cp_customer_sk integer,
    cp_valid_from date,
    cp_price numeric(12,2),
    cp_valid_to date
);

CREATE TABLE invoice (
    inv_invoice_id integer,
    inv_customer_sk integer,
    inv_order_number integer,
    inv_issued date,
    inv_due date,
    inv_amount numeric(12,2),
    inv_paid numeric(12,2),
    inv_status varchar
);

CREATE TABLE warehouse_outage (
    wo_warehouse_sk integer,
    wo_from_date date,
    wo_to_date date,
    wo_reason varchar
);

CREATE TABLE return_request_line (
    rl_line_id integer,
    rl_request_number integer,
    rl_line_no integer,
    rl_item_sk integer,
    rl_quantity integer,
    rl_unit_price numeric(12,2),
    rl_reason varchar,
    rl_requested_resolution varchar,
    rl_requested_variant_sk integer,
    rl_state varchar,
    rl_way varchar,
    rl_customer_sk integer
);

CREATE TABLE return_receipt (
    rc_receipt_id integer,
    rc_line_id integer,
    rc_received_date date,
    rc_item_sk integer,
    rc_quantity integer,
    rc_condition varchar
);

CREATE TABLE credit_note (
    cn_credit_note_id integer,
    cn_line_id integer,
    cn_customer_sk integer,
    cn_issued_date date,
    cn_amount numeric(12,2),
    cn_status varchar
);

CREATE TABLE refund_payment (
    rp_payment_id integer,
    rp_credit_note_id integer,
    rp_paid_date date,
    rp_amount numeric(12,2)
);

CREATE TABLE replacement_shipment (
    rs_shipment_id integer,
    rs_line_id integer,
    rs_item_sk integer,
    rs_quantity integer,
    rs_shipped_date date
);

CREATE TABLE return_request (
    rr_request_number integer,
    rr_customer_sk integer,
    rr_order_number integer,
    rr_channel varchar,
    rr_requested_date date,
    rr_state varchar
);

-- The computed return_request view reads its base header (MD-hop model evolution).
CREATE VIEW return_request_hdr AS SELECT * FROM return_request;

-- automation stdlib (ttr-parser automation/automation-stdlib.ttrm)
CREATE TABLE obligation (
    id text,
    recipe text,
    run text,
    entity text,
    key text,
    kind text,
    due date,
    state text,
    fulfilled_by text,
    fulfilled_at timestamp,
    created_at timestamp
);

-- monitor_inbound's verdict entity (RecipeCatalog: run, as_of, the key, route, verdict_reason, …)
CREATE TABLE monitor_inbound_verdict (
    run text,
    as_of timestamp,
    line_id integer,
    route text,
    verdict_reason text,
    parameters_fp text,
    run_as text,
    written_at timestamp
);
