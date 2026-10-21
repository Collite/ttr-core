SELECT "t1"."line_id", CASE WHEN "t1"."_ttrp_a_n15_0" IS NULL THEN NULL ELSE COALESCE("t7"."_ttrp_n_n15_0", 0) END AS "days_open"
FROM (SELECT "t0"."line_id", "t0"."request_number", "t0"."item", "t0"."quantity", "t0"."way", "t0"."customer", "t0"."obligation_id", "t0"."due", "t0"."obligation_state", "p_132c57960"."recipe", "p_132c57960"."run", "p_132c57960"."entity", "p_132c57960"."key", "p_132c57960"."kind", "p_132c57960"."due" AS "due0", "p_132c57960"."state", "p_132c57960"."fulfilled_by", "p_132c57960"."fulfilled_at", "p_132c57960"."created_at", "p_132c57960"."created_at" AS "_ttrp_a_n15_0"
        FROM (SELECT "p_28401bc7"."line_id", "p_28401bc7"."request_number", "p_28401bc7"."item", "p_28401bc7"."quantity", "p_28401bc7"."way", "p_28401bc7"."customer0" AS "customer", "p_132c5796"."id" AS "obligation_id", "p_132c5796"."due", "p_132c5796"."state" AS "obligation_state"
                FROM "p_28401bc7"
                    INNER JOIN "p_132c5796" ON "p_132c5796"."entity" = 'return_request_line' AND "p_132c5796"."key" = CAST("p_28401bc7"."line_id" AS VARCHAR(65536)) AND "p_132c5796"."kind" = 'inbound' AND "p_132c5796"."state" IN ('open', 'overdue', 'reopened')
                WHERE "p_28401bc7"."state" = 'awaiting_goods') AS "t0"
            INNER JOIN "p_132c5796" AS "p_132c57960" ON "t0"."obligation_id" = "p_132c57960"."id") AS "t1"
    LEFT JOIN (SELECT "t5"."_ttrp_a_n15_0" AS "_ttrp_k_n15_0__ttrp_a_n15_0", COUNT("p_3c60bd77"."_ttrp_d_n15_0") AS "_ttrp_n_n15_0"
        FROM (SELECT "p_132c57962"."created_at" AS "_ttrp_a_n15_0"
                FROM (SELECT "p_28401bc70"."line_id", "p_28401bc70"."request_number", "p_28401bc70"."item", "p_28401bc70"."quantity", "p_28401bc70"."way", "p_28401bc70"."customer0" AS "customer", "p_132c57961"."id" AS "obligation_id", "p_132c57961"."due", "p_132c57961"."state" AS "obligation_state"
                        FROM "p_28401bc7" AS "p_28401bc70"
                            INNER JOIN "p_132c5796" AS "p_132c57961" ON "p_132c57961"."entity" = 'return_request_line' AND "p_132c57961"."key" = CAST("p_28401bc70"."line_id" AS VARCHAR(65536)) AND "p_132c57961"."kind" = 'inbound' AND "p_132c57961"."state" IN ('open', 'overdue', 'reopened')
                        WHERE "p_28401bc70"."state" = 'awaiting_goods') AS "t3"
                    INNER JOIN "p_132c5796" AS "p_132c57962" ON "t3"."obligation_id" = "p_132c57962"."id"
                GROUP BY "p_132c57962"."created_at") AS "t5"
            INNER JOIN "p_3c60bd77" ON "t5"."_ttrp_a_n15_0" < "p_3c60bd77"."_ttrp_d_n15_0" AND "p_3c60bd77"."_ttrp_d_n15_0" <= :as_of
        GROUP BY "t5"."_ttrp_a_n15_0") AS "t7" ON "t1"."_ttrp_a_n15_0" = "t7"."_ttrp_k_n15_0__ttrp_a_n15_0"
