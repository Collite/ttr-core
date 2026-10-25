// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.entry

import org.tatrman.ttr.metadata.model.DbTable
import org.tatrman.ttrp.resolve.ModelIndex
import org.tatrman.ttrp.resolve.ModelRepo
import java.nio.file.Path
import java.nio.file.Paths

/**
 * EN-P2 test fixtures (task 05.T1): the isolated `entry` md model (five change-semantics postures)
 * loaded through the real `ttr-metadata` path, plus §5 batch resources. Kept separate from the shared
 * erp fixture — the erp tables carry no entry declarations. Programs are tiny (recognition is by
 * filename; the verb-call/batch surface is deferred, contracts §1), so they are inlined in specs.
 */
object EntryFixtures {
    /** The classpath dir of the fixture `models/` (copied to `build/resources/test/entry/models`). */
    fun modelsRoot(): Path = Paths.get(EntryFixtures::class.java.getResource("/entry/models")!!.toURI())

    /** A [ModelIndex] over the fixture model. */
    fun modelIndex(): ModelIndex = ModelRepo.indexOf(modelsRoot())!!

    /** The resolved [DbTable] with simple name [name] (fixture models are all in package `entry`). */
    fun table(name: String): DbTable =
        modelIndex()
            .findLoadable(name, listOf(ModelIndex.importScope(listOf("entry"), "entry")))
            .filterIsInstance<DbTable>()
            .single()

    /** A §5 batch resource under `/entry/batches/<name>.json`. */
    fun batchResource(name: String): RowBatch =
        RowBatch.parse(EntryFixtures::class.java.getResource("/entry/batches/$name.json")!!.readText())

    /** A tiny apply program: an `import entry.*` so filename-recognized targets resolve in scope. */
    fun programSource(): String = "import entry.*\n"

    fun fileName(table: String): String = "$table-entry-apply.ttrp"
}
