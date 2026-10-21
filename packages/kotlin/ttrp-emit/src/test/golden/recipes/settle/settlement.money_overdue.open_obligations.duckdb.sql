SELECT "t0"."line_id", "t0"."obligation_id", "t0"."kind", "t0"."due", 'money_overdue' AS "route", 'open_obligations' AS "context"
FROM (SELECT "p_10317c93"."line_id", "p_132c5796"."id" AS "obligation_id", "p_132c5796"."kind", "p_132c5796"."due"
        FROM "p_10317c93"
            INNER JOIN "p_132c5796" ON "p_132c5796"."entity" = 'return_request_line' AND "p_132c5796"."key" = CAST("p_10317c93"."line_id" AS VARCHAR(65536))
        WHERE "p_132c5796"."kind" IN ('credit_note', 'payment', 'replacement') AND "p_132c5796"."state" IN ('open', 'overdue', 'reopened')) AS "t0"
    INNER JOIN (SELECT "line_id"
        FROM (SELECT "p_65397b8d"."line_id", "p_65397b8d"."request_number", "p_65397b8d"."item", "p_65397b8d"."quantity", "p_65397b8d"."way", "p_65397b8d"."customer", "p_65397b8d"."credit_note_id", "p_65397b8d"."issued_date", "p_65397b8d"."payment_id", "p_65397b8d"."paid_date", "p_65397b8d"."shipment_id", "p_65397b8d"."shipped_date", "t9"."max_late", "t9"."kinds_open"
                FROM "p_65397b8d"
                    LEFT JOIN (SELECT "t2"."line_id", MAX(CASE WHEN "t2"."due" IS NULL THEN NULL ELSE COALESCE("t7"."_ttrp_n_n20_0", 0) END) AS "max_late", COUNT("t2"."kind") AS "kinds_open"
                        FROM (SELECT "p_10317c930"."line_id", "p_132c57960"."id" AS "obligation_id", "p_132c57960"."kind", "p_132c57960"."due"
                                FROM "p_10317c93" AS "p_10317c930"
                                    INNER JOIN "p_132c5796" AS "p_132c57960" ON "p_132c57960"."entity" = 'return_request_line' AND "p_132c57960"."key" = CAST("p_10317c930"."line_id" AS VARCHAR(65536))
                                WHERE "p_132c57960"."kind" IN ('credit_note', 'payment', 'replacement') AND "p_132c57960"."state" IN ('open', 'overdue', 'reopened')) AS "t2"
                            LEFT JOIN (SELECT "t5"."due" AS "_ttrp_k_n20_0_due", COUNT("p_bbdaa0f4"."_ttrp_d_n20_0") AS "_ttrp_n_n20_0"
                                FROM (SELECT "p_132c57961"."due"
                                        FROM "p_10317c93" AS "p_10317c931"
                                            INNER JOIN "p_132c5796" AS "p_132c57961" ON "p_132c57961"."entity" = 'return_request_line' AND "p_132c57961"."key" = CAST("p_10317c931"."line_id" AS VARCHAR(65536))
                                        WHERE "p_132c57961"."kind" IN ('credit_note', 'payment', 'replacement') AND "p_132c57961"."state" IN ('open', 'overdue', 'reopened')
                                        GROUP BY "p_132c57961"."due") AS "t5"
                                    INNER JOIN "p_bbdaa0f4" ON "t5"."due" < "p_bbdaa0f4"."_ttrp_d_n20_0" AND "p_bbdaa0f4"."_ttrp_d_n20_0" <= :as_of
                                GROUP BY "t5"."due") AS "t7" ON "t2"."due" = "t7"."_ttrp_k_n20_0_due"
                        GROUP BY "t2"."line_id") AS "t9" ON "p_65397b8d"."line_id" = "t9"."line_id") AS "t10"
        WHERE "t10"."way" IN ('refund_after_return', 'refund_no_return') AND "t10"."payment_id" IS NULL AND "t10"."max_late" > 0
        GROUP BY "line_id") AS "t13" ON "t0"."line_id" = "t13"."line_id"
