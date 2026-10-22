SELECT customer.sk
FROM customer
LEFT JOIN (
  SELECT return_request.customer, SUM(return_request_line.amount) AS amount
  FROM return_request_line
  JOIN return_request ON return_request.request_id = return_request_line.request_id
  WHERE return_request.requested_date >= {window_lo} AND return_request.requested_date <= {as_of}
  GROUP BY return_request.customer
) return_share ON return_share.customer = customer.sk
LEFT JOIN (
  SELECT sales.customer, SUM(sales.ext_sales_price) AS ext_sales_price
  FROM sales
  WHERE sales.sold_date >= {window_lo} AND sales.sold_date <= {as_of}
  GROUP BY sales.customer
) return_share_2 ON return_share_2.customer = customer.sk
WHERE return_share.amount / return_share_2.ext_sales_price > 0.4
