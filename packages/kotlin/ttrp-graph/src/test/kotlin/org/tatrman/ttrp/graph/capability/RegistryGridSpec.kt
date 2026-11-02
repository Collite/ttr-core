// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.capability

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.RunAsRef
import org.tatrman.ttr.metadata.model.World
import org.tatrman.ttr.metadata.model.WorldDeployment
import org.tatrman.ttr.metadata.model.WorldExecutor

/**
 * Grammar 0.22 (D-142): a deployment's target is an executor of its world, resolved through the world's executors —
 * there is no `target: native` pseudo-executor. An executor that happens to be named `native` is resolved like any
 * other (by what it extends); the Server is an executor that extends `native`.
 */
class RegistryGridSpec :
    StringSpec({
        fun executor(
            name: String,
            extends: String,
        ) = WorldExecutor(name, QualifiedName(name = name), extendsRef = extends)

        fun deployment(target: String) =
            WorldDeployment("d", QualifiedName(name = "d"), program = "p", target = target, runAs = RunAsRef.Caller)

        val world =
            World(
                "w",
                QualifiedName(name = "w"),
                executors =
                    listOf(
                        executor("server", "native"),
                        executor("native", "n8n_l1"),
                        executor("bpmn", "camunda-8"),
                    ),
            )

        "every target resolves through the world's executors, an executor named `native` included" {
            RegistryGrid.executorType(world, deployment("server")) shouldBe "native"
            RegistryGrid.executorType(world, deployment("native")) shouldBe "n8n_l1"
            RegistryGrid.executorType(world, deployment("bpmn")) shouldBe "camunda_8"
        }

        "`target: native` in a world without such an executor is an unknown executor, not the Server" {
            val bare = world.copy(executors = listOf(executor("server", "native")))
            shouldThrow<IllegalArgumentException> { RegistryGrid.executorType(bare, deployment("native")) }
                .message shouldBe "unknown executor native"
        }
    })
