// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.wire

import io.kotest.core.spec.style.StringSpec
import org.tatrman.translator.framework.HartlandJoinModel

/**
 * review-318 F1 — a NULL literal decoded with a type. The wire's surface tag `datetime` stands for DATE, TIME and
 * TIMESTAMP alike; read back as TIMESTAMP, a NULL beside a DATE or TIME value left a CASE or a UNION without a common
 * type, and `unparseFromRelNode` decodes too — so the single call failed as well, not only the re-entry. Only tags
 * whose every folded type has a common type with the rebuilt one (`text`, `bool`, `int`, `float`) are typed.
 */
class TypedNullReentrySpec :
    StringSpec({
        val parity = ReentryParity(HartlandJoinModel.handle())

        "N1 — CASE … THEN NULL ELSE a DATE literal: single call unparses, two halves give its SQL (PG, MSSQL)" {
            parity.sameSql("SELECT CASE WHEN revenue = 0 THEN NULL ELSE DATE '2026-01-01' END AS r FROM sale")
        }

        "N2 — CASE … THEN NULL ELSE a TIME literal: the same" {
            parity.sameSql("SELECT CASE WHEN revenue = 0 THEN NULL ELSE TIME '10:00:00' END AS r FROM sale")
        }

        "N3 — a DATE beside a NULL in UNION ALL: the same" {
            parity.sameSql("SELECT DATE '2026-01-01' AS r FROM sale UNION ALL SELECT NULL AS r FROM sale")
        }

        "N4 — a DATE column (cast from text) beside a NULL in a CASE: the same" {
            parity.sameSql("SELECT CASE WHEN revenue = 0 THEN NULL ELSE CAST(sale_month AS DATE) END AS r FROM sale")
        }

        "N5 — a float NULL (M7's shape) keeps its type: two halves byte for byte" {
            parity.sameBytes("SELECT CASE WHEN revenue = 0 THEN NULL ELSE revenue END AS r FROM sale")
        }

        // A `datetime` NULL stays untyped (it may be a DATE or a TIME), so a TIMESTAMP's NULL re-encodes as
        // `unknown:NULL` as on master: not byte for byte, the same SQL.
        "N6 — CASE … THEN NULL ELSE a TIMESTAMP literal: the same SQL both ways" {
            parity.sameSql(
                "SELECT CASE WHEN revenue = 0 THEN NULL ELSE TIMESTAMP '2026-01-01 10:00:00' END AS r FROM sale",
            )
        }
    })
