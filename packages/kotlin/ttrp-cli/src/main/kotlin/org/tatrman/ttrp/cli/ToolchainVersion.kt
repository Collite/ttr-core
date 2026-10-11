// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.cli

import java.util.Properties

/**
 * The toolchain version this `ttrp` build is — the Gradle project version stamped at build time into
 * `org/tatrman/ttrp/cli/toolchain-version.properties` (ttrp-cli `build.gradle.kts`, `generateToolchainVersion`).
 * Every bundle the CLI builds records it (`manifest.json` and `<prog>.compile-record.json` `toolchain:
 * org.tatrman:ttrp:<version>`), and `ttrp --version` prints it. Falls back to the jar manifest's
 * `Implementation-Version`, then to `0.0.0-dev` (an unstamped, IDE-compiled classpath).
 */
object ToolchainVersion {
    val current: String by lazy {
        ToolchainVersion::class.java
            .getResourceAsStream("/org/tatrman/ttrp/cli/toolchain-version.properties")
            ?.use { stream -> Properties().apply { load(stream) }.getProperty("version") }
            ?.takeIf { it.isNotBlank() }
            ?: ToolchainVersion::class.java.`package`?.implementationVersion
            ?: "0.0.0-dev"
    }
}
