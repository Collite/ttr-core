# XMI reader fixtures (MW-P5 S5.3, S5.3a)

Invented, no client names. `XmiReaderSpec` reads them in place.

- `world/` — the source world `shop` (15 definitions: cn, er, md), written in the spelling `ttr-mdg/1` emits (attributes
  in bytewise order, `is_a` as a list) and only with facts the XM-P1 XMI projection carries.
- `sources/shop.xmi` — `world/` exported by the XM-P1 writer (`ttr-metadata export xmi`); the spec re-exports and
  compares. `sources/shop-renamed.xmi` — the same export with the class `Product` renamed `Article` in EA (same GUID).
- `sources/orders-erd.xmi` — a plain EA ERD export (EA's own XMI 2.1 shape, `windows-1252`, one diagram).
- `sources/sales-billing.xmi` — an EA ERD with two UML packages, `sales` and `sales/billing` (nested), and one
  association across them (S5.3a).
- `model/` — `shop.xmi` through `ttr-mdg/1 --package shop`; `erd/` — `orders-erd.xmi` through `ea-erd/1 --package orders`;
  `erd-cn/` — the same through `profiles/ea-erd-cn.yaml` (a child with `cn-from-uml: true`).
- `multi/` — `sales-billing.xmi` through `ea-erd/1` with no `--package`: one TTR package per UML package
  (`package: {at: /model/package, verbatim: true}`), `sales` and `sales.billing`; `billed_to`'s end in `sales` is a
  qualified reference (`link … as: ref`), so the world loads strict with no `import`.
- `overlay.yaml` (keyed by the old qname) and `renames.yaml` — the overlay-orphan case on the renamed snapshot.
