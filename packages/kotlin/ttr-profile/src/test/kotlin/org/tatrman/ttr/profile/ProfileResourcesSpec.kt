// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.readBytes

class ProfileResourcesSpec :
    FunSpec({
        test("packaged canonical profile and parser schema equal their source bytes") {
            listOf(
                Triple("profile.jar", "profiles/canonical.yaml", "src/main/resources/profiles/canonical.yaml"),
                Triple("parser.jar", "schemas/profile.schema.json", "../../grammar/schemas/profile.schema.json"),
            ).forEach { (jarProperty, resource, source) ->
                JarFile(System.getProperty(jarProperty)).use { jar ->
                    jar.getInputStream(jar.getJarEntry(resource)).readBytes() shouldBe Path.of(source).readBytes()
                }
            }
        }
        test("resolved production dependencies go from profile to yaml and parser without metadata") {
            System.getProperty("profile.runtimeProjects").split(',').toSet() shouldBe
                setOf(
                    ":packages:kotlin:ttr-profile",
                    ":packages:kotlin:ttr-yaml",
                    ":packages:kotlin:ttr-parser",
                )
        }
    })
