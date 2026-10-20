// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cn

import org.tatrman.ttr.metadata.model.CnSchema
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.reconcile.ReconciliationResult
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.metadata.source.SourceSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** CN-P2 S2.5 — a cn project on disk, loaded the way Veles loads one (one `FileBasedSource`, reconciled). */
internal class CnProject(
    val snapshot: SourceSnapshot,
    val result: ReconciliationResult,
) {
    val cn: CnSchema get() = result.model.schemas.getValue("cn") as CnSchema

    fun concept(name: String) = cn.byName(name) ?: error("no concept '$name' in ${cn.concepts.keys.map { it.name }}")
}

internal object CnFixtures {
    /** The conformance fixtures (`tests/conformance/fixtures`). */
    val conformance: Path by lazy { locate("tests/conformance/fixtures") }

    /** This module's cn test resources. */
    val resources: Path by lazy { locate("packages/kotlin/ttr-metadata/src/test/resources/cn") }

    /** Load [files] (path under the model root → source file) as one source. The root's dirs give the packages. */
    fun load(files: Map<String, Path>): CnProject {
        val root = Files.createTempDirectory("cn-model")
        for ((rel, from) in files) {
            val p = root.resolve(rel)
            Files.createDirectories(p.parent)
            Files.writeString(p, Files.readString(from))
        }
        val snapshot = FileBasedSource("cn", 100, LocalFsStorage(id = "cn", rootPath = root)).load()
        return CnProject(snapshot, ModelReconciler(ModelDescriptor(id = "d", name = "d")).reconcile(listOf(snapshot)))
    }

    /** Every file of [dir] under [pkgDir] (`hartland/stores`). */
    fun dir(
        dir: Path,
        pkgDir: String,
    ): Map<String, Path> = Files.list(dir).use { s -> s.sorted().toList() }.associateBy { "$pkgDir/${it.fileName}" }

    /**
     * Conformance fixtures 75 (`model cn`) and 76 (`model binding`) as one package `hartland.concepts`, beside the
     * er / md / db faces they target (`resources/cn/concepts`).
     */
    fun concepts(): CnProject =
        load(
            dir(resources.resolve("concepts"), "hartland/concepts") +
                mapOf(
                    "hartland/concepts/c-concepts.ttrm" to conformance.resolve("75-cn-concepts.ttrm"),
                    "hartland/concepts/d-bindings.ttrm" to conformance.resolve("76-cn-bindings.ttrm"),
                ),
        )

    /**
     * Fixture 75's concepts with fixture 78's lexicon in the same package. Apart from [concepts]: 78's term `revenue`
     * makes 75's `md.revenue` ambiguous to the Kotlin resolver (ttr-core#228) — the links are what this one reads.
     */
    fun lexicon(): CnProject =
        load(
            dir(resources.resolve("concepts"), "hartland/concepts") +
                mapOf(
                    "hartland/concepts/c-concepts.ttrm" to conformance.resolve("75-cn-concepts.ttrm"),
                    "hartland/concepts/g-lexicon.ttrm" to conformance.resolve("78-cn-lexicon.ttrm"),
                ),
        )

    /** The hop-present project (`resources/cn/hop`, package `hartland.web`). */
    fun hop(): CnProject = load(dir(resources.resolve("hop"), "hartland/web"))

    fun cn(
        name: String,
        pkg: String,
    ) = QualifiedName(SchemaCode.CN, "concept", name, pkg)

    private fun locate(rel: String): Path {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve(rel)
            if (Files.isDirectory(candidate)) return candidate
            dir = dir.parent
        }
        error("could not locate $rel")
    }
}
