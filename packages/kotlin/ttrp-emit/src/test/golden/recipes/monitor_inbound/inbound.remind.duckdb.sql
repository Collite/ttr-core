SELECT "line_id", "request_number", "item", "quantity", "way", "customer", "obligation_id", "due", "obligation_state", "qty_ok", "qty_bad", "last_received", "days_open", "reminders", "route", 'nothing returned after ' || CAST(:inbound_reminder AS VARCHAR(65536)) || ' days' AS "verdict_reason"
FROM (SELECT "t17"."line_id", "t17"."request_number", "t17"."item", "t17"."quantity", "t17"."way", "t17"."customer", "t17"."obligation_id", "t17"."due", "t17"."obligation_state", "t17"."qty_ok", "t17"."qty_bad", "t17"."last_received", "t17"."days_open", "t20"."reminders", CASE WHEN "t17"."qty_ok" >= "t17"."quantity" THEN 'complete' WHEN "t17"."qty_bad" > 0 OR "t17"."qty_ok" > 0 AND "t17"."qty_ok" < "t17"."quantity" THEN 'damaged' WHEN "t17"."days_open" > :inbound_close THEN 'expired' WHEN "t17"."days_open" > :inbound_reminder AND "t20"."reminders" = 0 THEN 'remind' ELSE NULL END AS "route"
        FROM (SELECT "t6"."line_id", "t6"."request_number", "t6"."item", "t6"."quantity", "t6"."way", "t6"."customer", "t6"."obligation_id", "t6"."due", "t6"."obligation_state", "t6"."qty_ok", "t6"."qty_bad", "t6"."last_received", "t16"."days_open"
                FROM (SELECT "t0"."line_id", "t0"."request_number", "t0"."item", "t0"."quantity", "t0"."way", "t0"."customer", "t0"."obligation_id", "t0"."due", "t0"."obligation_state", "t5"."qty_ok", "t5"."qty_bad", "t5"."last_received"
                        FROM (SELECT "p_28401bc7"."line_id", "p_28401bc7"."request_number", "p_28401bc7"."item", "p_28401bc7"."quantity", "p_28401bc7"."way", "p_28401bc7"."customer0" AS "customer", "p_132c5796"."id" AS "obligation_id", "p_132c5796"."due", "p_132c5796"."state" AS "obligation_state"
                                FROM "p_28401bc7"
                                    INNER JOIN "p_132c5796" ON "p_132c5796"."entity" = 'return_request_line' AND "p_132c5796"."key" = CAST("p_28401bc7"."line_id" AS VARCHAR(65536)) AND "p_132c5796"."kind" = 'inbound' AND "p_132c5796"."state" IN ('open', 'overdue', 'reopened')
                                WHERE "p_28401bc7"."state" = 'awaiting_goods') AS "t0"
                            LEFT JOIN (SELECT "t2"."line_id", COALESCE(SUM(CASE WHEN "p_95135c37"."condition" = 'ok' THEN "p_95135c37"."quantity" ELSE 0 END), 0) AS "qty_ok", COALESCE(SUM(CASE WHEN "p_95135c37"."condition" <> 'ok' THEN "p_95135c37"."quantity" ELSE 0 END), 0) AS "qty_bad", MAX("p_95135c37"."received_date") AS "last_received"
                                FROM (SELECT "p_28401bc70"."line_id", "p_28401bc70"."request_number", "p_28401bc70"."item", "p_28401bc70"."quantity", "p_28401bc70"."way", "p_28401bc70"."customer0" AS "customer", "p_132c57960"."id" AS "obligation_id", "p_132c57960"."due", "p_132c57960"."state" AS "obligation_state"
                                        FROM "p_28401bc7" AS "p_28401bc70"
                                            INNER JOIN "p_132c5796" AS "p_132c57960" ON "p_132c57960"."entity" = 'return_request_line' AND "p_132c57960"."key" = CAST("p_28401bc70"."line_id" AS VARCHAR(65536)) AND "p_132c57960"."kind" = 'inbound' AND "p_132c57960"."state" IN ('open', 'overdue', 'reopened')
                                        WHERE "p_28401bc70"."state" = 'awaiting_goods') AS "t2"
                                    LEFT JOIN "p_95135c37" ON "t2"."line_id" = "p_95135c37"."line_id"
                                GROUP BY "t2"."line_id") AS "t5" ON "t0"."line_id" = "t5"."line_id") AS "t6"
                    LEFT JOIN (SELECT "t9"."line_id", CASE WHEN "t9"."_ttrp_a_n15_0" IS NULL THEN NULL ELSE COALESCE("t15"."_ttrp_n_n15_0", 0) END AS "days_open"
                        FROM (SELECT "t8"."line_id", "t8"."request_number", "t8"."item", "t8"."quantity", "t8"."way", "t8"."customer", "t8"."obligation_id", "t8"."due", "t8"."obligation_state", "p_132c57962"."recipe", "p_132c57962"."run", "p_132c57962"."entity", "p_132c57962"."key", "p_132c57962"."kind", "p_132c57962"."due" AS "due0", "p_132c57962"."state", "p_132c57962"."fulfilled_by", "p_132c57962"."fulfilled_at", "p_132c57962"."created_at", "p_132c57962"."created_at" AS "_ttrp_a_n15_0"
                                FROM (SELECT "p_28401bc71"."line_id", "p_28401bc71"."request_number", "p_28401bc71"."item", "p_28401bc71"."quantity", "p_28401bc71"."way", "p_28401bc71"."customer0" AS "customer", "p_132c57961"."id" AS "obligation_id", "p_132c57961"."due", "p_132c57961"."state" AS "obligation_state"
                                        FROM "p_28401bc7" AS "p_28401bc71"
                                            INNER JOIN "p_132c5796" AS "p_132c57961" ON "p_132c57961"."entity" = 'return_request_line' AND "p_132c57961"."key" = CAST("p_28401bc71"."line_id" AS VARCHAR(65536)) AND "p_132c57961"."kind" = 'inbound' AND "p_132c57961"."state" IN ('open', 'overdue', 'reopened')
                                        WHERE "p_28401bc71"."state" = 'awaiting_goods') AS "t8"
                                    INNER JOIN "p_132c5796" AS "p_132c57962" ON "t8"."obligation_id" = "p_132c57962"."id") AS "t9"
                            LEFT JOIN (SELECT "t13"."_ttrp_a_n15_0" AS "_ttrp_k_n15_0__ttrp_a_n15_0", COUNT("p_3c60bd77"."_ttrp_d_n15_0") AS "_ttrp_n_n15_0"
                                FROM (SELECT "p_132c57964"."created_at" AS "_ttrp_a_n15_0"
                                        FROM (SELECT "p_28401bc72"."line_id", "p_28401bc72"."request_number", "p_28401bc72"."item", "p_28401bc72"."quantity", "p_28401bc72"."way", "p_28401bc72"."customer0" AS "customer", "p_132c57963"."id" AS "obligation_id", "p_132c57963"."due", "p_132c57963"."state" AS "obligation_state"
                                                FROM "p_28401bc7" AS "p_28401bc72"
                                                    INNER JOIN "p_132c5796" AS "p_132c57963" ON "p_132c57963"."entity" = 'return_request_line' AND "p_132c57963"."key" = CAST("p_28401bc72"."line_id" AS VARCHAR(65536)) AND "p_132c57963"."kind" = 'inbound' AND "p_132c57963"."state" IN ('open', 'overdue', 'reopened')
                                                WHERE "p_28401bc72"."state" = 'awaiting_goods') AS "t11"
                                            INNER JOIN "p_132c5796" AS "p_132c57964" ON "t11"."obligation_id" = "p_132c57964"."id"
                                        GROUP BY "p_132c57964"."created_at") AS "t13"
                                    INNER JOIN "p_3c60bd77" ON "t13"."_ttrp_a_n15_0" < "p_3c60bd77"."_ttrp_d_n15_0" AND "p_3c60bd77"."_ttrp_d_n15_0" <= :as_of
                                GROUP BY "t13"."_ttrp_a_n15_0") AS "t15" ON "t9"."_ttrp_a_n15_0" = "t15"."_ttrp_k_n15_0__ttrp_a_n15_0") AS "t16" ON "t6"."line_id" = "t16"."line_id") AS "t17"
            LEFT JOIN (SELECT "t19"."line_id", COUNT("p_c7d0a168"."run") AS "reminders"
                FROM (SELECT "p_28401bc73"."line_id", "p_28401bc73"."request_number", "p_28401bc73"."item", "p_28401bc73"."quantity", "p_28401bc73"."way", "p_28401bc73"."customer0" AS "customer", "p_132c57965"."id" AS "obligation_id", "p_132c57965"."due", "p_132c57965"."state" AS "obligation_state"
                        FROM "p_28401bc7" AS "p_28401bc73"
                            INNER JOIN "p_132c5796" AS "p_132c57965" ON "p_132c57965"."entity" = 'return_request_line' AND "p_132c57965"."key" = CAST("p_28401bc73"."line_id" AS VARCHAR(65536)) AND "p_132c57965"."kind" = 'inbound' AND "p_132c57965"."state" IN ('open', 'overdue', 'reopened')
                        WHERE "p_28401bc73"."state" = 'awaiting_goods') AS "t19"
                    LEFT JOIN "p_c7d0a168" ON "t19"."line_id" = "p_c7d0a168"."line_id" AND "p_c7d0a168"."route" = 'remind'
                GROUP BY "t19"."line_id") AS "t20" ON "t17"."line_id" = "t20"."line_id") AS "t21"
WHERE "t21"."route" = 'remind'
