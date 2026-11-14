// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * `<root>/profiles/<file>.yaml` (any depth) by the identity each declares (`profile: <name>/<major>`), as `--profile` and the
 * generated-file guard resolve an identity (✅R269-B). [entries] are the unique identities, root-relative;
 * [duplicates] each identity two or more files declare, its files sorted (review-269 N2). Unreadable files skip.
 */
class ProfileCatalog(
    val entries: Map<String, Path>,
    val duplicates: Map<String, List<Path>>,
) {
    companion object {
        private val identity = Regex("""^profile:\s*['"]?([^'"\s#]+)""", RegexOption.MULTILINE)

        fun read(root: Path): ProfileCatalog {
            val directory = root.resolve("profiles")
            if (!Files.isDirectory(directory)) return ProfileCatalog(emptyMap(), emptyMap())
            val declared =
                Files
                    .walk(directory)
                    .use { paths ->
                        paths
                            .filter {
                                it.isRegularFile() &&
                                    directory.relativize(it).none { segment -> segment.name.startsWith(".") } &&
                                    (it.extension == "yaml" || it.extension == "yml")
                            }.toList()
                    }.sorted()
                    .mapNotNull { file ->
                        runCatching { identity.find(file.readText())?.groupValues?.get(1) }.getOrNull()?.let {
                            it to root.relativize(file)
                        }
                    }.groupBy({ it.first }, { it.second })
            val (unique, duplicated) = declared.entries.partition { it.value.size == 1 }
            return ProfileCatalog(
                unique.associate { it.key to it.value.single() },
                duplicated.associate { it.key to it.value },
            )
        }
    }
}
