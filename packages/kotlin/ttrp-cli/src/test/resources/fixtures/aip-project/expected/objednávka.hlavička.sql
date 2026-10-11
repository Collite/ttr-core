SELECT "číslo_objednávky", "id_zákazníka", "datum_prodeje"
FROM (SELECT "order_number" AS "číslo_objednávky", "bill_customer_sk" AS "id_zákazníka", "call_center_sk" AS "centrum_podpory", "sold_date_sk" AS "datum_prodeje"
        FROM (
    SELECT * FROM catalog_orders WHERE ship_mode_type='EXPRESS'
) AS "objednávka__filter") AS "t"
WHERE "číslo_objednávky" = :objednávka_id AND "centrum_podpory" = 1
