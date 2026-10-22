# SPDX-License-Identifier: Apache-2.0
"""Hatchling build hook: generate the plan.v1 / transdsl.v1 / dfdsl.v1 Python
message classes from the canonical `.proto` files before the wheel is assembled.

The `.proto` source of truth lives in the sibling Kotlin module
(`../../kotlin/ttr-plan-proto/src/main/proto/`) — tatrman owns the wire format
once (TR-3); this wheel is the pre-generated Python artifact. protoc is provided
by `grpcio-tools` (a build dependency), so the wheel is pure-Python (`py3-none-any`)
and consumers need no toolchain. The generated `src/org/**` tree is gitignored and
regenerated on every build (no stale drift), mirroring ttr-parser's `_generated/`.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path
from typing import Any

from hatchling.builders.hooks.plugin.interface import BuildHookInterface

# Relative to the proto source root — matches the import paths kantheon's
# generated code emits (`from org.tatrman.plan.v1 import plan_pb2`).
PROTOS = [
    "org/tatrman/plan/v1/plan.proto",
    "org/tatrman/plan/v1/context.proto",
    "org/tatrman/plan/v1/parameters.proto",
    "org/tatrman/transdsl/v1/transdsl.proto",
    "org/tatrman/dfdsl/v1/dfdsl.proto",
    # CN (conceptual model) — the frames below the frontier, frozen at CN-P3 S3.1: the shared refs, the read frame
    # (`statement.v1`) and the write frame (`assertion.v1`). A Python frame builder (kantheon) builds them.
    "org/tatrman/concept/v1/refs.proto",
    "org/tatrman/statement/v1/statement.proto",
    "org/tatrman/assertion/v1/assertion.proto",
]


class CustomBuildHook(BuildHookInterface):
    PLUGIN_NAME = "custom"

    def initialize(self, version: str, build_data: dict[str, Any]) -> None:
        root = Path(self.root)
        proto_root = (root / ".." / ".." / "kotlin" / "ttr-plan-proto" / "src" / "main" / "proto").resolve()
        if not proto_root.is_dir():
            raise RuntimeError(
                f"proto source root not found: {proto_root} — this wheel builds from the "
                "monorepo's Kotlin module; build from a checkout, not a standalone sdist."
            )

        out = root / "src"
        out.mkdir(parents=True, exist_ok=True)

        subprocess.run(
            [
                sys.executable,
                "-m",
                "grpc_tools.protoc",
                f"--proto_path={proto_root}",
                f"--python_out={out}",
                *PROTOS,
            ],
            check=True,
        )

        # protoc emits no __init__.py, but the `org.tatrman.*` import paths need
        # every level to be an importable package.
        #
        # The `org` and `org.tatrman` levels are SHARED with other distributions
        # (kantheon's `shared-proto` also contributes `org.tatrman.*` modules), so
        # they must stay PEP 420 namespace packages — NO `__init__.py` — otherwise a
        # regular `org.tatrman` package here shadows the other distribution's
        # submodules and `from org.tatrman.<x>.v1 import …` fails for one of them.
        # Deeper levels (`org.tatrman.plan`, `.transdsl`, `.dfdsl`, the cn `.concept` / `.statement` /
        # `.assertion`, and below) are
        # wheel-owned and get a regular `__init__.py`.
        org = out / "org"
        namespace_dirs = {org, org / "tatrman"}
        for directory in [org, *[p for p in org.rglob("*") if p.is_dir()]]:
            init = directory / "__init__.py"
            if directory in namespace_dirs:
                # Keep these PEP 420 namespace levels clean — remove any stale
                # __init__.py a previous build may have left in the reused src/ tree.
                if init.exists():
                    init.unlink()
                continue
            if not init.exists():
                init.write_text("")
