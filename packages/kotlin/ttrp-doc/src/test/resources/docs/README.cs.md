# Uvolnění objednávky

## O čem recept rozhoduje

- `hold_sales`: stock shortfall or price difference (podrobnosti: `shortfall`, `price_differences`)
- `hold_ar`: overdue invoices (podrobnosti: `overdue`)
- `release_confirmed`: all checks passed; above the confirmation threshold
- `release`: all checks passed

## Co čte

- `er.entity.customer_price`
- `er.entity.inventory`
- `er.entity.invoice`
- Objednávka (`er.entity.order`)
- `er.entity.price_list`
- `er.entity.sales_line`
- `er.entity.warehouse_outage`

## Co dělá

- `verdict.release` → Uvolnit objednávku (`release`): `order` = `order_number`. Objednávka by byla uvolněna.
- `verdict.release_confirmed` → `ask`: `about` = `order_number`, `action` = `release`, `due` = `2 working_days`, `on_due` = `hold { order: order_number, department: "Sales" }`, `who` = `role.sales_manager`
- `verdict.hold_sales` → `hold`: `department` = `"Sales"`, `order` = `order_number`
- `verdict.hold_sales` → `notify`: `detail` = `shortfall, price_differences`, `who` = `role.sales`
- `verdict.hold_ar` → `hold`: `department` = `"AR"`, `order` = `order_number`
- `verdict.hold_ar` → `notify`: `detail` = `overdue`, `who` = `role.ar`

## Parametry

- Tolerance ceny (`tolerance`) = `0.01` (decimal)
- `confirmation_threshold` = `50000` (decimal)

## Režim

`per-candidate`
