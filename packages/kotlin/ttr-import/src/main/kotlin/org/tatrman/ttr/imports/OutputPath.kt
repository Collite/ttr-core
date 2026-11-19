// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

/** A file relative to --out, also for the empty package. Never returns an absolute path. */
internal fun outputPath(
    pkg: String,
    file: String,
): String = listOf(pkg.replace('.', '/'), file).filter { it.isNotEmpty() }.joinToString("/")
