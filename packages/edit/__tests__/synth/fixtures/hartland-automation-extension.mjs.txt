// AU-hartland-P0 · S0.1 — the H-1/H-2 data extension of the model (D-79, D-81…D-84, D-127). S0.2's world moved to
// automation/tests/package.test.mjs at P2. Mocked/unit: parses the whole model/ tree as one project (project-harness.mjs), no live DB.
// Run: node --test model/tests/automation-extension.test.mjs (from the hartland repo root).
//
// The tables below ARE the contract — `project/kantheon/features/automation-agents/implementation/hartland/
// contracts.md` §1–§2 over `design/17-recipes-retyped.md` §0 (the entity table the recipes are written
// against). A change here is a change of the contract: amend those two files in the same change.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadHartlandProject, ACCEPTED_RESIDUAL_CODES } from './project-harness.mjs';

const MONEY = 'decimal(12,2)';

// ── physical (contracts §1): table → primary key + columns [name, type] ─────────────────────────────
// Types in TTR-M spelling. `boolean` is `bool` (TTR.g4 has it); `bigserial` is `int` (TTR-M has no
// bigint); `timestamptz` is `timestamp`; the DDL's `text` is `varchar` (the dims.ttrm precedent).
const TABLES = {
  sales_order: {
    pk: ['so_order_number'],
    columns: [
      ['so_order_number', 'int'], ['so_channel', 'varchar'], ['so_customer_sk', 'int'], ['so_placed_date', 'date'],
      ['so_delivered_date', 'date'], ['so_warehouse_sk', 'int'], ['so_value', MONEY], ['so_state', 'varchar'],
      ['so_held_for', 'varchar'], ['so_placed_by', 'varchar'],
    ],
  },
  sales_line: {
    pk: ['sl_order_number', 'sl_line_no'],
    columns: [
      ['sl_order_number', 'int'], ['sl_line_no', 'int'], ['sl_item_sk', 'int'], ['sl_quantity', 'int'],
      ['sl_unit_price', MONEY], ['sl_ext_sales_price', MONEY], ['sl_warehouse_sk', 'int'], ['sl_sold_date', 'date'],
      ['sl_customer_sk', 'int'], ['sl_channel', 'varchar'],
    ],
  },
  price_list: {
    pk: ['pl_item_sk', 'pl_channel', 'pl_valid_from'],
    columns: [['pl_item_sk', 'int'], ['pl_channel', 'varchar'], ['pl_valid_from', 'date'], ['pl_price', MONEY], ['pl_valid_to', 'date']],
  },
  customer_price: {
    pk: ['cp_item_sk', 'cp_customer_sk', 'cp_valid_from'],
    columns: [['cp_item_sk', 'int'], ['cp_customer_sk', 'int'], ['cp_valid_from', 'date'], ['cp_price', MONEY], ['cp_valid_to', 'date']],
  },
  invoice: {
    pk: ['inv_invoice_id'],
    columns: [
      ['inv_invoice_id', 'int'], ['inv_customer_sk', 'int'], ['inv_order_number', 'int'], ['inv_issued', 'date'],
      ['inv_due', 'date'], ['inv_amount', MONEY], ['inv_paid', MONEY], ['inv_status', 'varchar'],
    ],
  },
  warehouse_outage: {
    pk: ['wo_warehouse_sk', 'wo_from_date'],
    columns: [['wo_warehouse_sk', 'int'], ['wo_from_date', 'date'], ['wo_to_date', 'date'], ['wo_reason', 'varchar']],
  },
  return_request_hdr: {
    pk: ['rr_request_number'],
    columns: [
      ['rr_request_number', 'int'], ['rr_customer_sk', 'int'], ['rr_order_number', 'int'], ['rr_channel', 'varchar'],
      ['rr_requested_date', 'date'],
    ],
  },
  return_request_line: {
    pk: ['rl_line_id'],
    columns: [
      ['rl_line_id', 'int'], ['rl_request_number', 'int'], ['rl_line_no', 'int'], ['rl_item_sk', 'int'],
      ['rl_quantity', 'int'], ['rl_unit_price', MONEY], ['rl_reason', 'varchar'], ['rl_requested_resolution', 'varchar'],
      ['rl_requested_variant_sk', 'int'], ['rl_state', 'varchar'], ['rl_way', 'varchar'], ['rl_customer_sk', 'int'],
    ],
  },
  return_receipt: {
    pk: ['rc_receipt_id'],
    columns: [
      ['rc_receipt_id', 'int'], ['rc_line_id', 'int'], ['rc_received_date', 'date'], ['rc_item_sk', 'int'],
      ['rc_quantity', 'int'], ['rc_condition', 'varchar'],
    ],
  },
  credit_note: {
    pk: ['cn_credit_note_id'],
    columns: [
      ['cn_credit_note_id', 'int'], ['cn_line_id', 'int'], ['cn_customer_sk', 'int'], ['cn_issued_date', 'date'],
      ['cn_amount', MONEY], ['cn_status', 'varchar'],
    ],
  },
  refund_payment: {
    pk: ['rp_payment_id'],
    columns: [['rp_payment_id', 'int'], ['rp_credit_note_id', 'int'], ['rp_paid_date', 'date'], ['rp_amount', MONEY]],
  },
  replacement_shipment: {
    pk: ['rs_shipment_id'],
    columns: [
      ['rs_shipment_id', 'int'], ['rs_line_id', 'int'], ['rs_item_sk', 'int'], ['rs_quantity', 'int'], ['rs_shipped_date', 'date'],
    ],
  },
  reorder_proposal: {
    pk: ['rp_proposal_id'],
    columns: [
      ['rp_proposal_id', 'int'], ['rp_run', 'varchar'], ['rp_item_sk', 'int'], ['rp_warehouse_sk', 'int'], ['rp_on_hand', 'int'],
      ['rp_weekly_demand', MONEY], ['rp_proposed_qty', 'int'], ['rp_state', 'varchar'],
    ],
  },
  winback_mail: {
    pk: ['wm_customer_sk', 'wm_quarter'],
    columns: [['wm_customer_sk', 'int'], ['wm_quarter', 'varchar'], ['wm_offer', 'varchar'], ['wm_run', 'varchar'], ['wm_mailed_at', 'timestamp']],
  },
};

