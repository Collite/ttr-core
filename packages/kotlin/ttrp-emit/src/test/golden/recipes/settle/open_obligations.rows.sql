SELECT "t1"."line_id", "obligation"."id" AS "obligation_id", "obligation"."kind", "obligation"."due"
FROM (SELECT "line_id", "request_number", "item", "quantity", "way", "customer0" AS "customer"
        FROM (SELECT "return_request_line"."line_id", "return_request_line"."request_number", "return_request_line"."line_no", "return_request_line"."item", "return_request_line"."quantity", "return_request_line"."unit_price", "return_request_line"."reason", "return_request_line"."requested_resolution", "return_request_line"."requested_variant", "return_request_line"."state", "return_request_line"."way", "return_request_line"."customer", "return_request"."customer" AS "customer0", "return_request"."order_number", "return_request"."channel", "return_request"."requested_date", "return_request"."state" AS "state0"
                FROM "return_request_line"
                    INNER JOIN "return_request" ON "return_request_line"."request_number" = "return_request"."request_number") AS "t"
        WHERE "t"."state" IN ('goods_received', 'settling')) AS "t1"
    INNER JOIN "obligation" ON "obligation"."entity" = 'return_request_line' AND "obligation"."key" = CAST("t1"."line_id" AS VARCHAR)
WHERE "obligation"."kind" IN ('credit_note', 'payment', 'replacement') AND "obligation"."state" IN ('open', 'overdue', 'reopened')
