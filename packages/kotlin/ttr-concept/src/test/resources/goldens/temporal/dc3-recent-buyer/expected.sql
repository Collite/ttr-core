SELECT customer.sk FROM customer WHERE EXISTS (SELECT 1 FROM "order" o WHERE o.customer = customer.sk AND o.placed_date >= {window_lo} AND o.placed_date <= {as_of})
