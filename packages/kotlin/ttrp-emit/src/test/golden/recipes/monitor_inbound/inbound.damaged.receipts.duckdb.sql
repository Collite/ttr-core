SELECT "t3"."line_id", "t3"."qty_ok", "t3"."qty_bad", "t3"."last_received", 'damaged' AS "route", 'receipts' AS "context"
FROM (SELECT "t0"."line_id", COALESCE(SUM(CASE WHEN "p_95135c37"."condition" = 'ok' THEN "p_95135c37"."quantity" ELSE 0 END), 0) AS "qty_ok", COALESCE(SUM(CASE WHEN "p_95135c37"."condition" <> 'ok' THEN "p_95135c37"."quantity" ELSE 0 END), 0) AS "qty_bad", MAX("p_95135c37"."received_date") AS "last_received"
        FROM (SELECT "p_28401bc7"."line_id", "p_28401bc7"."request_number", "p_28401bc7"."item", "p_28401bc7"."quantity", "p_28401bc7"."way", "p_28401bc7"."customer0" AS "customer", "p_132c5796"."id" AS "obligation_id", "p_132c5796"."due", "p_132c5796"."state" AS "obligation_state"
                FROM "p_28401bc7"
                    INNER JOIN "p_132c5796" ON "p_132c5796"."entity" = 'return_request_line' AND "p_132c5796"."key" = CAST("p_28401bc7"."line_id" AS VARCHAR(65536)) AND "p_132c5796"."kind" = 'inbound' AND "p_132c5796"."state" IN ('open', 'overdue', 'reopened')
                WHERE "p_28401bc7"."state" = 'awaiting_goods') AS "t0"
            LEFT JOIN "p_95135c37" ON "t0"."line_id" = "p_95135c37"."line_id"
        GROUP BY "t0"."line_id") AS "t3"
    INNER JOIN (SELECT "line_id"
        FROM (SELECT "t22"."line_id", "t22"."request_number", "t22"."item", "t22"."quantity", "t22"."way", "t22"."customer", "t22"."obligation_id", "t22"."due", "t22"."obligation_state", "t22"."qty_ok", "t22"."qty_bad", "t22"."last_received", "t22"."days_open", "t25"."reminders", CASE WHEN "t22"."qty_ok" >= "t22"."quantity" THEN 'complete' WHEN "t22"."qty_bad" > 0 OR "t22"."qty_ok" > 0 AND "t22"."qty_ok" < "t22"."quantity" THEN 'damaged' WHEN "t22"."days_open" > :inbound_close THEN 'expired' WHEN "t22"."days_open" > :inbound_reminder AND "t25"."reminders" = 0 THEN 'remind' ELSE NULL END AS "route"
                FROM (SELECT "t11"."line_id", "t11"."request_number", "t11"."item", "t11"."quantity", "t11"."way", "t11"."customer", "t11"."obligation_id", "t11"."due", "t11"."obligation_state", "t11"."qty_ok", "t11"."qty_bad", "t11"."last_received", "t21"."days_open"
                        FROM (SELECT "t5"."line_id", "t5"."request_number", "t5"."item", "t5"."quantity", "t5"."way", "t5"."customer", "t5"."obligation_id", "t5"."due", "t5"."obligation_state", "t10"."qty_ok", "t10"."qty_bad", "t10"."last_received"
                                FROM (SELECT "p_28401bc70"."line_id", "p_28401bc70"."request_number", "p_28401bc70"."item", "p_28401bc70"."quantity", "p_28401bc70"."way", "p_28401bc70"."customer0" AS "customer", "p_132c57960"."id" AS "obligation_id", "p_132c57960"."due", "p_132c57960"."state" AS "obligation_state"
                                        FROM "p_28401bc7" AS "p_28401bc70"
                                            INNER JOIN "p_132c5796" AS "p_132c57960" ON "p_132c57960"."entity" = 'return_request_line' AND "p_132c57960"."key" = CAST("p_28401bc70"."line_id" AS VARCHAR(65536)) AND "p_132c57960"."kind" = 'inbound' AND "p_132c57960"."state" IN ('open', 'overdue', 'reopened')
                                        WHERE "p_28401bc70"."state" = 'awaiting_goods') AS "t5"
                                    LEFT JOIN (SELECT "t7"."line_id", COALESCE(SUM(CASE WHEN "p_95135c370"."condition" = 'ok' THEN "p_95135c370"."quantity" ELSE 0 END), 0) AS "qty_ok", COALESCE(SUM(CASE WHEN "p_95135c370"."condition" <> 'ok' THEN "p_95135c370"."quantity" ELSE 0 END), 0) AS "qty_bad", MAX("p_95135c370"."received_date") AS "last_received"
                                        FROM (SELECT "p_28401bc71"."line_id", "p_28401bc71"."request_number", "p_28401bc71"."item", "p_28401bc71"."quantity", "p_28401bc71"."way", "p_28401bc71"."customer0" AS "customer", "p_132c57961"."id" AS "obligation_id", "p_132c57961"."due", "p_132c57961"."state" AS "obligation_state"
                                                FROM "p_28401bc7" AS "p_28401bc71"
                                                    INNER JOIN "p_132c5796" AS "p_132c57961" ON "p_132c57961"."entity" = 'return_request_line' AND "p_132c57961"."key" = CAST("p_28401bc71"."line_id" AS VARCHAR(65536)) AND "p_132c57961"."kind" = 'inbound' AND "p_132c57961"."state" IN ('open', 'overdue', 'reopened')
                                                WHERE "p_28401bc71"."state" = 'awaiting_goods') AS "t7"
                                            LEFT JOIN "p_95135c37" AS "p_95135c370" ON "t7"."line_id" = "p_95135c370"."line_id"
                                        GROUP BY "t7"."line_id") AS "t10" ON "t5"."line_id" = "t10"."line_id") AS "t11"
                            LEFT JOIN (SELECT "t14"."line_id", CASE WHEN "t14"."_ttrp_a_n15_0" IS NULL THEN NULL ELSE COALESCE("t20"."_ttrp_n_n15_0", 0) END AS "days_open"
                                FROM (SELECT "t13"."line_id", "t13"."request_number", "t13"."item", "t13"."quantity", "t13"."way", "t13"."customer", "t13"."obligation_id", "t13"."due", "t13"."obligation_state", "p_132c57963"."recipe", "p_132c57963"."run", "p_132c57963"."entity", "p_132c57963"."key", "p_132c57963"."kind", "p_132c57963"."due" AS "due0", "p_132c57963"."state", "p_132c57963"."fulfilled_by", "p_132c57963"."fulfilled_at", "p_132c57963"."created_at", "p_132c57963"."created_at" AS "_ttrp_a_n15_0"
                                        FROM (SELECT "p_28401bc72"."line_id", "p_28401bc72"."request_number", "p_28401bc72"."item", "p_28401bc72"."quantity", "p_28401bc72"."way", "p_28401bc72"."customer0" AS "customer", "p_132c57962"."id" AS "obligation_id", "p_132c57962"."due", "p_132c57962"."state" AS "obligation_state"
                                                FROM "p_28401bc7" AS "p_28401bc72"
                                                    INNER JOIN "p_132c5796" AS "p_132c57962" ON "p_132c57962"."entity" = 'return_request_line' AND "p_132c57962"."key" = CAST("p_28401bc72"."line_id" AS VARCHAR(65536)) AND "p_132c57962"."kind" = 'inbound' AND "p_132c57962"."state" IN ('open', 'overdue', 'reopened')
                                                WHERE "p_28401bc72"."state" = 'awaiting_goods') AS "t13"
                                            INNER JOIN "p_132c5796" AS "p_132c57963" ON "t13"."obligation_id" = "p_132c57963"."id") AS "t14"
                                    LEFT JOIN (SELECT "t18"."_ttrp_a_n15_0" AS "_ttrp_k_n15_0__ttrp_a_n15_0", COUNT("p_3c60bd77"."_ttrp_d_n15_0") AS "_ttrp_n_n15_0"
                                        FROM (SELECT "p_132c57965"."created_at" AS "_ttrp_a_n15_0"
                                                FROM (SELECT "p_28401bc73"."line_id", "p_28401bc73"."request_number", "p_28401bc73"."item", "p_28401bc73"."quantity", "p_28401bc73"."way", "p_28401bc73"."customer0" AS "customer", "p_132c57964"."id" AS "obligation_id", "p_132c57964"."due", "p_132c57964"."state" AS "obligation_state"
                                                        FROM "p_28401bc7" AS "p_28401bc73"
                                                            INNER JOIN "p_132c5796" AS "p_132c57964" ON "p_132c57964"."entity" = 'return_request_line' AND "p_132c57964"."key" = CAST("p_28401bc73"."line_id" AS VARCHAR(65536)) AND "p_132c57964"."kind" = 'inbound' AND "p_132c57964"."state" IN ('open', 'overdue', 'reopened')
                                                        WHERE "p_28401bc73"."state" = 'awaiting_goods') AS "t16"
                                                    INNER JOIN "p_132c5796" AS "p_132c57965" ON "t16"."obligation_id" = "p_132c57965"."id"
                                                GROUP BY "p_132c57965"."created_at") AS "t18"
                                            INNER JOIN "p_3c60bd77" ON "t18"."_ttrp_a_n15_0" < "p_3c60bd77"."_ttrp_d_n15_0" AND "p_3c60bd77"."_ttrp_d_n15_0" <= :as_of
                                        GROUP BY "t18"."_ttrp_a_n15_0") AS "t20" ON "t14"."_ttrp_a_n15_0" = "t20"."_ttrp_k_n15_0__ttrp_a_n15_0") AS "t21" ON "t11"."line_id" = "t21"."line_id") AS "t22"
                    LEFT JOIN (SELECT "t24"."line_id", COUNT("p_c7d0a168"."run") AS "reminders"
                        FROM (SELECT "p_28401bc74"."line_id", "p_28401bc74"."request_number", "p_28401bc74"."item", "p_28401bc74"."quantity", "p_28401bc74"."way", "p_28401bc74"."customer0" AS "customer", "p_132c57966"."id" AS "obligation_id", "p_132c57966"."due", "p_132c57966"."state" AS "obligation_state"
                                FROM "p_28401bc7" AS "p_28401bc74"
                                    INNER JOIN "p_132c5796" AS "p_132c57966" ON "p_132c57966"."entity" = 'return_request_line' AND "p_132c57966"."key" = CAST("p_28401bc74"."line_id" AS VARCHAR(65536)) AND "p_132c57966"."kind" = 'inbound' AND "p_132c57966"."state" IN ('open', 'overdue', 'reopened')
                                WHERE "p_28401bc74"."state" = 'awaiting_goods') AS "t24"
                            LEFT JOIN "p_c7d0a168" ON "t24"."line_id" = "p_c7d0a168"."line_id" AND "p_c7d0a168"."route" = 'remind'
                        GROUP BY "t24"."line_id") AS "t25" ON "t22"."line_id" = "t25"."line_id") AS "t26"
        WHERE "t26"."route" = 'damaged'
        GROUP BY "line_id") AS "t29" ON "t3"."line_id" = "t29"."line_id"
