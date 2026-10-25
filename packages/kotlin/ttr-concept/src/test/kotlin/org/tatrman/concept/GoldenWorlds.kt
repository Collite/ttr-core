// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.ttrp.resolve.MdRepo
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * CN-P3 S3.7 — the golden fixture worlds (`resources/worlds/<name>/`), each one model root loaded the way Veles loads
 * one ([DefinitionFixtures.load]): `hartland` (the AU extension on er + the channel md face), `clinic` (the invented
 * clinical world — DC-1/2/7/8/11/12 and the Transfer assertion), `sales` (the md hero of E-0 and ActiveCustomer).
 * The md tier is loaded from the same root (`MdRepo` — ttr-metadata carries none) into [ResolvedCnModel.md] (S3.3);
 * [World.md] is the same object, for the oracles.
 */
internal object GoldenWorlds {
    val root: Path = DefinitionFixtures.repo.resolve("packages/kotlin/ttr-concept/src/test/resources/worlds")

    val names: List<String> = listOf("hartland", "clinic", "sales", "channel-reverse", "calendar-key", "er-parity")

    class World(
        val name: String,
        val dir: Path,
        val cn: ResolvedCnModel,
        val md: MdRepo.Loaded?,
    )

    private val cache = ConcurrentHashMap<String, World>()

    operator fun get(name: String): World = cache.computeIfAbsent(name, ::load)

    private fun load(name: String): World {
        val dir = root.resolve(name)
        require(Files.isDirectory(dir)) { "no golden world '$name' under $root" }
        val files =
            Files.walk(dir).use { s -> s.filter { it.isRegularFile() && it.extension == "ttrm" }.sorted().toList() }
        val model = DefinitionFixtures.load(files.associateBy { dir.relativize(it).toString() })
        val md = MdRepo.loadFrom(dir)
        return World(name, dir, ResolvedCnModel.of(model, snapshotHash = "golden:$name", md = md), md)
    }
}
