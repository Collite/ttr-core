# MD → ER hop

| MD element | ER landing | Rule / diagnostic |
| --- | --- | --- |
| md.cubelet.catalogSales | er.entity.catalog_sales | derived |
| md.cubelet.catalogSales.Calendar.day | er.entity.catalog_sales.sold | derived |
| md.cubelet.catalogSales.Customer.customerCode | er.entity.catalog_sales.customer | derived |
| md.cubelet.catalogSales.Product.code | er.entity.catalog_sales.item | derived |
| md.cubelet.orphan | — | md/er-unmapped |
| md.cubelet.orphan.Customer.customerCode | — | md/er-unmapped |
| md.cubelet.storeSales | er.entity.store_sales | derived |
| md.cubelet.storeSales.Calendar.day | er.entity.store_sales.sold | derived |
| md.cubelet.storeSales.Customer.customerCode | er.entity.store_sales.customer | derived |
| md.cubelet.storeSales.Product.code | er.entity.store_sales.item | derived |
| md.cubelet.webSales | er.entity.web_sales | derived |
| md.cubelet.webSales.Calendar.day | er.entity.web_sales.sold | derived |
| md.cubelet.webSales.Customer.customerCode | er.entity.web_sales.customer | derived |
| md.cubelet.webSales.Product.code | er.entity.web_sales.item | derived |
| md.dimension.Calendar | er.entity.date_dim | derived |
| md.dimension.Calendar.day | er.entity.date_dim.sk | derived |
| md.dimension.Customer | er.entity.customer | derived |
| md.dimension.Customer.birthYear | er.entity.customer.birthYear | derived |
| md.dimension.Customer.customerCode | er.entity.customer.sk | derived |
| md.dimension.Customer.state | er.entity.customer_address.state | derived |
| md.dimension.Product | er.entity.item | derived |
| md.dimension.Product.code | er.entity.item.sk | derived |
| md.domain.AddressCode | — | md/er-unmapped |
| md.domain.BirthYear | er.entity.customer.birthYear | derived |
| md.domain.CustomerCode | er.entity.customer.sk | derived |
| md.domain.Day | er.entity.date_dim.sk | derived |
| md.domain.ItemCode | er.entity.item.sk | derived |
| md.domain.LinkFrom | — | md/er-unmapped |
| md.domain.LinkTo | — | md/er-unmapped |
| md.domain.Money | — | md/er-unmapped |
| md.domain.State | er.entity.customer_address.state | derived |
| md.measure.revenue | — | md/er-ambiguous |

## Derived warehouse roles

| ER entity | Role | Why |
| --- | --- | --- |
| er.entity.catalog_sales | fact | cubelet md.cubelet.catalogSales (R1); measures and at least two many-to-one relations |
| er.entity.customer | dimension | dimension md.dimension.Customer (R3); one side of er.relation.catalog_sales_customer from a fact; one side of er.relation.store_sales_customer from a fact; one side of er.relation.web_sales_customer from a fact |
| er.entity.customer_address | unclassified |  |
| er.entity.customer_item | bridge | N:N map md.map.customer_items; flagged, not classified |
| er.entity.date_dim | dimension | dimension md.dimension.Calendar (R3); one side of er.relation.catalog_sales_sold from a fact; one side of er.relation.store_sales_sold from a fact; one side of er.relation.web_sales_sold from a fact |
| er.entity.household | unclassified |  |
| er.entity.item | dimension | dimension md.dimension.Product (R3); one side of er.relation.catalog_sales_item from a fact; one side of er.relation.store_sales_item from a fact; one side of er.relation.web_sales_item from a fact |
| er.entity.sales | unclassified |  |
| er.entity.store_sales | fact | cubelet md.cubelet.storeSales (R1); measures and at least two many-to-one relations |
| er.entity.view_sales | unclassified |  |
| er.entity.web_sales | fact | cubelet md.cubelet.webSales (R1); measures and at least two many-to-one relations |

## Findings

- warning: md/er-unmapped — md.cubelet.orphan: 'md.cubelet.orphan' has no er counterpart — no er2db for table 'db.dbo.table.orphan'
- warning: md/er-unmapped — md.cubelet.orphan.Customer.customerCode: 'md.cubelet.orphan.Customer.customerCode' has no er counterpart — no md2db binding for the element
- warning: md/er-unmapped — md.domain.AddressCode: 'md.domain.AddressCode' has no er counterpart — no md2db binding for the element
- warning: md/er-unmapped — md.domain.LinkFrom: 'md.domain.LinkFrom' has no er counterpart — no md2db binding for the element
- warning: md/er-unmapped — md.domain.LinkTo: 'md.domain.LinkTo' has no er counterpart — no md2db binding for the element
- warning: md/er-unmapped — md.domain.Money: 'md.domain.Money' has no er counterpart — no md2db binding for the element
- warning: md/er-ambiguous — md.measure.revenue: 'md.measure.revenue' maps to er.entity.catalog_sales.ext_sales_price, er.entity.sales.ext_sales_price, er.entity.store_sales.ext_sales_price, er.entity.web_sales.ext_sales_price; author md2er_cubelet/md2er_dimension to pick
