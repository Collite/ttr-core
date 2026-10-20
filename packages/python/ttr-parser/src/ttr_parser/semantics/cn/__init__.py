# SPDX-License-Identifier: Apache-2.0
"""0.17 - the conceptual model in semantics (CN-P2): the desugar (`desugar`), one grounding per
concept (`groundings`) and the model-level `cn/...` checks (`validate`). Mirrors the TS twin
(`packages/semantics/src/cn/`) and the Kotlin one (`org.tatrman.ttr.semantics.cn`).

No re-exports here: the symbol table imports `desugar`, and `groundings` / `validate` import the
resolver, which imports the symbol table — import the submodules directly."""
