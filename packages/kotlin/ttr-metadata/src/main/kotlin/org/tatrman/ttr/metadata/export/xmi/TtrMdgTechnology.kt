// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export.xmi

import java.nio.file.Files
import java.nio.file.Path

/** Element/attribute shape follows the checked-in Sparx Testing technology reference. */
object TtrMdgTechnology {
    fun render(profile: TtrMdgProfile = TtrMdgProfile.load()): ByteArray =
        Xml()
            .apply {
                element("MDG.Technology", "version" to "1.0") {
                    element(
                        "Documentation",
                        "id" to profile.id,
                        "name" to profile.name,
                        "version" to profile.version,
                        "notes" to "TTR metadata projections; no diagrams",
                    )
                    element("UMLProfiles") {
                        element("UMLProfile", "profiletype" to "uml2") {
                            element(
                                "Documentation",
                                "id" to profile.id,
                                "name" to "ttr-mdg",
                                "version" to profile.version,
                                "notes" to "http://tatrman.org/ns/ttr-mdg/1",
                            )
                            element("Content") {
                                element("Stereotypes") {
                                    for (st in profile.stereotypes) {
                                        element("Stereotype", "name" to st.name, "notes" to "") {
                                            element("AppliesTo") { element("Apply", "type" to st.metaclass) }
                                            element("TaggedValues") {
                                                for (tag in st.tags) {
                                                    element(
                                                        "Tag",
                                                        "name" to tag.name,
                                                        "type" to tag.type,
                                                        "description" to "",
                                                        "unit" to "",
                                                        "values" to tag.values,
                                                        "default" to "",
                                                    )
                                                }
                                            }
                                            element("Constraints")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }.bytes()
}

/** Used by Gradle's renderTtrMdg task; the exact same renderer serves library callers. */
object RenderTtrMdg {
    @JvmStatic fun main(args: Array<String>) {
        val path = Path.of(args.single())
        Files.createDirectories(path.parent)
        Files.write(path, TtrMdgTechnology.render())
    }
}
