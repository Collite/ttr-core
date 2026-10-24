// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.doc.annotations

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.polymorphic
import org.tatrman.ttrp.doc.DocSidecar

/**
 * AU annotation vocabulary. AGEL doc_id/doc_version map to the record's document/documentSha;
 * spans, producer and derived_from map to span, producer and derivedFrom. Payload fields are typed AU names
 * (objectRef, container, valueType, role), never generic AGEL labels. The Platform resolver owns proposals;
 * this front-end consumes only human Verdicts over Links. Each record belongs to one immutable document SHA.
 */
@Serializable
sealed interface Annotation {
    val id: String
    val span: List<Int>
    val producer: String
    val derivedFrom: List<String>
}

@Serializable enum class Escape {
    @SerialName("builtin")
    BUILTIN,

    @SerialName("foreign")
    FOREIGN,

    @SerialName("call")
    CALL,

    @SerialName("turn")
    TURN,

    @SerialName("restatement")
    RESTATEMENT,
}

@Serializable
@SerialName("Section")
data class Section(
    override val id: String,
    override val span: List<Int>,
    val title: String,
    override val producer: String = "resolver",
    override val derivedFrom: List<String> = emptyList(),
) : Annotation

@Serializable
@SerialName("Sentence")
data class Sentence(
    override val id: String,
    override val span: List<Int>,
    val text: String,
    override val producer: String = "resolver",
    override val derivedFrom: List<String> = emptyList(),
) : Annotation

@Serializable
@SerialName("Mention")
data class Mention(
    override val id: String,
    override val span: List<Int>,
    val objectRef: String,
    val lexiconMatch: String,
    val confidence: Double,
    override val producer: String = "resolver",
    override val derivedFrom: List<String> = emptyList(),
) : Annotation

@Serializable
@SerialName("Coref")
data class Coref(
    override val id: String,
    override val span: List<Int>,
    val mention: String,
    override val producer: String = "resolver",
    override val derivedFrom: List<String> = emptyList(),
) : Annotation

@Serializable
@SerialName("Role")
data class Role(
    override val id: String,
    override val span: List<Int>,
    val role: String,
    val container: String? = null,
    override val producer: String = "resolver",
    override val derivedFrom: List<String> = emptyList(),
) : Annotation

@Serializable
@SerialName("Parameter")
data class Parameter(
    override val id: String,
    override val span: List<Int>,
    val name: String,
    val value: String,
    val valueType: String,
    val unit: String? = null,
    override val producer: String = "resolver",
    override val derivedFrom: List<String> = emptyList(),
) : Annotation

@Serializable
@SerialName("Person")
data class Person(
    override val id: String,
    override val span: List<Int>,
    val role: String,
    override val producer: String = "resolver",
    override val derivedFrom: List<String> = emptyList(),
) : Annotation

@Serializable
@SerialName("Block")
data class Block(
    override val id: String,
    override val span: List<Int>,
    val nodes: List<String>,
    override val producer: String = "resolver",
    override val derivedFrom: List<String> = emptyList(),
) : Annotation

@Serializable
@SerialName("Link")
data class Link(
    override val id: String,
    override val span: List<Int>,
    val block: String,
    val sentences: List<String>,
    override val producer: String = "resolver",
    override val derivedFrom: List<String> = emptyList(),
) : Annotation

@Serializable
@SerialName("Inexpressible")
data class Inexpressible(
    override val id: String,
    override val span: List<Int>,
    val why: String,
    val escapes: List<Escape>,
    override val producer: String = "resolver",
    override val derivedFrom: List<String> = emptyList(),
) : Annotation

@Serializable
@SerialName("Verdict")
data class Verdict(
    override val id: String,
    override val span: List<Int>,
    val target: String,
    val accepted: Boolean,
    override val producer: String = "resolver",
    override val derivedFrom: List<String> = emptyList(),
) : Annotation

@Serializable
@SerialName("_Unknown")
private data class UnknownAnnotation(
    override val id: String = "",
    override val span: List<Int> = emptyList(),
    override val producer: String = "resolver",
    override val derivedFrom: List<String> = emptyList(),
    @kotlinx.serialization.Transient val ignoredType: String = "_Unknown",
) : Annotation

