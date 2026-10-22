SELECT customer.sk FROM customer WHERE EXISTS (SELECT 1 FROM invoice WHERE invoice.customer = customer.sk AND invoice.status = 'open' AND invoice.due < {as_of})
