SELECT "t3"."line_id", MAX(CASE WHEN "t3"."due" IS NULL THEN NULL ELSE COALESCE("t13"."_ttrp_n_n20_0", 0) END) AS "max_late", COUNT("t3"."kind") AS "kinds_open"
FROM (SELECT "t1"."line_id", "obligation"."id" AS "obligation_id", "obligation"."kind", "obligation"."due"
        FROM (SELECT "line_id", "request_number", "item", "quantity", "way", "customer0" AS "customer"
                FROM (SELECT "return_request_line"."line_id", "return_request_line"."request_number", "return_request_line"."line_no", "return_request_line"."item", "return_request_line"."quantity", "return_request_line"."unit_price", "return_request_line"."reason", "return_request_line"."requested_resolution", "return_request_line"."requested_variant", "return_request_line"."state", "return_request_line"."way", "return_request_line"."customer", "return_request"."customer" AS "customer0", "return_request"."order_number", "return_request"."channel", "return_request"."requested_date", "return_request"."state" AS "state0"
                        FROM "return_request_line"
                            INNER JOIN "return_request" ON "return_request_line"."request_number" = "return_request"."request_number") AS "t"
                WHERE "t"."state" IN ('goods_received', 'settling')) AS "t1"
            INNER JOIN "obligation" ON "obligation"."entity" = 'return_request_line' AND "obligation"."key" = CAST("t1"."line_id" AS VARCHAR)
        WHERE "obligation"."kind" IN ('credit_note', 'payment', 'replacement') AND "obligation"."state" IN ('open', 'overdue', 'reopened')) AS "t3"
    LEFT JOIN (SELECT "t9"."due" AS "_ttrp_k_n20_0_due", COUNT("t11"."_ttrp_d_n20_0") AS "_ttrp_n_n20_0"
        FROM (SELECT "obligation0"."due"
                FROM (SELECT "line_id", "request_number", "item", "quantity", "way", "customer0" AS "customer"
                        FROM (SELECT "return_request_line0"."line_id", "return_request_line0"."request_number", "return_request_line0"."line_no", "return_request_line0"."item", "return_request_line0"."quantity", "return_request_line0"."unit_price", "return_request_line0"."reason", "return_request_line0"."requested_resolution", "return_request_line0"."requested_variant", "return_request_line0"."state", "return_request_line0"."way", "return_request_line0"."customer", "return_request0"."customer" AS "customer0", "return_request0"."order_number", "return_request0"."channel", "return_request0"."requested_date", "return_request0"."state" AS "state0"
                                FROM "return_request_line" AS "return_request_line0"
                                    INNER JOIN "return_request" AS "return_request0" ON "return_request_line0"."request_number" = "return_request0"."request_number") AS "t4"
                        WHERE "t4"."state" IN ('goods_received', 'settling')) AS "t6"
                    INNER JOIN "obligation" AS "obligation0" ON "obligation0"."entity" = 'return_request_line' AND "obligation0"."key" = CAST("t6"."line_id" AS VARCHAR)
                WHERE "obligation0"."kind" IN ('credit_note', 'payment', 'replacement') AND "obligation0"."state" IN ('open', 'overdue', 'reopened')
                GROUP BY "obligation0"."due") AS "t9"
            INNER JOIN (SELECT *
                FROM (SELECT "cal_date" AS "_ttrp_d_n20_0", "working_day" AS "_ttrp_w_n20_0"
                        FROM "date_dim") AS "t10"
                WHERE "_ttrp_w_n20_0") AS "t11" ON "t9"."due" < "t11"."_ttrp_d_n20_0" AND "t11"."_ttrp_d_n20_0" <= :as_of
        GROUP BY "t9"."due") AS "t13" ON "t3"."due" = "t13"."_ttrp_k_n20_0_due"
GROUP BY "t3"."line_id"
