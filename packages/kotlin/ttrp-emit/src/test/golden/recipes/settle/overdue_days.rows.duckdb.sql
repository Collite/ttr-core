SELECT "t0"."line_id", MAX(CASE WHEN "t0"."due" IS NULL THEN NULL ELSE COALESCE("t5"."_ttrp_n_n20_0", 0) END) AS "max_late", COUNT("t0"."kind") AS "kinds_open"
FROM (SELECT "p_10317c93"."line_id", "p_132c5796"."id" AS "obligation_id", "p_132c5796"."kind", "p_132c5796"."due"
        FROM "p_10317c93"
            INNER JOIN "p_132c5796" ON "p_132c5796"."entity" = 'return_request_line' AND "p_132c5796"."key" = CAST("p_10317c93"."line_id" AS VARCHAR(65536))
        WHERE "p_132c5796"."kind" IN ('credit_note', 'payment', 'replacement') AND "p_132c5796"."state" IN ('open', 'overdue', 'reopened')) AS "t0"
    LEFT JOIN (SELECT "t3"."due" AS "_ttrp_k_n20_0_due", COUNT("p_bbdaa0f4"."_ttrp_d_n20_0") AS "_ttrp_n_n20_0"
        FROM (SELECT "p_132c57960"."due"
                FROM "p_10317c93" AS "p_10317c930"
                    INNER JOIN "p_132c5796" AS "p_132c57960" ON "p_132c57960"."entity" = 'return_request_line' AND "p_132c57960"."key" = CAST("p_10317c930"."line_id" AS VARCHAR(65536))
                WHERE "p_132c57960"."kind" IN ('credit_note', 'payment', 'replacement') AND "p_132c57960"."state" IN ('open', 'overdue', 'reopened')
                GROUP BY "p_132c57960"."due") AS "t3"
            INNER JOIN "p_bbdaa0f4" ON "t3"."due" < "p_bbdaa0f4"."_ttrp_d_n20_0" AND "p_bbdaa0f4"."_ttrp_d_n20_0" <= :as_of
        GROUP BY "t3"."due") AS "t5" ON "t0"."due" = "t5"."_ttrp_k_n20_0_due"
GROUP BY "t0"."line_id"
