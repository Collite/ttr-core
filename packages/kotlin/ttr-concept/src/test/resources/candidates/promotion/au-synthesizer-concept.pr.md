# cn: promote phase LargeOrder

Promotes cn candidate `2b8e4f10-7d3c-4a95-8e6b-51c0d9f2a7e3` — phase LargeOrder (`cn.LargeOrder`).

- **producer:** `au:synthesizer`
- **source:** `recipe://hartland.orders/order-review#s2`
- **confidence:** 0.64 (`au:synthesizer` · unbound-mention)
- **proposed:** 2026-10-23T09:20:31Z

### Evidence

- recipe_sentence `recipe://hartland.orders/order-review#s2`: Large orders, over 1000, wait for a manual review before they are released.

### er rendering

The phase as SQL over entities (`definition.render`, er):

```sql
SELECT "order".order_number
FROM "order"
WHERE "order"."value" > 1000
```
