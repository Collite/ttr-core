// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import org.tatrman.ttr.parser.model.ConceptDef
import org.tatrman.ttr.semantics.cn.conceptSugarOf
import org.tatrman.ttr.semantics.cn.sugarStereotypeKey

/** Shared canonical reference resolution for tiers 0 and 1, including implied sugar concepts. */
internal class DefinitionResolver(
    source: List<DefinedObject>,
) {
    private val entries =
        source +
            source
                .mapNotNull { host ->
                    val sugar = conceptSugarOf(host.def) ?: return@mapNotNull null
                    val key = sugarStereotypeKey(sugar.body) ?: return@mapNotNull null
                    val name =
                        sugar.body
                            .ref(key)
                            ?.parts
                            ?.lastOrNull() ?: return@mapNotNull null
                    val id = "cn.$key.$name"
                    if (source.any {
                            it.qname == id
                        }
                    ) {
                        null
                    } else {
                        host.copy(
                            qname = id,
                            model = "cn",
                            kind = key,
                            def = ConceptDef(key, name, sugar.source, sugar.body),
                            parentQname = null,
                        )
                    }
                }.distinctBy { it.qname }

    fun resolve(
        raw: String,
        context: DefinedObject,
        defaultModel: String,
        kind: String? = null,
    ): String =
        resolveKnown(raw, context, defaultModel, kind) ?: run {
            val layer = raw.split('.').firstOrNull { it in setOf("cn", "md", "er", "db") } ?: defaultModel
            when {
                raw.startsWith("$layer.") -> raw
                layer == "cn" -> "cn.$raw"
                kind != null -> "$layer.$kind.$raw"
                else -> "$layer.$raw"
            }
        }

    fun resolveKnown(
        raw: String,
        context: DefinedObject,
        defaultModel: String,
        kind: String? = null,
    ): String? {
        entries.firstOrNull { it.qname == raw }?.let { return it.qname }
        val layer = raw.split('.').firstOrNull { it in setOf("cn", "md", "er", "db") } ?: defaultModel
        val contextual = listOfNotNull("${context.qname}.$raw", context.parentQname?.let { "$it.$raw" })
        entries.firstOrNull { it.qname in contextual && it.model == layer && (kind == null || it.kind == kind) }?.let {
            return it.qname
        }
        val candidates = entries.filter { it.model == layer && (kind == null || it.kind == kind) && raw in aliases(it) }
        val local = candidates.filter { it.pkg == context.pkg }
        return (local.singleOrNull() ?: candidates.singleOrNull())?.qname
    }

    private fun aliases(e: DefinedObject): Set<String> {
        val name =
            when {
                e.parentQname != null -> e.qname.substringAfter("${e.model}.").substringAfter('.')
                else -> e.def.name
            }
        val short = if (e.parentQname == null) e.def.name else name
        return setOf(
            e.qname,
            short,
            "${e.model}.$short",
            "${e.pkg}.$short",
            "${e.model}.${e.pkg}.$short",
            "${e.pkg}.${e.qname}",
        )
    }
}
