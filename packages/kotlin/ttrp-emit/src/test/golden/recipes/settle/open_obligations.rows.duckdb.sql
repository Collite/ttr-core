SELECT "p_10317c93"."line_id", "p_132c5796"."id" AS "obligation_id", "p_132c5796"."kind", "p_132c5796"."due"
FROM "p_10317c93"
    INNER JOIN "p_132c5796" ON "p_132c5796"."entity" = 'return_request_line' AND "p_132c5796"."key" = CAST("p_10317c93"."line_id" AS VARCHAR(65536))
WHERE "p_132c5796"."kind" IN ('credit_note', 'payment', 'replacement') AND "p_132c5796"."state" IN ('open', 'overdue', 'reopened')
