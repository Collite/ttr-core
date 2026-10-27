SELECT item.id, price.amount AS quoted_price
FROM item
LEFT JOIN price ON price.item = item.id AND price.channel = 'web' AND price.since <= {as_of} AND (price.until IS NULL OR price.until >= {as_of})
