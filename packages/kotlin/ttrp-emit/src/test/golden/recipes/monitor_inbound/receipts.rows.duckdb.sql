SELECT "t0"."line_id", COALESCE(SUM(CASE WHEN "p_95135c37"."condition" = 'ok' THEN "p_95135c37"."quantity" ELSE 0 END), 0) AS "qty_ok", COALESCE(SUM(CASE WHEN "p_95135c37"."condition" <> 'ok' THEN "p_95135c37"."quantity" ELSE 0 END), 0) AS "qty_bad", MAX("p_95135c37"."received_date") AS "last_received"
FROM (SELECT "p_28401bc7"."line_id", "p_28401bc7"."request_number", "p_28401bc7"."item", "p_28401bc7"."quantity", "p_28401bc7"."way", "p_28401bc7"."customer0" AS "customer", "p_132c5796"."id" AS "obligation_id", "p_132c5796"."due", "p_132c5796"."state" AS "obligation_state"
        FROM "p_28401bc7"
            INNER JOIN "p_132c5796" ON "p_132c5796"."entity" = 'return_request_line' AND "p_132c5796"."key" = CAST("p_28401bc7"."line_id" AS VARCHAR(65536)) AND "p_132c5796"."kind" = 'inbound' AND "p_132c5796"."state" IN ('open', 'overdue', 'reopened')
        WHERE "p_28401bc7"."state" = 'awaiting_goods') AS "t0"
    LEFT JOIN "p_95135c37" ON "t0"."line_id" = "p_95135c37"."line_id"
GROUP BY "t0"."line_id"