// The derived views (data/views/*.sql): view → columns, in the script's order.
const VIEWS = {
  return_request: [
    ['rr_request_number', 'int'], ['rr_customer_sk', 'int'], ['rr_order_number', 'int'], ['rr_channel', 'varchar'],
    ['rr_requested_date', 'date'], ['rr_state', 'varchar'],
  ],
  document_flow: [['df_pred_kind', 'varchar'], ['df_pred_id', 'int'], ['df_succ_kind', 'varchar'], ['df_succ_id', 'int']],
};

// The two columns the extension adds to existing dimensions (D-82, D-83).
const COLUMN_ADDS = [['customer', 'c_center_sk', 'int'], ['date_dim', 'd_working_day', 'bool']];

// Foreign keys: name → [from table.column, to table.column]. The 22 of contracts §1, plus four the §2
// relations bind (fk_rl_customer — D-127; fk_rp_item, fk_rp_warehouse, fk_wm_customer), plus three
// LOGICAL keys on the return_request VIEW (the channel_sales precedent in fks.ttrm: a relation whose
// entity reads a view binds a key on the view's columns, not on the table under it).
const FKS = {
  fk_so_customer: ['sales_order.so_customer_sk', 'customer.c_customer_sk'],
  fk_so_warehouse: ['sales_order.so_warehouse_sk', 'warehouse.w_warehouse_sk'],
  fk_sl_order: ['sales_line.sl_order_number', 'sales_order.so_order_number'],
  fk_sl_item: ['sales_line.sl_item_sk', 'item.i_item_sk'],
  fk_sl_warehouse: ['sales_line.sl_warehouse_sk', 'warehouse.w_warehouse_sk'],
  fk_sl_customer: ['sales_line.sl_customer_sk', 'customer.c_customer_sk'],
  fk_pl_item: ['price_list.pl_item_sk', 'item.i_item_sk'],
  fk_cp_item: ['customer_price.cp_item_sk', 'item.i_item_sk'],
  fk_cp_customer: ['customer_price.cp_customer_sk', 'customer.c_customer_sk'],
  fk_inv_customer: ['invoice.inv_customer_sk', 'customer.c_customer_sk'],
  fk_inv_order: ['invoice.inv_order_number', 'sales_order.so_order_number'],
  fk_wo_warehouse: ['warehouse_outage.wo_warehouse_sk', 'warehouse.w_warehouse_sk'],
  fk_rr_customer: ['return_request_hdr.rr_customer_sk', 'customer.c_customer_sk'],
  fk_rr_order: ['return_request_hdr.rr_order_number', 'sales_order.so_order_number'],
  fk_rl_request: ['return_request_line.rl_request_number', 'return_request_hdr.rr_request_number'],
  fk_rl_item: ['return_request_line.rl_item_sk', 'item.i_item_sk'],
  fk_rl_variant: ['return_request_line.rl_requested_variant_sk', 'item.i_item_sk'],
  fk_rl_customer: ['return_request_line.rl_customer_sk', 'customer.c_customer_sk'],
  fk_rc_line: ['return_receipt.rc_line_id', 'return_request_line.rl_line_id'],
  fk_cn_line: ['credit_note.cn_line_id', 'return_request_line.rl_line_id'],
  fk_rp_credit_note: ['refund_payment.rp_credit_note_id', 'credit_note.cn_credit_note_id'],
  fk_rs_line: ['replacement_shipment.rs_line_id', 'return_request_line.rl_line_id'],
  fk_rp_item: ['reorder_proposal.rp_item_sk', 'item.i_item_sk'],
  fk_rp_warehouse: ['reorder_proposal.rp_warehouse_sk', 'warehouse.w_warehouse_sk'],
  fk_wm_customer: ['winback_mail.wm_customer_sk', 'customer.c_customer_sk'],
  fk_c_center: ['customer.c_center_sk', 'warehouse.w_warehouse_sk'],
  fk_rrv_customer: ['return_request.rr_customer_sk', 'customer.c_customer_sk'],
  fk_rrv_order: ['return_request.rr_order_number', 'sales_order.so_order_number'],
  fk_rlv_request: ['return_request_line.rl_request_number', 'return_request.rr_request_number'],
  // LOGICAL keys from each dated document's own date to the calendar day (the date-relation fix, below).
  fk_so_date: ['sales_order.so_placed_date', 'date_dim.d_date'],
  fk_invoice_date: ['invoice.inv_issued', 'date_dim.d_date'],
  fk_rrv_date: ['return_request.rr_requested_date', 'date_dim.d_date'],
  fk_rc_date: ['return_receipt.rc_received_date', 'date_dim.d_date'],
  fk_cn_date: ['credit_note.cn_issued_date', 'date_dim.d_date'],
  fk_rp_paid_date: ['refund_payment.rp_paid_date', 'date_dim.d_date'],
  fk_rs_date: ['replacement_shipment.rs_shipped_date', 'date_dim.d_date'],
};

