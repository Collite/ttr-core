// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.yaml.snakeyaml.Yaml

/**
 * RV-P1.1 — the published `.schema.json` files and the Kotlin validator must agree.
 *
 * The schemas are the contract an author, an editor or a non-JVM consumer reads; the validator is
 * the JVM enforcement, written by hand so the published artifact ships no JSON stack. That is a
 * legitimate split only while the two cannot drift, which is what this spec guarantees: every
 * fixture is run through BOTH, and a disagreement in either direction fails.
 *
 * networknt is test-scope here — exactly as in ttrp-emit, ttrp-cli and ttrp-lsp.
 */
class SchemaEquivalenceSpec :
    FunSpec({

        val mapper = ObjectMapper()
        val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

        fun schema(resource: String): JsonSchema =
            factory.getSchema(
                requireNotNull(this::class.java.getResourceAsStream(resource)) { "missing schema: $resource" }
                    .reader()
                    .readText(),
            )

        val lexiconSchema = schema("/schemas/ttr-lexicon.v1.schema.json")
        val skillSchema = schema("/schemas/ttr-skill.v1.schema.json")

        fun fixture(path: String): String =
            requireNotNull(this::class.java.getResourceAsStream("/lexicon-schema-fixtures/$path")) {
                "missing fixture: $path"
            }.reader().readText()

        /** YAML → JSON tree, so the schema engine can see what the author wrote. */
        fun tree(yaml: String) = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(Yaml().load<Any>(yaml))

        fun schemaAccepts(
            s: JsonSchema,
            yaml: String,
        ): Boolean = s.validate(tree(yaml)).isEmpty()

        context("valid fixtures: schema and validator both accept") {
            listOf(
                "aliases-minimal.lex.yaml",
                "values-with-defaults.lex.yaml",
                "grounding-triggers.lex.yaml",
                // RV-44 (RV-P3.0): declared matching profiles. Additive to `ttr-lexicon/v1`, so
                // this fixture and the three above are validated by the SAME schema id.
                "match-profiles.lex.yaml",
                // LP (P2a T3): the `pred:` slice. Same schema id again — a new target PREFIX is
                // not a new file shape, which is the whole reason `ground:` needed no bump either.
                "predicate-triggers.lex.yaml",
            ).forEach { name ->
                test(name) {
                    schemaAccepts(lexiconSchema, fixture("valid/$name")) shouldBe true
                    LexiconValidator
                        .loadDataFile(fixture("valid/$name"), name)
                        .shouldBeInstanceOf<LexiconLoad.Ok<LexiconDataFile>>()
                }
            }

            test("skill-trend.md") {
                val fm =
                    FrontmatterSplitter
                        .split(fixture("valid/skill-trend.md"))
                        .shouldBeInstanceOf<LexiconLoad.Ok<Frontmatter>>()
                        .value

                schemaAccepts(skillSchema, fm.yaml) shouldBe true
                LexiconValidator
                    .loadSkillFile(fixture("valid/skill-trend.md"), "skill-trend.md")
                    .shouldBeInstanceOf<LexiconLoad.Ok<SkillDef>>()
            }
        }

        context("invalid fixtures: schema and validator both reject") {
            // Every YAML data fixture the catalogue covers. `duplicate-term` is deliberately absent:
            // JSON Schema cannot express "no two terms in this file share text+lang", which is
            // exactly why that one rule lives in Kotlin only — see the validator's KDoc.
            listOf(
                "unknown-method.lex.yaml",
                "missing-target.lex.yaml",
                "typos-without-distance.lex.yaml",
                "unknown-top-level-key.lex.yaml",
                "schema-id-mismatch.lex.yaml",
                "bad-lang.lex.yaml",
                // RV-P1.6 (RV-42): expressible in JSON Schema as a conditional pattern, so
                // both sides enforce the closed kind set.
                "ground-unknown-kind.lex.yaml",
                // RV-44 (RV-P3.0): every profile rule below IS expressible in JSON Schema — the
                // closed norm enum, the `dependentRequired` anchor for `typos`, the score bounds,
                // and the `method`/`match` exclusion as a `not: {required: [both]}`. The ONE
                // exception is the `exact − d·penalty` bound, which compares two sibling fields
                // arithmetically and therefore joins duplicate-term below.
                "profile-unknown-norm.lex.yaml",
                "profile-typos-without-exact.lex.yaml",
                "profile-method-and-match.lex.yaml",
                "profile-score-out-of-range.lex.yaml",
                // LP (P2a T1): the closed `pred:` kind set, expressible as a conditional pattern
                // exactly as `ground:`'s is — so both sides enforce it.
                "pred-unknown-kind.lex.yaml",
            ).forEach { name ->
                test(name) {
                    schemaAccepts(lexiconSchema, fixture("invalid/$name")) shouldBe false
                    LexiconValidator
                        .loadDataFile(fixture("invalid/$name"), name)
                        .shouldBeInstanceOf<LexiconLoad.Rejected>()
                }
            }

            listOf("skill-op-not-prefixed.md", "skill-zero-triggers.md").forEach { name ->
                test(name) {
                    val fm =
                        FrontmatterSplitter
                            .split(fixture("invalid/$name"))
                            .shouldBeInstanceOf<LexiconLoad.Ok<Frontmatter>>()
                            .value

                    schemaAccepts(skillSchema, fm.yaml) shouldBe false
                    LexiconValidator
                        .loadSkillFile(fixture("invalid/$name"), name)
                        .shouldBeInstanceOf<LexiconLoad.Rejected>()
                }
            }
        }

        test("the typos-budget rule is Kotlin-only, and the schema knowingly passes it") {
            // JSON Schema has no way to say "`exact` must exceed `distance` x `penalty`" — it
            // cannot do arithmetic across two sibling fields. Every individual bound in this
            // fixture holds, which is exactly why the schema accepts it and Kotlin must not.
            val yaml = fixture("invalid/profile-typos-exhausts-score.lex.yaml")

            schemaAccepts(lexiconSchema, yaml) shouldBe true
            LexiconValidator
                .loadDataFile(yaml, "profile-typos-exhausts-score.lex.yaml")
                .shouldBeInstanceOf<LexiconLoad.Rejected>()
                .codes shouldBe listOf(LexiconErrors.TYPOS_BUDGET_EXHAUSTS_SCORE)
        }

        test("the pred: FORM rule is Kotlin-only, and the schema knowingly passes it") {
            // RG-LEX-031 asks a per-language word list ("is `with` a function word in en?").
            // JSON Schema cannot carry one, and inlining 70 words as a pattern would be a second
            // copy of the list — so this joins duplicate-term and the typos budget on the
            // Kotlin-only side. Both weak forms are reported: a slice is authored in bulk.
            val yaml = fixture("invalid/pred-weak-form.lex.yaml")

            schemaAccepts(lexiconSchema, yaml) shouldBe true
            LexiconValidator
                .loadDataFile(yaml, "pred-weak-form.lex.yaml")
                .shouldBeInstanceOf<LexiconLoad.Rejected>()
                .codes shouldBe listOf(LexiconErrors.WEAK_PREDICATE_FORM, LexiconErrors.WEAK_PREDICATE_FORM)
        }

        test("the pred: all-function-words PHRASE rule is Kotlin-only too (review-103 N5)") {
            val yaml = fixture("invalid/pred-stopword-phrase.lex.yaml")

            schemaAccepts(lexiconSchema, yaml) shouldBe true
            LexiconValidator
                .loadDataFile(yaml, "pred-stopword-phrase.lex.yaml")
                .shouldBeInstanceOf<LexiconLoad.Rejected>()
                .codes shouldBe listOf(LexiconErrors.WEAK_PREDICATE_FORM, LexiconErrors.WEAK_PREDICATE_FORM)
        }

        test("the pred: WIDTH rule is Kotlin-only, and the schema knowingly passes it (review-103 F1)") {
            // RG-LEX-032 counts tokens on `TermNormalizer`'s whitespace. A JSON-Schema pattern
            // could approximate that, but it would be a second definition of "a token", and the
            // one the resolver's windows are sized against lives in Kotlin.
            val yaml = fixture("invalid/pred-wide-form.lex.yaml")

            schemaAccepts(lexiconSchema, yaml) shouldBe true
            LexiconValidator
                .loadDataFile(yaml, "pred-wide-form.lex.yaml")
                .shouldBeInstanceOf<LexiconLoad.Rejected>()
                .codes shouldBe listOf(LexiconErrors.WIDE_PREDICATE_FORM, LexiconErrors.WIDE_PREDICATE_FORM)
        }

        test("the duplicate-term rule is Kotlin-only, and the schema knowingly passes it") {
            val yaml = fixture("invalid/duplicate-term.lex.yaml")

            schemaAccepts(lexiconSchema, yaml) shouldBe true
            LexiconValidator
                .loadDataFile(yaml, "duplicate-term.lex.yaml")
                .shouldBeInstanceOf<LexiconLoad.Rejected>()
                .codes shouldBe listOf(LexiconErrors.DUPLICATE_TERM)
        }
    })
