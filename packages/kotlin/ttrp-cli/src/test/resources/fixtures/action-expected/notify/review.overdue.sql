SELECT "order_id", "due_date", "amount"
FROM (SELECT "t"."order_id", "t"."customer_id", "t"."amount", "t"."due_date", "t"."status", "t0"."email", "t0"."full_name", "t0"."email" AS "recipient", "t0"."full_name" AS "subject"
        FROM (SELECT "ORDER_ID" AS "order_id", "CUSTOMER_ID" AS "customer_id", "AMOUNT" AS "amount", "DUE_DATE" AS "due_date", "STATUS" AS "status"
                FROM "ORD_HEAD") AS "t"
            INNER JOIN (SELECT "CUSTOMER_ID" AS "customer_id", "EMAIL" AS "email", "FULL_NAME" AS "full_name"
                FROM "CUST") AS "t0" ON "t"."customer_id" = "t0"."customer_id") AS "t1"
WHERE "t1"."status" = 2
