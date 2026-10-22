SELECT "order".order_number
FROM "order"
WHERE "order".state IN ('placed', 'held')
