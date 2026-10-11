# Operate

> **I run this.**

Everything between `helm install` and a system you are willing to be paged for:
the values you are expected to set, the identity duty you cannot delegate to us,
where policy lives, and how to answer "why did it say that?" six months later.

## The pages

- [**The values contract**](values-contract.md) — every key the umbrella chart
  honours, what it does, and what happens if you leave it at its default.
- [**OIDC and Keycloak**](identity-and-oidc.md) — wiring your IdP, and the
  verification duty that stays yours.
- [**Deploy the Studio Viewer**](studio-viewer.md) — the read-only model viewer
  as a container next to Veles: runtime config, the read-only catalog proxy, and
  the cookie-session edge it expects.
- [**Policy in git**](policy-in-git.md) — the open validator store: writing,
  reviewing, and shipping policy the same way you ship the model.
- [**Upgrade and versioning**](upgrade-and-versioning.md) — what the product
  version promises you.
- [**Component renames (2026-08)**](component-renames.md) — every component
  dropped its `ttr-` prefix; the old→new table, and why nothing you call moved.

## Coming with the acceptance run

- **One question, one trace** — the observability story: following a single
  question end to end through the stack (lands with the traced quickstart).
- **Engine workers** — sizing and scaling the part that touches your data.
