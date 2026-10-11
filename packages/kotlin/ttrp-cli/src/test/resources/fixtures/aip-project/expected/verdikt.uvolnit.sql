SELECT *
FROM (SELECT "číslo_objednávky", "id_zákazníka", "datum_prodeje"
        FROM (SELECT "order_number" AS "číslo_objednávky", "bill_customer_sk" AS "id_zákazníka", "call_center_sk" AS "centrum_podpory", "sold_date_sk" AS "datum_prodeje"
                FROM (
    SELECT * FROM catalog_orders WHERE ship_mode_type='EXPRESS'
) AS "objednávka__filter") AS "t"
        WHERE "číslo_objednávky" = :objednávka_id AND "centrum_podpory" = 1) AS "t1",
        (SELECT COUNT("id_produktu") AS "n_výpadků"
        FROM (SELECT "t5"."číslo_objednávky", "t5"."id_produktu", "t5"."množství", "t5"."cena", "t7"."datum_stavu", "t7"."id_centra", "t7"."na_skladě", COALESCE("t7"."na_skladě", 0) AS "disponibilní"
                FROM (SELECT "t2"."číslo_objednávky", "t2"."id_produktu", "t2"."množství", "t2"."cena"
                        FROM (SELECT "cs_order_number" AS "číslo_objednávky", "cs_item_sk" AS "id_produktu", "cs_warehouse_sk" AS "id_centra", "cs_quantity" AS "množství", "cs_sales_price" AS "cena"
                                FROM "catalog_sales") AS "t2"
                            INNER JOIN (SELECT *
                                FROM (SELECT "order_number" AS "číslo_objednávky", "bill_customer_sk" AS "id_zákazníka", "call_center_sk" AS "centrum_podpory", "sold_date_sk" AS "datum_prodeje"
                                        FROM (
    SELECT * FROM catalog_orders WHERE ship_mode_type='EXPRESS'
) AS "objednávka__filter") AS "t3"
                                WHERE "číslo_objednávky" = :objednávka_id AND "centrum_podpory" = 1) AS "t4" ON "t2"."číslo_objednávky" = "t4"."číslo_objednávky") AS "t5"
                    LEFT JOIN (SELECT *
                        FROM (SELECT "inv_date_sk" AS "datum_stavu", "inv_item_sk" AS "id_produktu", "inv_warehouse_sk" AS "id_centra", "inv_quantity_on_hand" AS "na_skladě"
                                FROM "inventory") AS "t6"
                        WHERE "id_centra" = :centrum AND "datum_stavu" = :stav_k) AS "t7" ON "t5"."id_produktu" = "t7"."id_produktu") AS "t8"
        WHERE "t8"."disponibilní" < "t8"."množství") AS "t11"
WHERE "t11"."n_výpadků" = 0
