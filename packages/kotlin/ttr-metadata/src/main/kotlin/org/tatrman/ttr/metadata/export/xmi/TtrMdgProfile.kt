// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export.xmi

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

data class MdgTag(
    val name: String,
    val type: String,
    val values: String = "",
)

data class MdgStereotype(
    val name: String,
    val metaclass: String,
    val tags: List<MdgTag>,
)

data class TtrMdgProfile(
    val id: String,
    val name: String,
    val version: String,
    val stereotypes: List<MdgStereotype>,
) {
    companion object {
        fun load(): TtrMdgProfile =
            requireNotNull(
                TtrMdgProfile::class.java.getResourceAsStream("/profiles/ttr-mdg/1/profile.yaml"),
            ).use { input ->
                val data =
                    Yaml(
                        SafeConstructor(LoaderOptions().apply { isAllowDuplicateKeys = false }),
                    ).load<Map<String, Any>>(input)

                fun Map<*, *>.string(key: String) = get(key) as String
                val common =
                    (data["commonTags"] as List<*>).map { t ->
                        val tag = t as Map<*, *>
                        MdgTag(tag.string("name"), tag.string("type"))
                    }
                val stereotypes =
                    (data["stereotypes"] as List<*>).map { s ->
                        val st = s as Map<*, *>
                        val tags =
                            (st["tags"] as? List<*>).orEmpty().map { t ->
                                val tag = t as Map<*, *>
                                MdgTag(
                                    tag.string("name"),
                                    tag.string("type"),
                                    tag["values"] as? String ?: "",
                                )
                            }
                        MdgStereotype(st.string("name"), st.string("metaclass"), common + tags)
                    }
                require(stereotypes.map { it.name }.distinct().size == stereotypes.size)
                require(
                    stereotypes.all { st ->
                        st.tags
                            .map { it.name }
                            .distinct()
                            .size == st.tags.size
                    },
                )
                TtrMdgProfile(
                    data.getValue("id") as String,
                    data.getValue("name") as String,
                    data.getValue("version") as String,
                    stereotypes,
                )
            }
    }
}