// ── logical (contracts §2, 17 §0): entity → the table/view it reads + attributes [name, type, isKey, column] ─
const ENTITIES = {
  order: {
    reads: ['table', 'sales_order'],
    attributes: [
      ['order_number', 'int', true, 'so_order_number'], ['channel', 'text', false, 'so_channel'],
      ['customer', 'int', false, 'so_customer_sk'], ['placed_date', 'date', false, 'so_placed_date'],
      ['delivered_date', 'date', false, 'so_delivered_date'], ['fulfilling_warehouse', 'int', false, 'so_warehouse_sk'],
      ['value', MONEY, false, 'so_value'], ['state', 'text', false, 'so_state'], ['held_for', 'text', false, 'so_held_for'],
      ['placed_by', 'text', false, 'so_placed_by'],
    ],
  },
  sales_line: {
    reads: ['table', 'sales_line'],
    attributes: [
      ['order_number', 'int', true, 'sl_order_number'], ['line_no', 'int', true, 'sl_line_no'], ['item', 'int', false, 'sl_item_sk'],
      ['quantity', 'int', false, 'sl_quantity'], ['unit_price', MONEY, false, 'sl_unit_price'],
      ['ext_sales_price', MONEY, false, 'sl_ext_sales_price'], ['warehouse', 'int', false, 'sl_warehouse_sk'],
      ['sold_date', 'date', false, 'sl_sold_date'], ['customer', 'int', false, 'sl_customer_sk'], ['channel', 'text', false, 'sl_channel'],
    ],
  },
  price_list: {
    reads: ['table', 'price_list'],
    attributes: [
      ['item', 'int', true, 'pl_item_sk'], ['channel', 'text', true, 'pl_channel'], ['valid_from', 'date', true, 'pl_valid_from'],
      ['price', MONEY, false, 'pl_price'], ['valid_to', 'date', false, 'pl_valid_to'],
    ],
  },
  customer_price: {
    reads: ['table', 'customer_price'],
    attributes: [
      ['item', 'int', true, 'cp_item_sk'], ['customer', 'int', true, 'cp_customer_sk'], ['valid_from', 'date', true, 'cp_valid_from'],
      ['price', MONEY, false, 'cp_price'], ['valid_to', 'date', false, 'cp_valid_to'],
    ],
  },
  invoice: {
    reads: ['table', 'invoice'],
    attributes: [
      ['invoice_id', 'int', true, 'inv_invoice_id'], ['customer', 'int', false, 'inv_customer_sk'],
      ['order_number', 'int', false, 'inv_order_number'], ['issued', 'date', false, 'inv_issued'], ['due', 'date', false, 'inv_due'],
      ['amount', MONEY, false, 'inv_amount'], ['paid', MONEY, false, 'inv_paid'], ['status', 'text', false, 'inv_status'],
    ],
  },
  warehouse_outage: {
    reads: ['table', 'warehouse_outage'],
    attributes: [
      ['warehouse', 'int', true, 'wo_warehouse_sk'], ['from_date', 'date', true, 'wo_from_date'],
      ['to_date', 'date', false, 'wo_to_date'], ['reason', 'text', false, 'wo_reason'],
    ],
  },
  return_request: {
    reads: ['view', 'return_request'],
    attributes: [
      ['request_number', 'int', true, 'rr_request_number'], ['customer', 'int', false, 'rr_customer_sk'],
      ['order_number', 'int', false, 'rr_order_number'], ['channel', 'text', false, 'rr_channel'],
      ['requested_date', 'date', false, 'rr_requested_date'], ['state', 'text', false, 'rr_state'],
    ],
  },
  return_request_line: {
    reads: ['table', 'return_request_line'],
    attributes: [
      ['line_id', 'int', true, 'rl_line_id'], ['request_number', 'int', false, 'rl_request_number'],
      ['line_no', 'int', false, 'rl_line_no'], ['item', 'int', false, 'rl_item_sk'], ['quantity', 'int', false, 'rl_quantity'],
      ['unit_price', MONEY, false, 'rl_unit_price'], ['reason', 'text', false, 'rl_reason'],
      ['requested_resolution', 'text', false, 'rl_requested_resolution'], ['requested_variant', 'int', false, 'rl_requested_variant_sk'],
      ['state', 'text', false, 'rl_state'], ['way', 'text', false, 'rl_way'], ['customer', 'int', false, 'rl_customer_sk'],
    ],
  },
  return_receipt: {
    reads: ['table', 'return_receipt'],
    attributes: [
      ['receipt_id', 'int', true, 'rc_receipt_id'], ['line_id', 'int', false, 'rc_line_id'],
      ['received_date', 'date', false, 'rc_received_date'], ['item', 'int', false, 'rc_item_sk'],
      ['quantity', 'int', false, 'rc_quantity'], ['condition', 'text', false, 'rc_condition'],
    ],
  },
  credit_note: {
    reads: ['table', 'credit_note'],
    attributes: [
      ['credit_note_id', 'int', true, 'cn_credit_note_id'], ['line_id', 'int', false, 'cn_line_id'],
      ['customer', 'int', false, 'cn_customer_sk'], ['issued_date', 'date', false, 'cn_issued_date'],
      ['amount', MONEY, false, 'cn_amount'], ['status', 'text', false, 'cn_status'],
    ],
  },
  refund_payment: {
    reads: ['table', 'refund_payment'],
    attributes: [
      ['payment_id', 'int', true, 'rp_payment_id'], ['credit_note_id', 'int', false, 'rp_credit_note_id'],
      ['paid_date', 'date', false, 'rp_paid_date'], ['amount', MONEY, false, 'rp_amount'],
    ],
  },
  replacement_shipment: {
    reads: ['table', 'replacement_shipment'],
    attributes: [
      ['shipment_id', 'int', true, 'rs_shipment_id'], ['line_id', 'int', false, 'rs_line_id'], ['item', 'int', false, 'rs_item_sk'],
      ['quantity', 'int', false, 'rs_quantity'], ['shipped_date', 'date', false, 'rs_shipped_date'],
    ],
  },
  document_flow: {
    reads: ['view', 'document_flow'],
    attributes: [
      ['pred_kind', 'text', true, 'df_pred_kind'], ['pred_id', 'int', true, 'df_pred_id'],
      ['succ_kind', 'text', true, 'df_succ_kind'], ['succ_id', 'int', true, 'df_succ_id'],
    ],
  },
  reorder_proposal: {
    reads: ['table', 'reorder_proposal'],
    attributes: [
      ['proposal_id', 'int', true, 'rp_proposal_id'], ['run', 'text', false, 'rp_run'], ['item', 'int', false, 'rp_item_sk'],
      ['warehouse', 'int', false, 'rp_warehouse_sk'], ['on_hand', 'int', false, 'rp_on_hand'],
      ['weekly_demand', MONEY, false, 'rp_weekly_demand'], ['proposed_qty', 'int', false, 'rp_proposed_qty'],
      ['state', 'text', false, 'rp_state'],
    ],
  },
  winback_mail: {
    reads: ['table', 'winback_mail'],
    attributes: [
      ['customer', 'int', true, 'wm_customer_sk'], ['quarter', 'text', true, 'wm_quarter'], ['offer', 'text', false, 'wm_offer'],
      ['run', 'text', false, 'wm_run'], ['mailed_at', 'timestamp', false, 'wm_mailed_at'],
    ],
  },
};

