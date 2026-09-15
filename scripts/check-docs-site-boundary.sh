#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# check-docs-site-boundary.sh — CI gate: the public docs site never reaches into
# the engineering record. Companion to the `docs-site` --strict build job.
#
# The rule (docs-dx.md §4, RO-27): `docs/ecosystem/` (the design corpus) and
# `docs/features/` (the per-effort engineering record) are NOT site content. The
# site is product documentation; those trees are how we decided to build it. A
# site page may *distill* that material into an explanation — it may never mirror
# it, and it may never relative-link into it.
#
# Two reasons this is a gate and not a guideline:
#   1. Correctness — a relative link from `docs-site/docs/**` into `docs/**` is
#      broken the instant the site is served standalone. It resolves on someone's
#      laptop and 404s in production. `mkdocs build --strict` does not catch it,
#      because the path escapes the docs dir it knows about.
#   2. Rot — the mirror is the wiki-rot mechanism. Two homes for one concept means
#      one of them is silently wrong, and the reader cannot tell which.
#
# If a site page genuinely needs to point at the engineering record, link OUTWARD
# to a GitHub URL (https://github.com/Collite/ttr-core/blob/master/docs/...): that
# is an explicit, durable handoff off the site rather than a hidden dependency.
#
# Run locally before opening a PR:  bash scripts/check-docs-site-boundary.sh
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

site_docs='docs-site/docs'

if [ ! -d "$site_docs" ]; then
  echo "check-docs-site-boundary: no $site_docs — nothing to check"
  exit 0
fi

# Markdown links/images whose target is a relative path climbing out of the site
# tree into docs/ecosystem/ or docs/features/. Matches `](...)` targets only, so
# prose naming the directory (as the landing page does, deliberately) is fine.
# Absolute https:// links are untouched by design — those are the sanctioned form.
pattern='\]\([^)]*(\.\./)+docs/(ecosystem|features)/'

offenders=$(grep -rnE --include='*.md' "$pattern" "$site_docs" || true)

if [ -n "$offenders" ]; then
  echo "::error::a docs-site page relative-links into the engineering record (docs-dx.md §4)."
  echo "$offenders"
  echo
  echo "docs/ecosystem/ and docs/features/ are the engineering record, not site content."
  echo "These links break the moment the site is served standalone. If the page truly"
  echo "needs to point there, use an absolute GitHub URL instead:"
  echo "  https://github.com/Collite/ttr-core/blob/master/docs/features/<...>"
  exit 1
fi

echo "check-docs-site-boundary: clean"
