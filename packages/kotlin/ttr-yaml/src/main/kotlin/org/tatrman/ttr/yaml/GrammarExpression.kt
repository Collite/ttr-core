// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import kotlinx.serialization.json.*

internal object GrammarExpression {
    fun parse(source: String): JsonElement {
        val tokens = Regex("[A-Za-z_]\\w*|[()|?*+]").findAll(source).map { it.value }.toList()
        var i = 0
        lateinit var alt: () -> JsonElement

        fun sequence(): JsonElement {
            val nodes = mutableListOf<JsonElement>()
            while (i < tokens.size && tokens[i] !in setOf(")", "|")) {
                var node =
                    if (tokens[i] ==
                        "("
                    ) {
                        i++
                        val n = alt()
                        require(tokens[i++] == ")")
                        n
                    } else {
                        obj("symbol" to text(tokens[i++]))
                    }
                if (i < tokens.size &&
                    tokens[i] in setOf("?", "*", "+")
                ) {
                    node = obj("repeat" to text(tokens[i++]), "node" to node)
                }
                nodes += node
            }
            return if (nodes.size == 1) nodes.single() else obj("seq" to JsonArray(nodes))
        }
        alt = {
            val nodes = mutableListOf(sequence())
            while (i < tokens.size && tokens[i] == "|") {
                i++
                nodes += sequence()
            }
            if (nodes.size == 1) nodes.single() else obj("alt" to JsonArray(nodes))
        }
        return alt().also { require(i == tokens.size) }
    }
}
