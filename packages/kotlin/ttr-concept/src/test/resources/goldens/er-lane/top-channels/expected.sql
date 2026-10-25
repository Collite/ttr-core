SELECT sales.channel AS channel, SUM(sales.ext_sales_price) AS revenue
FROM sales
WHERE sales.sold_date >= DATE '2026-01-01' AND sales.sold_date < DATE '2027-01-01'
GROUP BY sales.channel
ORDER BY revenue DESC
LIMIT 2