// The attributes the extension adds to existing entities: [entity, attribute, type, table, column].
const ATTRIBUTE_ADDS = [['customer', 'center', 'int', 'customer', 'c_center_sk'], ['date_dim', 'working_day', 'bool', 'date_dim', 'd_working_day']];

// Relations: name → [from, to, cardinality.from, cardinality.to, fk, join pairs [fromAttr, toAttr]].
// The 20 of contracts §2, then five more — one per extension fk that names a dimension — so the file's
// own rule ("one relation per foreign key") holds. `fk_rl_variant` gets none: a second line → item
// relation would make every line/item join ambiguous (the requested variant is reached by value, 17 §4.1).
// Every relation carries `join:` pairs beside its fk: they are what `on: relation X` (TTR-P) and the
// translator's JoinPolicy read; a relation with an fk only has no join condition there.
const RELATIONS = {
  rel_order_customer: ['order', 'customer', '0..*', '1', 'fk_so_customer', [['customer', 'sk']]],
  rel_order_warehouse: ['order', 'warehouse', '0..*', '1', 'fk_so_warehouse', [['fulfilling_warehouse', 'sk']]],
  rel_order_line: ['order', 'sales_line', '1', '1..*', 'fk_sl_order', [['order_number', 'order_number']]],
  rel_sales_line_item: ['sales_line', 'item', '0..*', '1', 'fk_sl_item', [['item', 'sk']]],
  rel_sales_line_customer: ['sales_line', 'customer', '0..*', '1', 'fk_sl_customer', [['customer', 'sk']]],
  rel_invoice_customer: ['invoice', 'customer', '0..*', '1', 'fk_inv_customer', [['customer', 'sk']]],
  rel_invoice_order: ['invoice', 'order', '0..*', '1', 'fk_inv_order', [['order_number', 'order_number']]],
  rel_outage_warehouse: ['warehouse_outage', 'warehouse', '0..*', '1', 'fk_wo_warehouse', [['warehouse', 'sk']]],
  rel_customer_center: ['customer', 'warehouse', '0..*', '0..1', 'fk_c_center', [['center', 'sk']]],
  rel_request_customer: ['return_request', 'customer', '0..*', '1', 'fk_rrv_customer', [['customer', 'sk']]],
  rel_request_order: ['return_request', 'order', '0..*', '1', 'fk_rrv_order', [['order_number', 'order_number']]],
  rel_request_line: ['return_request', 'return_request_line', '1', '1..*', 'fk_rlv_request', [['request_number', 'request_number']]],
  rel_line_customer: ['return_request_line', 'customer', '0..*', '1', 'fk_rl_customer', [['customer', 'sk']]],
  rel_receipt_line: ['return_receipt', 'return_request_line', '0..*', '1', 'fk_rc_line', [['line_id', 'line_id']]],
  rel_credit_note_line: ['credit_note', 'return_request_line', '0..*', '1', 'fk_cn_line', [['line_id', 'line_id']]],
  rel_payment_credit_note: ['refund_payment', 'credit_note', '0..*', '1', 'fk_rp_credit_note', [['credit_note_id', 'credit_note_id']]],
  rel_shipment_line: ['replacement_shipment', 'return_request_line', '0..*', '1', 'fk_rs_line', [['line_id', 'line_id']]],
  rel_proposal_item: ['reorder_proposal', 'item', '0..*', '1', 'fk_rp_item', [['item', 'sk']]],
  rel_proposal_warehouse: ['reorder_proposal', 'warehouse', '0..*', '1', 'fk_rp_warehouse', [['warehouse', 'sk']]],
  rel_winback_customer: ['winback_mail', 'customer', '0..*', '1', 'fk_wm_customer', [['customer', 'sk']]],
  // beyond §2 — one per remaining dimension fk
  rel_sales_line_warehouse: ['sales_line', 'warehouse', '0..*', '1', 'fk_sl_warehouse', [['warehouse', 'sk']]],
  rel_price_list_item: ['price_list', 'item', '0..*', '1', 'fk_pl_item', [['item', 'sk']]],
  rel_customer_price_item: ['customer_price', 'item', '0..*', '1', 'fk_cp_item', [['item', 'sk']]],
  rel_customer_price_customer: ['customer_price', 'customer', '0..*', '1', 'fk_cp_customer', [['customer', 'sk']]],
  rel_line_item: ['return_request_line', 'item', '0..*', '1', 'fk_rl_item', [['item', 'sk']]],
  // The date relations: every dated document head reaches the calendar day from its own date, as every fact
  // does through its rel_*_date. Without one a bare `JOIN date_dim` (the composer's way of writing a period,
  // completed by the translator's model joins) is left `ON TRUE` — the whole calendar under every document;
  // found by the P0-exit routing run (a3-3, a3-4). One per document: `sales_line` dates with its order (a
  // second date path would make every order ⋈ line ⋈ date_dim join ambiguous), the line with its request;
  // ranges (price lists, outages) and the run artefacts (proposals, win-back mails) have no day to join.
  rel_order_date: ['order', 'date_dim', '0..*', '1', 'fk_so_date', [['placed_date', 'cal_date']]],
  rel_invoice_date: ['invoice', 'date_dim', '0..*', '1', 'fk_invoice_date', [['issued', 'cal_date']]],
  rel_request_date: ['return_request', 'date_dim', '0..*', '1', 'fk_rrv_date', [['requested_date', 'cal_date']]],
  rel_receipt_date: ['return_receipt', 'date_dim', '0..*', '1', 'fk_rc_date', [['received_date', 'cal_date']]],
  rel_credit_note_date: ['credit_note', 'date_dim', '0..*', '1', 'fk_cn_date', [['issued_date', 'cal_date']]],
  rel_payment_date: ['refund_payment', 'date_dim', '0..*', '1', 'fk_rp_paid_date', [['paid_date', 'cal_date']]],
  rel_shipment_date: ['replacement_shipment', 'date_dim', '0..*', '1', 'fk_rs_date', [['shipped_date', 'cal_date']]],
};

