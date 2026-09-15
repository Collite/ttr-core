<!-- SPDX-License-Identifier: Apache-2.0 -->
# The values contract

*Reference. Every key the umbrella chart honours, what it does, and what happens if you leave it at
its default. This page is the operator's projection of the chart's documented values contract; the
[canonical contract](https://github.com/Collite/ttr-server/blob/master/helm/tatrman-server/values-contract.md)
ships with the chart and is the source of truth — if a key is not in it, do not guess what it does.*

The umbrella chart installs the whole product from one release. You configure it with values; this
page is the contract for those values, so nothing is folklore reconstructed from defaults.

## Shape

- **`global.image.tag`** — the product image tag applied across services. Precedence for any one
  service is `image.tag` (per-service override) → `global.image.tag` → the chart's `appVersion`.
  Leave per-service tags unset and pin `global.image.tag` for a uniform install.
- **Enable toggles** — each service has an `<name>.enabled` flag. The chart ships a sensible default
  set; turn a service off and its workload is not rendered. The value key is not always the image
  or directory name — the contract's key↔image map is the authority.

## Identity and OIDC (the keys you must get right)

Governance is per-user, so identity configuration is not optional. Under `auth`:

- **`auth.issuerUrl`** — your OIDC issuer.
- **`auth.audience`** — the expected token audience.
- **`auth.jwksUri`** — where the doors fetch signing keys.
- **`auth.realm`** — the realm (Keycloak-shaped).
- **`auth.verification`** — `ingress` or `in-door`: *where* the bearer token is verified before
  pipeline entry. The mechanism is yours to choose; **that verification happens at all is not** —
  see [OIDC and Keycloak](identity-and-oidc.md).
- **`auth.trustedNetworkShortcuts`** — default off. The `X-User-Id` / `user_id` edge shortcuts are
  outside the MCP identity contract; enabling them is your authority alone, and they are ignored
  whenever a real bearer token resolves.

The claim conventions (user id from `preferred_username`→`sub`, roles from `realm_access.roles`) are
fixed by the identity contract and are **not** configurable — only the mapping from your IdP to
those two facts is.

### Dev Keycloak — quickstart only

`devIdp.*` stands up a throwaway Keycloak that seeds a realm, a `tatrman-agent` public client, and a
`demo`/`demo` user. It exists so a stranger can complete the quickstart without wiring an IdP first.
**It is not for production** — bring your own IdP and set the `auth.*` keys above.

## Understanding-layer and per-service config

- `nlp.backends.*`, `geo.*`, `resolver.*` — configuration for the understanding layer
  (grounding, resolution, fuzzy). UFAL/NC-licensed NLP backends are opt-in and off by default.
- Per-service passthrough — each service exposes its own values under its key for resource requests,
  replicas and service-specific settings.

## Versioning

The chart's **`appVersion` is the product version** — the promise you get about compatibility across
an upgrade. See [Upgrade and versioning](upgrade-and-versioning.md) for what it commits to (RO-24).

For the full key-by-key list, resource defaults and the value-key↔image map, read the canonical
contract linked at the top of this page — this projection covers the keys an operator sets, not
every internal default.
