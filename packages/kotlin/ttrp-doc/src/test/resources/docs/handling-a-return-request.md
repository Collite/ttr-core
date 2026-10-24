# Handling a return request

Goods arriving incomplete or damaged go to customer care. The amount returned in the last twelve months needs the explicit sum-of-receipts over sum-of-sales restatement.

```ttrp preamble
// decide_return.ttrp — derived from handling-a-return-request.md §Deciding
// AU-core-P0 shared fixture: AU design `17` §4.1, verbatim but for the TTR-P comment marker (`#` → `//`).
// F2: `return_share` is restated without scalar subqueries (TTR-SQL has none, TTRP-SQL-006) — see there.
// F3 (AU-core-P1 S1.3 T7): 17's `:as_of - 365` is `:as_of - days(365)` — an integer next to a date is read as
// days with a warning; a duration in an expression is a call.
uses world "hartland.worlds.demo"
import hartland.*
import hartland.automation.*
```

```ttrp
recipe: decide_return
recipe {
key:        return_request_line.line_id
    as of:      now
    parameters: [return_window, abuse_share, small_value, care_threshold, settlement, inbound_reminder]
    document:   "handling-a-return-request.md"
}
```

Consider every received return-request line.

```sql candidates received(out lines) target erp
        SELECT l.line_id, l.request_number, l.item, l.quantity, l.unit_price, l.quantity * l.unit_price AS line_value,
               l.reason, l.requested_resolution, l.requested_variant,
               r.customer, r.order_number, r.requested_date
        FROM   er.entity.return_request_line l JOIN er.entity.return_request r ON r.request_number = l.request_number
        WHERE  l.state = 'received'
```

Compute bought here from the recipe inputs.

```sql context bought_here(in lines, out rows) target erp
        SELECT q.line_id, o.delivered_date,
               CASE WHEN o.delivered_date >= :as_of - :return_window THEN 1 ELSE 0 END AS in_window,
               CASE WHEN s.item IS NULL THEN 0 ELSE 1 END AS item_on_order
        FROM   lines q
        LEFT JOIN er.entity.order o      ON o.order_number = q.order_number AND o.customer = q.customer
        LEFT JOIN er.entity.sales_line s ON s.order_number = q.order_number AND s.item = q.item
```

Compute return share from the recipe inputs.

```sql context return_share(in lines, out rows) target erp
        WITH returned AS (
            SELECT rr.customer, SUM(rl.quantity * rl.unit_price) AS returned_value
            FROM   er.entity.return_request_line rl JOIN er.entity.return_request rr ON rr.request_number = rl.request_number
            WHERE  rr.requested_date >= :as_of - days(365) AND rl.state <> 'rejected'
            GROUP  BY rr.customer
        ),
        bought AS (
            SELECT s.customer, SUM(s.ext_sales_price) AS bought_value
            FROM   er.entity.sales_line s
            WHERE  s.sold_date >= :as_of - days(365)
            GROUP  BY s.customer
        )
        SELECT q.line_id, COALESCE(r.returned_value, 0) / NULLIF(b.bought_value, 0) AS share
        FROM   lines q
        LEFT JOIN returned r ON r.customer = q.customer
        LEFT JOIN bought   b ON b.customer = q.customer
```

Compute request value from the recipe inputs.

```sql context request_value(out rows) target erp
        SELECT l.request_number, SUM(l.quantity * l.unit_price) AS request_value
        FROM   er.entity.return_request_line l GROUP BY l.request_number
```

Compute stock at center from the recipe inputs.

```ttrp
    context stock_at_center(in lines, out rows) target erp {
        latest = load(er.entity.inventory) -> latest(by: as_of_date, per: item, warehouse)
        lines -> join(right: load(er.entity.customer), kind: inner, on: relation rel_line_customer)      // lines.customer → customer (1..1)
              -> join(right: latest, kind: left, on: left.item = right.item and customer.center = right.warehouse)
              -> calc(item_on_hand = coalesce(latest.quantity_on_hand, 0))
              -> join(right: latest, kind: left, on: left.requested_variant = right.item and customer.center = right.warehouse)
              -> calc(variant_on_hand = coalesce(latest.quantity_on_hand, 0))
              -> select(line_id, item_on_hand, variant_on_hand)
              -> rows
    }
