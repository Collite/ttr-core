// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * CN-P0 S0.4 T1 — `ttr-concept` has ZERO LLM (architecture §7; the RG-P5 `NoLlmDependency` pattern). Every function
 * here sits below the determinism frontier (P-3 v2): it validates, lowers and renders frames an agent proposed, and it
 * never calls a model, a gateway or a server.
 *
 * This is the RUNTIME backstop. The primary guard is the `verifyNoLlmDependency` Gradle task (wired into `check`),
 * which fails the build when the module's runtimeClasspath resolves an LLM, gateway, kantheon or HTTP-client artifact.
 * Here the same is asserted on the test classpath, at class-load time and by jar name.
 */
class NoLlmDependencySpec :
    StringSpec({

        val forbiddenClasses =
            listOf(
                "org.tatrman.llm.v1.LlmGatewayServiceGrpcKt",
                "org.tatrman.llm.v1.LlmGatewayServiceGrpc",
                "org.tatrman.llm.client.LlmGatewayClient",
                "com.openai.client.OpenAIClient",
                "com.theokanning.openai.service.OpenAiService",
                "com.anthropic.client.AnthropicClient",
                "dev.langchain4j.model.chat.ChatLanguageModel",
                // a lower never calls a server — no HTTP client of any kind
                "io.ktor.client.HttpClient",
            )

        val forbiddenJarFragments =
            listOf(
                "llm-client",
                "llm-gateway",
                "openai",
                "anthropic",
                "langchain",
                "theokanning",
                "kantheon",
                "ktor-client",
            )

        "no LLM, gateway or HTTP-client class is loadable" {
            val loadable =
                forbiddenClasses.filter { cls ->
                    runCatching { Class.forName(cls, false, this::class.java.classLoader) }.isSuccess
                }
            loadable shouldBe emptyList()
        }

        "no LLM, gateway, kantheon or HTTP-client jar is on the classpath" {
            val entries = System.getProperty("java.class.path").orEmpty().split(java.io.File.pathSeparator)
            val hits =
                entries.filter { entry ->
                    val name = entry.substringAfterLast(java.io.File.separatorChar).lowercase()
                    forbiddenJarFragments.any { name.contains(it) }
                }
            hits shouldBe emptyList()
        }
    })
