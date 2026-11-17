// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldStartWith

/**
 * AU-export S1.5.9 review-340 H1: ttrp-lsp is an application, so it binds its own SLF4J provider. A library never does.
 * Before S1.5.9 it got slf4j-nop only through `ttrp-frontend -> ttr-metadata-cli`, and without a provider SLF4J prints
 * "No SLF4J providers were found" on stderr. The build passes the published `runtimeClasspath`'s components in
 * `ttr.runtimeClasspath` (gradle/runtime-classpath-components.gradle.kts).
 */
class Slf4jProviderSpec :
    StringSpec({
        "exactly one SLF4J provider on the runtime classpath, and it is slf4j-nop" {
            val components =
                requireNotNull(
                    System.getProperty("ttr.runtimeClasspath"),
                ) { "the build did not pass ttr.runtimeClasspath" }.split(',')
            val providers =
                components.filter { component ->
                    PROVIDERS.any { component.startsWith(it) } || component.startsWith("org.apache.logging.log4j:log4j-slf4j")
                }
            providers shouldHaveSize 1
            providers.single() shouldStartWith "org.slf4j:slf4j-nop:"
        }
    }) {
    private companion object {
        val PROVIDERS =
            listOf(
                "org.slf4j:slf4j-nop:",
                "org.slf4j:slf4j-simple:",
                "org.slf4j:slf4j-jdk14:",
                "org.slf4j:slf4j-reload4j:",
                "ch.qos.logback:logback-classic:",
            )
    }
}