```

Decide the return route from the evidence.

```ttrp
    decision way(in lines, in bought_here, in return_share, in request_value, in stock_at_center) {
        v = lines -> join(right: bought_here,     kind: left, on: left.line_id = right.line_id)
                  -> join(right: return_share,    kind: left, on: left.line_id = right.line_id)
                  -> join(right: stock_at_center, kind: left, on: left.line_id = right.line_id)
                  -> join(right: request_value,   kind: left, on: left.request_number = right.request_number)
        v -> switch {
            mode: exclusive
            reject_not_ours:       delivered_date is null or item_on_order = 0           because: "not bought here"
            reject_late:           in_window = 0                                          because: "delivered more than {return_window} days ago"
            reject_abuse:          share > :abuse_share                                   because: "return share {share} above the limit"
            to_customer_care:      request_value > :care_threshold                        because: "request above the care threshold"
            refund_no_return:      line_value < :small_value and reason in ("damaged", "did_not_arrive")
                                                                                          because: "small value, {reason}"
            replacement_no_return: reason in ("damaged", "did_not_arrive") and item_on_hand >= quantity
                                                                                          because: "{reason}, replacement on stock"
            exchange:              requested_resolution = "exchange" and variant_on_hand >= quantity
                                                                                          because: "requested variant on stock"
            else:                  refund_after_return                                    because: "goods back, then the money"
        }
    }
```

Held orders are rechecked every morning. Releasing a high-value order needs confirmation.

```ttrp
    actions {
        way.reject_not_ours, way.reject_late, way.reject_abuse
                                   -> set_state       { line: line_id, to: rejected }
        way.reject_not_ours, way.reject_late, way.reject_abuse
                                   -> notify_customer { customer: customer, template: "return-rejected" }
        way.to_customer_care       -> ask              { who: role.customer_care, about: request_number, action: set_state,
                                                         group_by: request_number, due: 3 working_days }
        way.refund_no_return       -> set_state        { line: line_id, to: settling, way: refund_no_return }
        way.refund_no_return       -> expect           { line: line_id, kind: credit_note, due: working_days(:settlement) }
        way.replacement_no_return  -> set_state        { line: line_id, to: settling, way: replacement_no_return }
        way.replacement_no_return  -> expect           { line: line_id, kind: replacement, due: working_days(:settlement) }
        way.exchange               -> set_state        { line: line_id, to: awaiting_goods, way: exchange }
        way.refund_after_return    -> set_state        { line: line_id, to: awaiting_goods, way: refund_after_return }
        way.exchange, way.refund_after_return
                                   -> expect           { line: line_id, kind: inbound, due: days(:inbound_reminder) }
        way.exchange, way.refund_after_return
                                   -> notify_customer  { customer: customer, template: "return-instructions" }
    }
```

```ttrp preamble
// monitor_inbound.ttrp — derived from handling-a-return-request.md §Inbound
// AU-core-P0 shared fixture: AU design `17` §4.2, verbatim but for the TTR-P comment marker (`#` → `//`).
// AU-core-P1: the two conditional `expect`s carry 17 §5's `when:` (the hartland recipe's, S2.2 T3), not a comment.
uses world "hartland.worlds.demo"
import hartland.*
import hartland.automation.*
```

```ttrp
recipe: monitor_inbound
recipe {
key:        return_request_line.line_id
    as of:      now
    parameters: [inbound_reminder, inbound_close, settlement]
    document:   "handling-a-return-request.md"
}
```

Compute awaiting from the recipe inputs.

```sql candidates awaiting(out lines) target erp
        SELECT l.line_id, l.request_number, l.item, l.quantity, l.way, r.customer, ob.id AS obligation_id, ob.due, ob.state AS obligation_state
        FROM   er.entity.return_request_line l
        JOIN   er.entity.return_request r ON r.request_number = l.request_number
        JOIN   er.entity.obligation ob    ON ob.entity = 'return_request_line' AND ob.key = l.line_id AND ob.kind = 'inbound'
                                          AND ob.state IN ('open', 'overdue', 'reopened')
        WHERE  l.state = 'awaiting_goods'
