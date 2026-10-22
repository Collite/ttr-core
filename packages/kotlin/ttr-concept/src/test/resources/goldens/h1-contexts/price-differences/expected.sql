SELECT sales_line.order_number, sales_line.line_no, sales_line.item, sales_line.unit_price, COALESCE(customer_price.price, price_list.price) AS list_price
FROM "order"
JOIN sales_line ON sales_line.order_number = "order".order_number
LEFT JOIN customer_price ON customer_price.item = sales_line.item AND customer_price.customer = "order".customer AND customer_price.valid_from <= "order".placed_date AND (customer_price.valid_to IS NULL OR customer_price.valid_to >= "order".placed_date)
LEFT JOIN price_list ON price_list.item = sales_line.item AND price_list.channel = "order".channel AND price_list.valid_from <= "order".placed_date AND (price_list.valid_to IS NULL OR price_list.valid_to >= "order".placed_date)
WHERE "order".state IN ('placed', 'held') AND (COALESCE(customer_price.price, price_list.price) IS NULL OR ABS(sales_line.unit_price - COALESCE(customer_price.price, price_list.price)) > {tolerance})