// ── helpers ─────────────────────────────────────────────────────────────────────────────────────────
const project = await loadHartlandProject();

function defsOfKind(kind) {
  const out = [];
  for (const [uri, ast] of project.asts) {
    if (uri.startsWith('stock://')) continue;
    for (const def of ast.definitions ?? []) if (def.kind === kind) out.push(def);
  }
  return out;
}
const byName = (kind) => new Map(defsOfKind(kind).map((d) => [d.name, d]));
const typeOf = (t) => (t?.kind === 'structured' ? `${t.typeName}(${t.length},${t.precision})` : t?.name);
const objectEntry = (node, key) => node?.entries?.find((e) => e.key === key)?.value;
const last2 = (idNode) => idNode.parts.slice(-2).join('.');

const tables = byName('table');
const views = byName('view');
const fks = byName('fk');
const entities = byName('entity');
const relations = byName('relation');
const er2dbEntities = new Map(defsOfKind('er2dbEntity').map((d) => [d.entity.path, d]));
const er2dbAttributes = new Map(defsOfKind('er2dbAttribute').map((d) => [d.attribute.path, d]));

const columnsOf = (def) => (def?.columns ?? []).map((c) => [c.name, typeOf(c.type)]);

// ── (physical) ──────────────────────────────────────────────────────────────────────────────────────
test('S0.1 physical — every extension table exists with exactly the contract\'s columns, types and primary key', () => {
  const problems = [];
  for (const [name, { pk, columns }] of Object.entries(TABLES)) {
    const t = tables.get(name);
    if (!t) { problems.push(`no def table ${name}`); continue; }
    assert.deepEqual(columnsOf(t), columns, `${name} columns`);
    assert.deepEqual((t.primaryKey ?? []).map((p) => p.name ?? p.path ?? p), pk, `${name} primaryKey`);
    const keyed = t.columns.filter((c) => c.isKey).map((c) => c.name);
    assert.deepEqual(keyed, pk, `${name}: the isKey columns are the primary key`);
  }
  assert.deepEqual(problems, [], problems.join('; '));
});

