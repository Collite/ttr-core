// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/** All lookups are explicit and local. Catalog paths are root-relative; extends paths are file-relative. */
class ProfileResolver(
    root: Path,
    catalog: Map<String, Path>,
) {
    private val root = root.toRealPath()
    private val catalog = catalog.toMap()

    fun resolve(declaration: ProfileDeclaration?): List<ProfileDefinition> {
        if (declaration == null) return resolve("canonical/1", root.resolve("ttr.yaml"))
        checkRange(declaration.grammarRange, declaration.grammarLocation)
        return resolveAt(declaration.profile, Path.of(declaration.location.file), declaration.location)
    }

    fun resolve(
        reference: String,
        declaringFile: Path,
    ): List<ProfileDefinition> =
        resolveAt(reference, declaringFile, ProfileSpan(declaringFile.toString(), 1, 1, 1, 1, "/profile"))

    private fun resolveAt(
        reference: String,
        declaringFile: Path,
        location: ProfileSpan,
    ): List<ProfileDefinition> {
        val chain = mutableListOf<ProfileDefinition>()
        val visiting = mutableSetOf<Path>()

        fun visit(
            ref: String,
            from: Path,
            at: ProfileSpan,
            depth: Int,
        ) {
            val bundled = ref == "canonical" || ref == "canonical/1"
            val path = if (bundled) null else confined(ref, from, at)
            if (path != null && !visiting.add(path)) {
                throw ProfileException("ys/profile-cycle", at, "Profile cycle through $ref")
            }
            if (depth > 32) throw ProfileException("ys/profile-invalid", at, "Profile chain exceeds 32 profiles")
            val profile =
                if (bundled) {
                    ProfileReader.read(
                        requireNotNull(
                            javaClass.getResourceAsStream("/profiles/canonical.yaml"),
                        ) { "Missing bundled canonical profile" }
                            .bufferedReader()
                            .use { it.readText() },
                        "<bundled>/profiles/canonical.yaml",
                    )
                } else {
                    val source =
                        try {
                            path!!.readText()
                        } catch (e: IOException) {
                            throw ProfileException("ys/profile-not-found", at, "Cannot read profile $ref: ${e.message}")
                        }
                    ProfileReader.read(source, path.toString())
                }
            checkRange(profile.grammarRange, profile.locations.getValue("/grammar"))
            profile.extends?.let { parent ->
                visit(
                    parent,
                    path ?: from,
                    profile.locations.getValue("/extends"),
                    depth + 1,
                )
            }
            chain += profile
        }
        visit(reference, declaringFile, location, 1)
        return chain.toList()
    }

    private fun confined(
        ref: String,
        from: Path,
        at: ProfileSpan,
    ): Path {
        try {
            val candidate =
                catalog[ref]?.let { root.resolve(it) } ?: run {
                    val path = Path.of(ref)
                    if (!ref.startsWith(".") && !path.isAbsolute && !ref.endsWith(".yaml") && !ref.endsWith(".yml")) {
                        throw ProfileException("ys/profile-not-found", at, "No catalog entry for $ref")
                    }
                    // Relative caller paths are rooted explicitly, never resolved against the process cwd.
                    val file = if (from.isAbsolute) from else root.resolve(from)
                    file.parent.resolve(path)
                }
            val real = candidate.toRealPath()
            if (!real.startsWith(root) || !Files.isRegularFile(real)) {
                throw ProfileException("ys/profile-not-found", at, "Profile is outside root or is not a file: $ref")
            }
            return real
        } catch (e: IOException) {
            throw ProfileException("ys/profile-not-found", at, "Profile not found: $ref")
        } catch (e: java.nio.file.InvalidPathException) {
            throw ProfileException("ys/profile-not-found", at, "Invalid profile path: $ref")
        }
    }

    private fun checkRange(
        range: GrammarRange,
        at: ProfileSpan,
    ) {
        if (!range.admits()) {
            throw ProfileException(
                "ys/profile-grammar-mismatch",
                at,
                "${range.text} excludes running grammar ${GrammarRange.runningGrammar}",
            )
        }
    }
}
