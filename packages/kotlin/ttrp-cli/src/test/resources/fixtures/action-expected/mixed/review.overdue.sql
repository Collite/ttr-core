SELECT "order_id", CAST("status" AS VARCHAR(MAX)) AS "reason", "due_date" AS "due", "amount"
FROM (SELECT "ORDER_ID" AS "order_id", "CUSTOMER_ID" AS "customer_id", "AMOUNT" AS "amount", "DUE_DATE" AS "due_date", "STATUS" AS "status"
        FROM "ORD_HEAD") AS "t"
WHERE "status" = 2
