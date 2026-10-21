SELECT "t1"."line_id", COUNT("monitor_inbound_verdict"."run") AS "reminders"
FROM (SELECT "t"."line_id", "t"."request_number", "t"."item", "t"."quantity", "t"."way", "t"."customer0" AS "customer", "obligation"."id" AS "obligation_id", "obligation"."due", "obligation"."state" AS "obligation_state"
        FROM (SELECT "return_request_line"."line_id", "return_request_line"."request_number", "return_request_line"."line_no", "return_request_line"."item", "return_request_line"."quantity", "return_request_line"."unit_price", "return_request_line"."reason", "return_request_line"."requested_resolution", "return_request_line"."requested_variant", "return_request_line"."state", "return_request_line"."way", "return_request_line"."customer", "return_request"."customer" AS "customer0", "return_request"."order_number", "return_request"."channel", "return_request"."requested_date", "return_request"."state" AS "state0"
                FROM "return_request_line"
                    INNER JOIN "return_request" ON "return_request_line"."request_number" = "return_request"."request_number") AS "t"
            INNER JOIN "obligation" ON "obligation"."entity" = 'return_request_line' AND "obligation"."key" = CAST("t"."line_id" AS VARCHAR) AND "obligation"."kind" = 'inbound' AND "obligation"."state" IN ('open', 'overdue', 'reopened')
        WHERE "t"."state" = 'awaiting_goods') AS "t1"
    LEFT JOIN "monitor_inbound_verdict" ON "t1"."line_id" = "monitor_inbound_verdict"."line_id" AND "monitor_inbound_verdict"."route" = 'remind'
GROUP BY "t1"."line_id"