```

Compute receipts from the recipe inputs.

```sql context receipts(in lines, out rows) target erp
        SELECT q.line_id,
               COALESCE(SUM(CASE WHEN rc.condition = 'ok' THEN rc.quantity ELSE 0 END), 0) AS qty_ok,
               COALESCE(SUM(CASE WHEN rc.condition <> 'ok' THEN rc.quantity ELSE 0 END), 0) AS qty_bad,
               MAX(rc.received_date) AS last_received
        FROM   lines q LEFT JOIN er.entity.return_receipt rc ON rc.line_id = q.line_id
        GROUP  BY q.line_id
```

Compute age from the recipe inputs.

```ttrp
    context age(in lines, out rows) target erp {
        lines -> join(right: load(er.entity.obligation), kind: inner, on: left.obligation_id = right.id)
              -> calc(days_open = age(since: obligation.created_at))
              -> select(line_id, days_open)
              -> rows
    }
```

Compute reminded from the recipe inputs.

```sql context reminded(in lines, out rows) target erp
        SELECT q.line_id, COUNT(v.run) AS reminders
        FROM   lines q LEFT JOIN er.entity.monitor_inbound_verdict v ON v.line_id = q.line_id AND v.route = 'remind'
        GROUP  BY q.line_id
```

Track open inbound obligations until goods arrive or the return window expires.

```ttrp
    decision inbound(in lines, in receipts, in age, in reminded) {
        v = lines -> join(right: receipts, kind: left, on: left.line_id = right.line_id)
                  -> join(right: age,      kind: left, on: left.line_id = right.line_id)
                  -> join(right: reminded, kind: left, on: left.line_id = right.line_id)
        v -> switch {
            mode: exclusive
            complete:    qty_ok >= quantity                                    because: "returned in full"              detail: receipts
            damaged:     qty_bad > 0 or (qty_ok > 0 and qty_ok < quantity)      because: "incomplete or damaged return"   detail: receipts
            expired:     days_open > :inbound_close                            because: "nothing returned after {inbound_close} days"
            remind:      days_open > :inbound_reminder and reminders = 0        because: "nothing returned after {inbound_reminder} days"
            else:        none                                                  // still within the window — no verdict row (D-80)
        }
    }
```

Held orders are rechecked every morning. Releasing a high-value order needs confirmation.

```ttrp
    actions {
        inbound.complete  -> fulfil          { line: line_id, kind: inbound, to: fulfilled }       // ⇒ signal(fulfilled) to the waiting engine
        inbound.complete  -> set_state       { line: line_id, to: goods_received }
        inbound.complete  -> expect          { line: line_id, kind: credit_note,  due: working_days(:settlement) }  when: way = "refund_after_return"
        inbound.complete  -> expect          { line: line_id, kind: replacement,  due: working_days(:settlement) }  when: way = "exchange"
        inbound.damaged   -> task            { who: role.customer_care, about: line_id, detail: receipts }
        inbound.expired   -> fulfil          { line: line_id, kind: inbound, to: cancelled }
        inbound.expired   -> set_state       { line: line_id, to: not_returned }
        inbound.remind    -> notify_customer { customer: customer, template: "return-reminder" }
    }
