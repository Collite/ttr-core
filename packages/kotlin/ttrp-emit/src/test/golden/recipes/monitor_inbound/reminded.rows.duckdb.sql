SELECT "t0"."line_id", COUNT("p_c7d0a168"."run") AS "reminders"
FROM (SELECT "p_28401bc7"."line_id", "p_28401bc7"."request_number", "p_28401bc7"."item", "p_28401bc7"."quantity", "p_28401bc7"."way", "p_28401bc7"."customer0" AS "customer", "p_132c5796"."id" AS "obligation_id", "p_132c5796"."due", "p_132c5796"."state" AS "obligation_state"
        FROM "p_28401bc7"
            INNER JOIN "p_132c5796" ON "p_132c5796"."entity" = 'return_request_line' AND "p_132c5796"."key" = CAST("p_28401bc7"."line_id" AS VARCHAR(65536)) AND "p_132c5796"."kind" = 'inbound' AND "p_132c5796"."state" IN ('open', 'overdue', 'reopened')
        WHERE "p_28401bc7"."state" = 'awaiting_goods') AS "t0"
    LEFT JOIN "p_c7d0a168" ON "t0"."line_id" = "p_c7d0a168"."line_id" AND "p_c7d0a168"."route" = 'remind'
GROUP BY "t0"."line_id"