test('S0.1 physical — the two derived views declare their columns in the script\'s order, with their SQL', () => {
  for (const [name, columns] of Object.entries(VIEWS)) {
    const v = views.get(name);
    assert.ok(v, `no def view ${name}`);
    assert.deepEqual(columnsOf(v), columns, `${name} columns`);
    assert.ok(v.definitionSql, `${name} has no definitionSql`);
  }
});

test('S0.1 physical — customer.c_center_sk (D-83) and date_dim.d_working_day (D-82) are declared', () => {
  for (const [table, column, type] of COLUMN_ADDS) {
    const c = tables.get(table)?.columns?.find((x) => x.name === column);
    assert.ok(c, `${table}.${column} is not declared`);
    assert.equal(typeOf(c.type), type, `${table}.${column} type`);
  }
});

test('S0.1 physical — every extension fk joins exactly the contract\'s columns', () => {
  const problems = [];
  for (const [name, [from, to]] of Object.entries(FKS)) {
    const fk = fks.get(name);
    if (!fk) { problems.push(`no def fk ${name}`); continue; }
    const got = [fk.from.items.map(last2).join(','), fk.to.items.map(last2).join(',')];
    if (got[0] !== from || got[1] !== to) problems.push(`${name}: ${got.join(' → ')}, want ${from} → ${to}`);
  }
  assert.deepEqual(problems, [], problems.join('; '));
});