```

```ttrp preamble
// settle.ttrp — derived from handling-a-return-request.md §Outbound
// AU-core-P0 shared fixture: AU design `17` §4.3, verbatim but for the TTR-P comment marker (`#` → `//`).
uses world "hartland.worlds.demo"
import hartland.*
import hartland.automation.*
```

```ttrp
recipe: settle
recipe {
key:        return_request_line.line_id
    as of:      now
    parameters: [settlement]
    document:   "handling-a-return-request.md"

}
```

Consider lines awaiting settlement.

```sql candidates settling(out lines) target erp
        SELECT l.line_id, l.request_number, l.item, l.quantity, l.way, r.customer
        FROM   er.entity.return_request_line l JOIN er.entity.return_request r ON r.request_number = l.request_number
        WHERE  l.state IN ('settling', 'goods_received')
```

Compute open obligations from the recipe inputs.

```sql context open_obligations(in lines, out rows) target erp
        SELECT q.line_id, ob.id AS obligation_id, ob.kind, ob.due
        FROM   lines q JOIN er.entity.obligation ob ON ob.entity = 'return_request_line' AND ob.key = q.line_id
        WHERE  ob.kind IN ('credit_note', 'payment', 'replacement') AND ob.state IN ('open', 'overdue', 'reopened')
```

Compute documents from the recipe inputs.

```sql context documents(in lines, out rows) target erp
        SELECT q.line_id,
               cn.credit_note_id, cn.issued_date, p.payment_id, p.paid_date, sh.shipment_id, sh.shipped_date
        FROM   lines q
        LEFT JOIN er.entity.credit_note          cn ON cn.line_id = q.line_id
        LEFT JOIN er.entity.refund_payment       p  ON p.credit_note_id = cn.credit_note_id
        LEFT JOIN er.entity.replacement_shipment sh ON sh.line_id = q.line_id
```

Compute overdue days from the recipe inputs.

```ttrp
    context overdue_days(in lines, in open_obligations, out rows) target erp {
        open_obligations -> calc(late = working_days(between: due, and: :as_of))
                         -> aggregate { group_by: line_id  max_late = max(late), kinds_open = count(kind) }
                         -> rows
    }
```

Close completed settlements; escalate overdue documents and payments.

```ttrp
    decision settlement(in lines, in open_obligations, in documents, in overdue_days) {
        v = lines -> join(right: documents,    kind: left, on: left.line_id = right.line_id)
                  -> join(right: overdue_days, kind: left, on: left.line_id = right.line_id)
        v -> switch {
            mode: overlapping                                    // a line may be both "credit note issued" and "money overdue"
            credit_note_issued:  credit_note_id is not null     because: "credit note {credit_note_id} issued"
            money_received:      payment_id is not null         because: "refund paid on {paid_date}"
            goods_shipped:       shipment_id is not null        because: "replacement shipped on {shipped_date}"
            money_overdue:       way in ("refund_no_return", "refund_after_return") and payment_id is null and max_late > 0
                                                                because: "refund {max_late} working days late"       detail: open_obligations
            goods_overdue:       way in ("replacement_no_return", "exchange") and shipment_id is null and max_late > 0
                                                                because: "replacement {max_late} working days late"  detail: open_obligations
            closed:              (payment_id is not null and way in ("refund_no_return", "refund_after_return"))
                                 or (shipment_id is not null and way in ("replacement_no_return", "exchange"))
                                                                because: "settled"
        }
    }
```

Held orders are rechecked every morning. Releasing a high-value order needs confirmation.

```ttrp
    actions {
        settlement.credit_note_issued -> fulfil    { line: line_id, kind: credit_note,  to: fulfilled }
        settlement.credit_note_issued -> expect    { line: line_id, kind: payment,      due: working_days(:settlement) }
        settlement.money_received     -> fulfil    { line: line_id, kind: payment,      to: fulfilled }
        settlement.goods_shipped      -> fulfil    { line: line_id, kind: replacement,  to: fulfilled }
        settlement.money_overdue      -> task      { who: role.finance,   about: line_id, detail: open_obligations }
        settlement.goods_overdue      -> task      { who: role.logistics, about: line_id, detail: open_obligations }
        settlement.closed             -> set_state { line: line_id, to: closed }
    }
```