data class AnnotationReadResult(
    val record: AnnotationRecord,
    val warnings: List<String>,
)

@Serializable
data class AnnotationRecord(
    val document: String,
    val documentSha: String,
    val annotations: List<Annotation>,
) {
    fun toJson(): String = Json.encodeToString(this)

    fun applyVerdicts(sidecar: DocSidecar): DocSidecar {
        require(sidecar.documentSha == documentSha) { "annotation documentSha does not match the document" }
        val byId = annotations.associateBy { it.id }
        val rejected =
            annotations
                .filterIsInstance<Verdict>()
                .filter { it.producer.startsWith("human@") }
                .groupBy { it.target }
                .mapValues { it.value.last() }
                .values
                .filterNot { it.accepted }
        val removals = mutableMapOf<List<Int>, MutableSet<String>>()
        rejected.forEach { verdict ->
            val link = byId[verdict.target] as? Link ?: return@forEach
            val block = byId[link.block] as? Block ?: return@forEach
            link.sentences.forEach { id ->
                val sentence = byId[id] as? Sentence ?: return@forEach
                removals.getOrPut(sentence.span) { mutableSetOf() }.addAll(block.nodes)
            }
        }
        return sidecar.copy(
            sentences = sidecar.sentences.map { it.copy(nodes = it.nodes - removals[it.span].orEmpty()) },
        )
    }

    companion object {
        fun load(
            text: String,
            documentSha: String,
        ): AnnotationRecord = read(text, documentSha).record

        fun read(
            text: String,
            documentSha: String,
        ): AnnotationReadResult {
            val json =
                Json {
                    ignoreUnknownKeys = true
                    coerceInputValues = true
                    serializersModule =
                        kotlinx.serialization.modules.SerializersModule {
                            polymorphic(Annotation::class) {
                                defaultDeserializer { name ->
                                    object : kotlinx.serialization.DeserializationStrategy<UnknownAnnotation> {
                                        override val descriptor = UnknownAnnotation.serializer().descriptor

                                        override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder) =
                                            UnknownAnnotation.serializer().deserialize(decoder).copy(
                                                ignoredType =
                                                    name ?: "<missing>",
                                            )
                                    }
                                }
                            }
                        }
                }
            val decoded = json.decodeFromString<AnnotationRecord>(text)
            val unknown = decoded.annotations.filterIsInstance<UnknownAnnotation>()
            val record = decoded.copy(annotations = decoded.annotations.filterNot { it is UnknownAnnotation })
            require(record.documentSha == documentSha) { "annotation documentSha does not match the document" }
            require(
                record.annotations
                    .map { it.id }
                    .distinct()
                    .size == record.annotations.size,
            ) { "duplicate annotation id" }
            record.annotations.forEach {
                require(it.span.size == 2 && it.span[0] >= 0 && it.span[1] >= it.span[0]) { "invalid annotation span" }
            }
            val byId = decoded.annotations.associateBy { it.id }
            record.annotations.forEach { a ->
                when (a) {
                    is Role ->
                        require(
                            a.role in
                                setOf("candidates", "context", "decision", "action", "parameter", "program", "people"),
                        ) { "invalid AU role" }
                    is Mention -> require(a.confidence in 0.0..1.0) { "invalid mention confidence" }
                    is Coref ->
                        require(byId[a.mention] is Mention || byId[a.mention] is UnknownAnnotation) {
                            "coreference must name a mention"
                        }
                    is Link ->
                        require(
                            (byId[a.block] is Block || byId[a.block] is UnknownAnnotation) &&
                                a.sentences.all {
                                    (byId[it] is Sentence || byId[it] is UnknownAnnotation)
                                },
                        ) { "link must name a block and sentences" }
                    is Verdict -> require(a.target in byId) { "verdict must name an annotation" }
                    else -> Unit
                }
            }
            return AnnotationReadResult(record, unknown.map { "unknown annotation type \"${it.ignoredType}\" ignored" })
        }
    }
}
