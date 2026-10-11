// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.bare

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Contracts §1: bare-fragment dialect markers resolve by double-extension or a first-line
 * comment override — the comment WINS (C3-g-ii), never content sniffing (P2). An unmarked
 * generic file resolves to null (→ TTRP-FRG-002 at the call site).
 */
class DialectMarkerSpec :
    StringSpec({
        "double extension .ttr.sql → sql, .ttr.py → pandas" {
            DialectMarker.resolve("crunch.ttr.sql", "SELECT 1") shouldBe "sql"
            DialectMarker.resolve("prep.ttr.py", "df.filter(x > 0)") shouldBe "pandas"
        }

        "first-line comment override wins over the extension" {
            // .ttr.sql extension but the comment says pandas → pandas (C3-g-ii).
            DialectMarker.resolve("mismatch.ttr.sql", "-- ttr: dialect=pandas\nx.filter(a > 0)") shouldBe "pandas"
            DialectMarker.resolve("report.sql", "-- ttr: dialect=sql\nSELECT 1") shouldBe "sql"
            DialectMarker.resolve("prep.py", "# ttr: dialect=pandas\nx.limit(1)") shouldBe "pandas"
        }

        "leading whitespace on the comment is tolerated; other content is not sniffed" {
            DialectMarker.resolve("x.txt", "   -- ttr: dialect=sql\n…") shouldBe "sql"
            DialectMarker.resolve("unmarked.sql", "SELECT 1 FROM t") shouldBe null // generic ext, no override → null
            DialectMarker.resolve("data.csv", "a,b,c") shouldBe null
        }

        "TTR-B skins: `.ttrb` is English, `.ttrb-cs` is Czech" {
            DialectMarker.resolve("report.ttrb", "Load a.") shouldBe "ttrb"
            DialectMarker.resolve("rules/rozhodnuti.ttrb-cs", "Načti a.") shouldBe "ttrb-cs"
        }

        "TTR-B header `# ttr: dialect=b lang=<xx>` — lang=cs is the Czech skin, lang=en / no lang English" {
            DialectMarker.resolve("x.txt", "# ttr: dialect=b lang=cs\nNačti a.") shouldBe "ttrb-cs"
            DialectMarker.resolve("x.txt", "# ttr: dialect=b lang=en\nLoad a.") shouldBe "ttrb"
            DialectMarker.resolve("x.txt", "# ttr: dialect=b\nLoad a.") shouldBe "ttrb"
            DialectMarker.resolve("x.txt", "# ttr: dialect=ttrb\nLoad a.") shouldBe "ttrb" // the pre-B2 spelling
            // an unknown language is kept — FRG-001 names it downstream
            DialectMarker.resolve("x.txt", "# ttr: dialect=b lang=de\nLade a.") shouldBe "ttrb-de"
        }

        "the TTR-B header wins over the extension" {
            DialectMarker.resolve("x.ttrb", "# ttr: dialect=b lang=cs\nNačti a.") shouldBe "ttrb-cs"
            DialectMarker.resolve("x.ttrb-cs", "# ttr: dialect=b lang=en\nLoad a.") shouldBe "ttrb"
        }
    })
