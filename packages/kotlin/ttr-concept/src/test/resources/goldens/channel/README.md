# Channel — `revenue where WebSale` / `revenue by Channel` (A-H-7)

Source: `design/11-recipes-over-concepts.md` §2 L16-5 and §8 A-H-7, world `hartland`.

**⚑CN-P8 (default, plan.md):** A-H-7's literal forms need facts Hartland states only inside the `channel_sales` view's
SQL (`revenue where WebSale` → the `webSales` cubelet) and the measure hop (`revenue by Channel` → `channel_sales`) —
both md/hop's. P3 lowers a value kind and a value-kind phase through the **md reverse hop**: the md attribute whose hop
lands on `Channel`'s erTarget (`channel_sales.channel`; here the explicit `md2er_cubelet channel_sales_er`). The world's
`channelSales` cubelet carries `Channel.code` as its grain.

| case | | stage / demand |
|---|---|---|
| `where-websale-dimension` | `revenue` filtered by the phase `WebSale` (`channel = 'web'`) → `channelSales[channel.code: "web"].revenue @ sum` | S3.3 |
| `by-channel-dimension` | `revenue` grouped by `Channel` → `channelSales[channel.code: *].revenue @ sum` | S3.3 |
| `where-websale-carrier.pending` | the phase selects the `webSales` carrier (L16-5 literally) | md/hop |
| `by-channel-sales.pending` | `revenue by Channel` on `channel_sales` | md/hop |

Pending convention, case format and oracles: [`../README.md`](../README.md).
