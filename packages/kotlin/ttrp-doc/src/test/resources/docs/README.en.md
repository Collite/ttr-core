# Releasing a placed order

## What this recipe decides

- `hold_sales`: stock shortfall or price difference (details: `shortfall`, `price_differences`)
- `hold_ar`: overdue invoices (details: `overdue`)
- `release_confirmed`: all checks passed; above the confirmation threshold
- `release`: all checks passed

## What it reads

- `er.entity.customer_price`
- `er.entity.inventory`
- `er.entity.invoice`
- Order (`er.entity.order`)
- `er.entity.price_list`
- `er.entity.sales_line`
- `er.entity.warehouse_outage`

## What it does

- `verdict.release` → Release order (`release`): `order` = `order_number`. Would release this order.
- `verdict.release_confirmed` → `ask`: `about` = `order_number`, `action` = `release`, `due` = `2 working_days`, `on_due` = `hold { order: order_number, department: "Sales" }`, `who` = `role.sales_manager`
- `verdict.hold_sales` → `hold`: `department` = `"Sales"`, `order` = `order_number`
- `verdict.hold_sales` → `notify`: `detail` = `shortfall, price_differences`, `who` = `role.sales`
- `verdict.hold_ar` → `hold`: `department` = `"AR"`, `order` = `order_number`
- `verdict.hold_ar` → `notify`: `detail` = `overdue`, `who` = `role.ar`

## Parameters

- Tolerance (`tolerance`) = `0.01` (decimal)
- `confirmation_threshold` = `50000` (decimal)

## Mode

`per-candidate`
