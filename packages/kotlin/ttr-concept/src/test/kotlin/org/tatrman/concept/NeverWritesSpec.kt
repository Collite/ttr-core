// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.toPath

/**
 * CN-P3 S3.5 T5 — cn never executes a write (contracts §2.3; architecture §8: `assertion.lower` never holds a write
 * credential): no class `ttr-concept` compiles references JDBC (`java.sql`, `javax.sql`) or the translator's DML
 * (`StoreNode`, `StoreDmlUnparser`) — read from the compiled classes' constant pools, so an import, a fully qualified
 * name and a reflective string are all caught. The scan is proven able to see such a reference on the test classes.
 */
class NeverWritesSpec :
    FunSpec({
        fun classes(root: Path): List<Path> =
            Files.walk(root).use { s -> s.filter { it.isRegularFile() && it.extension == "class" }.sorted().toList() }

        fun references(
            root: Path,
            forbidden: List<String>,
        ): List<String> =
            classes(root).flatMap { c ->
                val pool = String(Files.readAllBytes(c), Charsets.ISO_8859_1)
                forbidden.filter { it in pool }.map { "${root.relativize(c)} → $it" }
            }

        val main =
            ConceptService::class.java.protectionDomain.codeSource.location
                .toURI()
                .toPath()

        test("the main classes reference no JDBC and no DML") {
            classes(main).size shouldBeGreaterThan 20
            references(main, FORBIDDEN).shouldBeEmpty()
        }

        test("the scan sees a JDBC reference where there is one — the live suite's") {
            val tests =
                NeverWritesSpec::class.java.protectionDomain.codeSource.location
                    .toURI()
                    .toPath()
            references(tests, FORBIDDEN).shouldNotBeEmpty()
        }
    }) {
    companion object {
        val FORBIDDEN =
            listOf(
                "java/sql/",
                "javax/sql/",
                "org/tatrman/plan/v1/StoreNode",
                "StoreDmlUnparser",
            )
    }
}