// ── (a) entities ────────────────────────────────────────────────────────────────────────────────────
test('S0.1 (a) — every extension entity exists with exactly the contract\'s attributes, types and keys', () => {
  for (const [name, { attributes }] of Object.entries(ENTITIES)) {
    const e = entities.get(name);
    assert.ok(e, `no def entity ${name}`);
    const got = (e.attributes ?? []).map((a) => [a.name, typeOf(a.type), a.isKey === true]);
    assert.deepEqual(got, attributes.map(([n, t, k]) => [n, t, k]), `${name} attributes`);
  }
});

test('S0.1 (a) — every extension entity resolves in the project to the ENTITY (hartland.er.entity.*)', () => {
  // `resolved` alone is not enough: the resolver falls back to a same-named db table (return_receipt).
  const unresolved = Object.keys(ENTITIES).filter((name) => {
    const res = project.resolver.resolveReference({ path: `er.entity.${name}`, parts: ['er', 'entity', name] }, { schemaCode: 'er', namespace: 'entity' });
    return !(res.resolved && res.symbol.kind === 'entity' && res.symbol.qname === `hartland.er.entity.${name}`);
  });
  assert.deepEqual(unresolved, [], `unresolved: ${unresolved.join(', ')}`);
});

// ── (b) relations ───────────────────────────────────────────────────────────────────────────────────
test('S0.1 (b) — every relation exists with the contract\'s from/to/cardinality and binds its fk', () => {
  const problems = [];
  for (const [name, [from, to, cFrom, cTo, fk]] of Object.entries(RELATIONS)) {
    const r = relations.get(name);
    if (!r) { problems.push(`no def relation ${name}`); continue; }
    if (r.from.path !== `er.entity.${from}`) problems.push(`${name}.from = ${r.from.path}`);
    if (r.to.path !== `er.entity.${to}`) problems.push(`${name}.to = ${r.to.path}`);
    if (objectEntry(r.cardinality, 'from')?.value !== cFrom) problems.push(`${name} cardinality.from = ${objectEntry(r.cardinality, 'from')?.value}`);
    if (objectEntry(r.cardinality, 'to')?.value !== cTo) problems.push(`${name} cardinality.to = ${objectEntry(r.cardinality, 'to')?.value}`);
    if (r.binding?.fk?.path !== `db.dbo.${fk}`) { problems.push(`${name}.binding.fk = ${r.binding?.fk?.path}`); continue; }
    const res = project.resolver.resolveReference({ path: r.binding.fk.path, parts: r.binding.fk.parts }, { schemaCode: 'db', namespace: 'dbo' });
    if (!res.resolved) problems.push(`${name}.binding.fk ${r.binding.fk.path} does not resolve`);
  }
  assert.deepEqual(problems, [], problems.join('; '));
});

