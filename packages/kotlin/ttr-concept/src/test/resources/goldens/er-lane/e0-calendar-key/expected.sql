SELECT SUM(sales.ext_sales_price) AS revenue
FROM sales
JOIN date_dim ON date_dim.sk = sales.sold_date
WHERE date_dim.cal_date >= DATE '2026-01-01' AND date_dim.cal_date < DATE '2026-02-01'
