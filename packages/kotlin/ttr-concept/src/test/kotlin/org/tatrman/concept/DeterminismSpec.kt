// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import io.kotest.core.spec.style.DescribeSpec

/**
 * CN-P0 S0.4 T1 — the determinism guard's SKELETON (architecture §7; filled at CN-P3 S3.8). `ttr-concept` is a pure
 * function of resolved, cached, snapshot-pinned inputs: the same model + the same frame must give a byte-identical
 * [LowerResult] (plan, dot-path and provenance) and a byte-identical [Rendering] text. Disabled until there is a body
 * to run against.
 */
class DeterminismSpec :
    DescribeSpec({
        xdescribe("the same inputs give byte-identical results (CN-P3 S3.8)") {
            it("statementLower twice over one model → identical LowerResult bytes") { TODO("P3") }
            it("assertionLower twice over one model → identical RowObjects") { TODO("P3") }
            it("definitionRender(er) twice → identical Rendering.text") { TODO("P3") }
        }
    })