test('S0.1 (b) — every relation carries its join pairs, and the fk joins the columns those attributes bind', () => {
  const problems = [];
  for (const [name, [from, to, , cTo, fk, pairs]] of Object.entries(RELATIONS)) {
    const r = relations.get(name);
    if (!r) { problems.push(`no def relation ${name}`); continue; }
    const got = (r.join?.items ?? []).map((o) => [objectEntry(o, 'from')?.path, objectEntry(o, 'to')?.path]);
    const want = pairs.map(([f, t]) => [`er.entity.${from}.${f}`, `er.entity.${to}.${t}`]);
    if (JSON.stringify(got) !== JSON.stringify(want)) { problems.push(`${name} join ${JSON.stringify(got)}, want ${JSON.stringify(want)}`); continue; }
    // The pair and the fk say the same join: each side's attribute binds a column the fk names. A 1 → 1..*
    // relation (rel_order_line, rel_request_line) runs AGAINST its fk, which lives on the many side.
    const col = (attrPath) => last2(objectEntry(er2dbAttributes.get(attrPath)?.target, 'column') ?? { parts: [] });
    const [fkFrom, fkTo] = FKS[fk] ?? [];
    const [pf, pt] = want[0];
    const [a, b] = cTo === '1..*' ? [col(pt), col(pf)] : [col(pf), col(pt)];
    if (FKS[fk] && (a !== fkFrom || b !== fkTo)) problems.push(`${name}: join binds ${col(pf)} → ${col(pt)}, ${fk} joins ${fkFrom} → ${fkTo}`);
  }
  assert.deepEqual(problems, [], problems.join('; '));
});

// ── (c) er2db ───────────────────────────────────────────────────────────────────────────────────────
test('S0.1 (c) — every extension entity has an er2db_entity on its table/view, every attribute an er2db_attribute on its column', () => {
  const problems = [];
  for (const [name, { reads: [kind, target], attributes }] of Object.entries(ENTITIES)) {
    const m = er2dbEntities.get(`er.entity.${name}`);
    const bound = objectEntry(m?.target, kind)?.path;
    if (bound !== `db.dbo.${target}`) problems.push(`${name} → ${kind} ${bound}, want db.dbo.${target}`);
    for (const [attr, , , column] of attributes) {
      const a = er2dbAttributes.get(`er.entity.${name}.${attr}`);
      const c = objectEntry(a?.target, 'column')?.path;
      if (c !== `db.dbo.${target}.${column}`) problems.push(`${name}.${attr} → ${c}, want db.dbo.${target}.${column}`);
    }
  }
  assert.deepEqual(problems, [], problems.join('; '));
});

// ── (d) the two attribute adds ──────────────────────────────────────────────────────────────────────
test('S0.1 (d) — customer.center (D-83) and date_dim.working_day (D-82) exist and are bound', () => {
  for (const [entity, attr, type, table, column] of ATTRIBUTE_ADDS) {
    const a = entities.get(entity)?.attributes?.find((x) => x.name === attr);
    assert.ok(a, `${entity}.${attr} is not declared`);
    assert.equal(typeOf(a.type), type, `${entity}.${attr} type`);
    const c = objectEntry(er2dbAttributes.get(`er.entity.${entity}.${attr}`)?.target, 'column')?.path;
    assert.equal(c, `db.dbo.${table}.${column}`, `${entity}.${attr} binding`);
  }
});

// ── (e) the project stays clean ─────────────────────────────────────────────────────────────────────
test('S0.1 (e) — the whole project parses, nothing is unresolved, and no diagnostic outside the accepted residuals', () => {
  const parseErrors = [...project.parseErrorsByFile].filter(([, errors]) => errors.length).map(([f, e]) => `${f}: ${JSON.stringify(e[0])}`);
  assert.deepEqual(parseErrors, [], parseErrors.join('; '));
  const offenders = [];
  for (const [file, codes] of project.diagnosticsByFile) {
    for (const code of codes) if (code === 'ttr/unresolved-reference' || !ACCEPTED_RESIDUAL_CODES.has(code)) offenders.push(`${file}: ${code}`);
  }
  assert.deepEqual(offenders, [], offenders.join('; '));
});

// ── the world (contracts §3, S0.2) ──────────────────────────────────────────────────────────────────────────────
// Moved to automation/tests/package.test.mjs with the world itself at AU-hartland P2: the world's automation half
// names the package's programs and roles, so it lives in `automation/worlds/demo.ttrm`, beside them — `model/` is
// what veles and the lexicon build read, and nothing there may name an automation object.
