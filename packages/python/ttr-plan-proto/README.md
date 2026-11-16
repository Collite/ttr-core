# ttr-plan-proto (Python)

Pre-generated Python message classes for the **TTR-P plan pipeline** wire formats:
`plan.v1`, `transdsl.v1`, `dfdsl.v1`, the CN frames (`concept.v1`, `statement.v1`, `assertion.v1`) and the
translator's enums (`translate.v1`: `Language`, `SqlDialect`). Importable at their canonical module paths,
e.g. `from org.tatrman.plan.v1 import plan_pb2`.

**Canonical source of truth:** the `.proto` files in the sibling Kotlin module
`../../kotlin/ttr-plan-proto/src/main/proto/`. tatrman owns the wire format once
(decision TR-3); this wheel and the `org.tatrman:ttr-plan-proto` Maven jar are the
per-language published artifacts. Formerly generated inside kantheon `shared/proto`;
transferred at `f2e2efb` (2026-07-06). See `docs/ttr-translator/`.

## Build

Pure-Python wheel (`py3-none-any`). The build hook (`hatch_build.py`) runs
`grpcio-tools`' bundled `protoc` over the sibling `.proto` files — no JVM needed.
The generated `src/org/**` tree is gitignored and regenerated on every build.

```bash
uv build --wheel                 # or: pipx run build --wheel
uv run --with protobuf --with pytest pytest tests/
```

Released to PyPI via `python-plan/v<x.y.z>` (trusted publishing); versioned in
lockstep with the Maven `kotlin-translator/v*` pair by convention.

## Governance

Proto changes are driven by kantheon runtime needs (typically `PipelineContext`);
they arrive as PRs to this repo, which cuts a prompt lockstep release. Field
numbers/types are append-only within `v1` (see `docs/ttr-translator/architecture/contracts.md` §2).
