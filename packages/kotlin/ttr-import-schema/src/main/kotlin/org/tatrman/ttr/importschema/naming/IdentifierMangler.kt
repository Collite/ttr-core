// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.importschema.naming

/**
 * External SQL identifier → TTR-M identifier, per the SV-P4·S3 reconciliation of platform
 * contracts §12 (IQ-2) with the actual TTR-M grammar.
 *
 * TTR.g4's `IDENT` rule is `[a-zA-ZÀ-ɏ_][a-zA-Z0-9_À-ɏ]*` — Latin-1 /
 * Latin-Extended letters (every Czech diacritic: č ě ř š ž ů …) are valid identifier chars.
 * So Czech identifiers are legal TTR-M names *verbatim* (the ttrp-cli `aip-project` test fixture
 * carries them end to end). §12 rule 1's `[^a-z0-9_]→_` + lowercase
 * mangling predates confirmed unicode-identifier support and would needlessly destroy them.
 *
 * The reconciled policy (findings, tasks-sv-p4-s3):
 *  1. A source identifier already a valid TTR-M IDENT is preserved **verbatim** (case kept) —
 *     no mangle, no source-name carrier. This is the hero's dominant case.
 *  2. A genuinely-illegal identifier is deterministically mangled: illegal chars → `_`,
 *     runs collapsed, a leading digit gets a `_` prefix. The original is recorded (via
 *     [Mangled.original]) so the review checklist (F5) — not the model text (Q-3: the model
 *     stays clean canonical form) — carries the source-name and keeps the mapping invertible.
 *  3. No lowercasing (the grammar is case-sensitive; lowercasing invites collisions).
 *
 * Collision handling (§12 rule 3: mangled collisions are a hard error `TTRP-IMP-001`, never
 * auto-suffixed) is the caller's job — this is a pure per-identifier function.
 */
object IdentifierMangler {
    private val VALID_IDENT = Regex("^[a-zA-ZÀ-ɏ_][a-zA-Z0-9_À-ɏ]*$")
    private val ILLEGAL_CHAR = Regex("[^a-zA-Z0-9_À-ɏ]")
    private val UNDERSCORE_RUN = Regex("_+")

    data class Mangled(
        /** The TTR-M identifier to emit. */
        val ttrName: String,
        /** The verbatim source identifier — recorded in the checklist when [wasMangled]. */
        val original: String,
        val wasMangled: Boolean,
    )

    fun mangle(source: String): Mangled {
        if (VALID_IDENT.matches(source)) return Mangled(source, source, wasMangled = false)
        var s = ILLEGAL_CHAR.replace(source, "_")
        s = UNDERSCORE_RUN.replace(s, "_")
        if (s.isEmpty() || s.first().isDigit()) s = "_$s"
        return Mangled(s, source, wasMangled = true)
    }
}
