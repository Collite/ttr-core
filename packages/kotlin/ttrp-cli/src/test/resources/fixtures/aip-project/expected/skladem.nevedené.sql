SELECT "t2"."číslo_objednávky", "t2"."id_produktu", "t2"."množství", "t2"."cena"
FROM (SELECT "t"."číslo_objednávky", "t"."id_produktu", "t"."množství", "t"."cena"
        FROM (SELECT "cs_order_number" AS "číslo_objednávky", "cs_item_sk" AS "id_produktu", "cs_warehouse_sk" AS "id_centra", "cs_quantity" AS "množství", "cs_sales_price" AS "cena"
                FROM "catalog_sales") AS "t"
            INNER JOIN (SELECT *
                FROM (SELECT "order_number" AS "číslo_objednávky", "bill_customer_sk" AS "id_zákazníka", "call_center_sk" AS "centrum_podpory", "sold_date_sk" AS "datum_prodeje"
                        FROM (
    SELECT * FROM catalog_orders WHERE ship_mode_type='EXPRESS'
) AS "objednávka__filter") AS "t0"
                WHERE "číslo_objednávky" = :objednávka_id AND "centrum_podpory" = 1) AS "t1" ON "t"."číslo_objednávky" = "t1"."číslo_objednávky") AS "t2"
    LEFT JOIN (SELECT "inv_item_sk" AS "id_produktu", 1 AS "_ttrp_exists"
        FROM "inventory"
        GROUP BY "inv_item_sk") AS "t5" ON "t2"."id_produktu" = "t5"."id_produktu"
WHERE "t5"."_ttrp_exists" IS NULL
