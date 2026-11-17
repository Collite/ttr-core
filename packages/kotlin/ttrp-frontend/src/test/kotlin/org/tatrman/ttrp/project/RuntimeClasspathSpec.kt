// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.project

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain

/**
 * AU-export S1.5.9 T1 (c) (D-235, MW S4.1): ttrp-frontend is a library, so every reader of the model that pulls it in
 * (ttr-server's automation fat jar among them) carries its runtime classpath. No CLI rides it: not ttr-metadata-cli, not
 * clikt/mordant/jna, not ttr-import, not the CLIs' `slf4j-nop` binding. The build passes the published `runtimeClasspath`'s
 * components in `ttr.runtimeClasspath` (gradle/runtime-classpath-components.gradle.kts; the test classpath is no witness:
 * kotest brings its own mordant).
 */
class RuntimeClasspathSpec :
    StringSpec({
        val components =
            requireNotNull(
                System.getProperty("ttr.runtimeClasspath"),
            ) { "the build did not pass ttr.runtimeClasspath" }
                .split(',')
                .filter { it.isNotBlank() }

        "the list is the real runtime classpath" {
            components shouldContain "project :packages:kotlin:ttr-metadata"
        }

        "no CLI module and no CLI library on the runtime classpath" {
            components
                .filter {
                    it.endsWith("-cli") ||
                        it.startsWith("com.github.ajalt.") ||
                        it.startsWith("net.java.dev.jna:") ||
                        it == "project :packages:kotlin:ttr-import" ||
                        it.startsWith("org.slf4j:slf4j-nop:")
                }.shouldBeEmpty()
        }
    })
