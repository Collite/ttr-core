// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.bare

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.ast.ContainerDecl
import org.tatrman.ttrp.ast.FragmentBody
import org.tatrman.ttrp.ast.PortKind
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId

/**
 * T6.3.3 / T6.3.4: a marked bare fragment file is a valid TTR-P program (C0). The wrapper container
 * (name from filename, target from `[ttrp] bare-target`, in-ports + program-level loads per derived
 * external ref, display) is synthesized as derived AST; the interior's bare names resolve via the
 * S18 default-imports prelude (C2-d: in-ports > imports > qnames). Bare-target missing ⇒ TTRP-FRG-003.
 */
class BareProgramSpec :
    StringSpec({
        fun containerOf(report: org.tatrman.ttrp.resolve.TtrpChecker.Report): ContainerDecl =
            report.document.statements
                .filterIsInstance<ContainerDecl>()
                .single()

        fun errors(report: org.tatrman.ttrp.resolve.TtrpChecker.Report) =
            report.diagnostics.filter { it.severity == Severity.ERROR }.map { it.id.id }

        "bare .ttr.sql compiles clean; container `crunch` synthesized @ the bare-target with an `accounts` in-port" {
            val report = BareFixtures.check("crunch.ttr.sql")
            errors(report) shouldBe emptyList()
            val c = containerOf(report)
            c.name shouldBe "crunch"
            c.target.parts.last() shouldBe "erp_pg"
            (c.body is FragmentBody) shouldBe true
            c.ports.filter { it.kind == PortKind.IN }.map { it.name } shouldContain "accounts"
        }

        "bare .ttr.py compiles clean (the receiver becomes a derived in-port)" {
            val report = BareFixtures.check("prep.ttr.py")
            errors(report) shouldBe emptyList()
            containerOf(report).ports.filter { it.kind == PortKind.IN }.map { it.name } shouldContain "accounts"
        }

        "bare .ttrb compiles clean; self-terminating (Show) ⇒ no synthesized out port" {
            val report = BareFixtures.check("report.ttrb")
            errors(report) shouldBe emptyList()
            val c = containerOf(report)
            c.name shouldBe "report"
            c.ports.none { it.kind == PortKind.OUT } shouldBe true
        }

        "bare .ttrb-cs (Czech skin) compiles clean, to the same canonical body as its English twin" {
            val cs = BareFixtures.check("report.ttrb-cs")
            errors(cs) shouldBe emptyList()
            val c = containerOf(cs)
            c.name shouldBe "report"
            (c.body as FragmentBody).tag shouldBe "ttrb-cs"
            c.ports.none { it.kind == PortKind.OUT } shouldBe true
            val en = containerOf(BareFixtures.check("report.ttrb"))
            org.tatrman.ttrp.dialect.b.TtrbCorpus.canonical(
                (c.body as FragmentBody).decomposition!!.statements,
            ) shouldBe
                org.tatrman.ttrp.dialect.b.TtrbCorpus
                    .canonical((en.body as FragmentBody).decomposition!!.statements)
        }

        "the interior is embedded verbatim (C2-f) — the fragment sourceText is the file bytes" {
            val c = containerOf(BareFixtures.check("crunch.ttr.sql"))
            (c.body as FragmentBody).sourceText.trim() shouldBe BareFixtures.read("crunch.ttr.sql").trim()
        }

        "missing [ttrp] bare-target ⇒ TTRP-FRG-003 (no fallback guessing, P2)" {
            val report = BareFixtures.check("crunch.ttr.sql", BareFixtures.manifest(bareTarget = null))
            errors(report) shouldContain TtrpDiagnosticId.FRG_003.id
        }

        "S18 boundary: default-imports NEVER leak into a canonical .ttrp document" {
            // A canonical file using a bare `accounts` with no import — synthesis does not run for .ttrp,
            // so `accounts` does not resolve via the manifest's default-imports.
            val src = "uses world \"acme.worlds.dev\"\nx = load(accounts)\n"
            val report =
                org.tatrman.ttrp.resolve
                    .TtrpChecker(
                        BareFixtures.manifest(),
                        org.tatrman.ttrp.resolve.ResolutionFixtures
                            .modelsRoot(),
                    ).check(src, "canonical.ttrp")
            (errors(report).isNotEmpty()) shouldBe true
        }

        "wrapper container name is filename-derived and identifier-sanitized (S12 analogue)" {
            WrapperSynthesizer.containerName("path/to/hero-crunch.ttr.sql") shouldBe "hero_crunch"
            WrapperSynthesizer.containerName("crunch.ttr.py") shouldBe "crunch"
        }
    })
