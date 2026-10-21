// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics

import org.tatrman.ttr.parser.model.ConceptBindingDef
import org.tatrman.ttr.parser.model.ConceptDef
import org.tatrman.ttr.parser.model.ConceptProperty
import org.tatrman.ttr.parser.model.Definition
import org.tatrman.ttr.parser.model.DimensionDef
import org.tatrman.ttr.parser.model.EntityDef
import org.tatrman.ttr.parser.model.Er2DbAttributeDef
import org.tatrman.ttr.parser.model.Er2DbEntityDef
import org.tatrman.ttr.parser.model.Er2DbRelationDef
import org.tatrman.ttr.parser.model.ProcedureDef
import org.tatrman.ttr.parser.model.SourceLocation
import org.tatrman.ttr.parser.model.TableDef
import org.tatrman.ttr.parser.model.ViewDef
import org.tatrman.ttr.parser.model.WorldDef
import org.tatrman.ttr.semantics.cn.GroundingOrigin
import org.tatrman.ttr.semantics.cn.desugarConcepts

/**
 * Distinguishes explicit `def er2db_*` declarations from symbols synthesized
 * from inline `mapping:` properties (v2.1). Only set on er2db_* kinds.
 */
enum class MappingSource { Explicit, Inline }

/**
 * One symbol-table entry. Mirrors TS `SymbolEntry`
 * (`packages/semantics/src/symbol-table.ts`).
 */
data class SymbolEntry(
    val qname: String,
    val kind: String,
    val name: String,
    val namespace: String,
    val source: SourceLocation,
    val documentUri: String,
    val parent: String?,
    val packageName: String,
    val schemaCode: String,
    val mappingSource: MappingSource? = null,
    /** 0.17 — a concept's stereotype (`kind == "concept"` only); every stereotype shares one namespace. */
    val stereotype: String? = null,
    /** 0.17 — a cn binding's keyword (`kind == "conceptBinding"` only); its key's kind segment. */
    val bindingKind: String? = null,
    /**
     * 0.17 — set on what the cn desugar synthesized (CN-P2 S2.2): a concept a `concept { }` block declares (and
     * its participant labels), or the binding a `grounds:` / `concept { }` stands for. Element references never
     * reach these; an authored symbol of the same key is preferred ([SymbolTable.get]).
     */
    val cnOrigin: GroundingOrigin? = null,
)

/** A qname that resolves to more than one entry. */
data class Duplicate(
    val qname: String,
    val entries: List<SymbolEntry>,
)

/**
 * Per-document symbol builder. Mirrors TS `DocumentSymbolTable`: it constructs
 * qualified names from the document's package / schema / namespace exactly the
 * way `makeQname` / `makeQnameChild` do (including the transitional doubled
 * `cnc.cnc.role.*` shape for stock CNC vocabulary loaded from a `stock://` URI).
 */
internal class DocumentSymbols(
    private val documentUri: String,
    definitions: List<Definition>,
    private val schemaCode: String,
    private val namespace: String,
    private val packageName: String,
) {
    private val entries = LinkedHashMap<String, SymbolEntry>()

    init {
        for (def in definitions) addEntry(def)
        addDesugared(definitions)
    }

    /**
     * 0.17 — the concepts this document's `concept { }` blocks declare and the bindings its `grounds:` and
     * `concept { }` stand for ([desugarConcepts]). A synthesized symbol never replaces one the document
     * declares: an authored concept of the same name is the concept the block grounds. Mirrors TS
     * `DocumentSymbolTable.addDesugared`.
     */
    private fun addDesugared(definitions: List<Definition>) {
        val desugar = desugarConcepts(definitions, packageName)
        for (c in desugar.concepts) {
            val q = makeQname("concept", listOf(c.name))
            if (entries.containsKey(q)) continue
            val entry =
                SymbolEntry(
                    qname = q,
                    kind = "concept",
                    name = c.name,
                    namespace = namespace,
                    // The name in the declaring slot, not the slot (TS parity, review-119 M-5).
                    source = c.declaring.ref.source,
                    documentUri = documentUri,
                    parent = null,
                    packageName = packageName,
                    schemaCode = "cn",
                    stereotype = c.stereotype,
                    cnOrigin = GroundingOrigin.Sugar,
                )
            entries[q] = entry
            if (c.stereotype == "event") addParticipants(entry, c.sugar.body.properties, GroundingOrigin.Sugar)
        }
        for (g in desugar.groundings) {
            val bindingKind = g.bindingKind ?: continue
            if (g.origin == GroundingOrigin.Binding) continue
            val q = makeQname(bindingKind, g.name.split('.'))
            if (entries.containsKey(q)) continue
            entries[q] =
                SymbolEntry(
                    qname = q,
                    kind = "conceptBinding",
                    name = g.name,
                    namespace = namespace,
                    source = g.source,
                    documentUri = documentUri,
                    parent = null,
                    packageName = packageName,
                    schemaCode = "binding",
                    bindingKind = bindingKind,
                    cnOrigin = g.origin,
                )
        }
    }

    /**
     * 0.17 — an event's participants register by label (`Warehouse as fulfilling` → `fulfilling`; unlabelled →
     * the concept name lower-cased, cn contracts §1.1), so a binding's `participants:` keys resolve. Two
     * unlabelled participants of one concept share a key — `cn/involves-unlabelled-duplicate`, not a duplicate.
     */
    private fun addParticipants(
        event: SymbolEntry,
        properties: List<ConceptProperty>,
        cnOrigin: GroundingOrigin? = null,
    ) {
        val involves = properties.firstNotNullOfOrNull { it as? ConceptProperty.Involves } ?: return
        for (participant in involves.participants) {
            val label =
                participant.label ?: participant.ref.parts
                    .last()
                    .lowercase()
            val q = makeQnameChild(event, label)
            if (cnOrigin != null && entries.containsKey(q)) continue
            entries[q] =
                SymbolEntry(
                    qname = q,
                    kind = "participant",
                    name = label,
                    namespace = namespace,
                    source = participant.source,
                    documentUri = documentUri,
                    parent = event.qname,
                    packageName = packageName,
                    schemaCode = event.schemaCode,
                    cnOrigin = cnOrigin,
                )
        }
    }

    fun all(): List<SymbolEntry> = entries.values.toList()

    /**
     * The v4.0 uniform key for a top-level def. The model is derived from the
     * def's own [kind] (D12), the schema slot is db-only ([namespace], else dbo),
     * and the kind segment is always present. Stock cnc no longer doubles (D15).
     */
    private fun makeQname(
        kind: String,
        parts: List<String>,
    ): String = buildCanonicalKey(packageName, namespace, kind, parts, schemaCode)

    private fun makeQnameChild(
        parentEntry: SymbolEntry,
        childName: String,
    ): String = buildCanonicalKey(packageName, namespace, parentEntry.kind, listOf(parentEntry.name, childName))

    private fun addEntry(def: Definition) {
        val kind = kindOf(def)
        val qnameStr = makeQname(keyKindOf(def), listOf(def.name))

        val entry =
            SymbolEntry(
                qname = qnameStr,
                kind = kind,
                name = def.name,
                namespace = namespace,
                source = def.source,
                documentUri = documentUri,
                parent = null,
                packageName = packageName,
                schemaCode = modelForKind(kind, schemaCode),
                mappingSource =
                    if (def is Er2DbEntityDef || def is Er2DbAttributeDef || def is Er2DbRelationDef) {
                        MappingSource.Explicit
                    } else {
                        null
                    },
                stereotype = (def as? ConceptDef)?.stereotype,
                bindingKind = (def as? ConceptBindingDef)?.bindingKind,
            )
        entries[qnameStr] = entry

        when (def) {
            is EntityDef -> def.attributes.forEach { addChild(entry, it.name, "attribute", it.source) }
            // v3.1 MD — dimension attributes register dimension-qualified (`md.dimension.<Dim>.<attr>`). TS parity.
            is DimensionDef -> def.attributes.forEach { addChild(entry, it.name, "attribute", it.source) }
            is ConceptDef -> if (def.stereotype == "event") addParticipants(entry, def.body.properties)
            is TableDef -> def.columns.forEach { addChild(entry, it.name, "column", it.source) }
            is ViewDef -> def.columns.forEach { addChild(entry, it.name, "column", it.source) }
            is ProcedureDef -> def.resultColumns.forEach { addChild(entry, it.name, "column", it.source) }
            is WorldDef -> {
                def.engines.forEach { addChild(entry, it.name, "engine", it.source) }
                def.executors.forEach { addChild(entry, it.name, "executor", it.source) }
                // 0.16 — the named automation members (grants are unnamed: keyed by their triple, no symbol).
                def.identities.forEach { m -> m.name?.let { addChild(entry, it, "identity", m.source) } }
                def.deployments.forEach { m -> m.name?.let { addChild(entry, it, "deployment", m.source) } }
                def.processes.forEach { addChild(entry, it.name, "process", it.source) }
                def.storages.forEach { s ->
                    addChild(entry, s.name, "storage", s.source)
                    s.schemas.forEach { sc ->
                        val q =
                            buildCanonicalKey(packageName, namespace, entry.kind, listOf(entry.name, s.name, sc.name))
                        entries[q] =
                            SymbolEntry(
                                qname = q,
                                kind = "worldSchema",
                                name = sc.name,
                                namespace = namespace,
                                source = sc.source,
                                documentUri = documentUri,
                                parent = makeQnameChild(entry, s.name),
                                packageName = packageName,
                                schemaCode = entry.schemaCode,
                            )
                    }
                }
            }
            else -> {}
        }
    }

    private fun addChild(
        parentEntry: SymbolEntry,
        childName: String,
        kind: String,
        source: SourceLocation,
    ) {
        val q = makeQnameChild(parentEntry, childName)
        entries[q] =
            SymbolEntry(
                qname = q,
                kind = kind,
                name = childName,
                namespace = namespace,
                source = source,
                documentUri = documentUri,
                parent = parentEntry.qname,
                packageName = packageName,
                // Children inherit the parent's effective schema verbatim.
                schemaCode = parentEntry.schemaCode,
            )
    }
}

/**
 * Project-wide symbol table aggregating [DocumentSymbols] across documents.
 * Mirrors TS `ProjectSymbolTable` (`packages/semantics/src/project-symbols.ts`).
 *
 * Conflict semantics match TS: duplicate qnames are NOT rejected — they are
 * kept and surfaced via [duplicates]; [get] returns the first.
 *
 * **Intentionally not ported:** TS `ProjectSymbolTable.upsertSynthesizedSymbols` /
 * `synthesizedByDocument` — the index of er2db_* symbols synthesized from v2.1
 * inline `mapping:` properties. Synthesis lives in `mapping-synthesizer.ts`, which
 * plan P2-1 scopes as modeler-TS-only (edit-synthesizer adjacent; ai-platform does
 * not need it). Consequently this table indexes only explicitly-declared
 * `def er2db_*` symbols; inline-mapping resolution/duplicate-mapping parity is out
 * of scope for the published Kotlin artifact by design.
 */
class SymbolTable {
    private val byDocument = LinkedHashMap<String, DocumentSymbols>()
    private val byQname = LinkedHashMap<String, MutableList<SymbolEntry>>()

    /**
     * Insert (or replace) a document's symbols.
     *
     * @param packageName the document's **declared** package — i.e.
     * `ParseResult.packageName` (the `package <name>` directive), or `""` when
     * absent. It flows directly into qname construction. TS derives this from
     * `ast.packageDecl` and ignores any caller value; the Kotlin API takes a
     * `List<Definition>` (which does not carry the package decl), so the caller
     * MUST supply the declared package — passing an inferred-from-path or
     * otherwise-different value would produce qnames that diverge from TS.
     */
    fun upsertDocument(
        uri: String,
        definitions: List<Definition>,
        schemaCode: String,
        namespace: String,
        packageName: String = "",
    ) {
        if (byDocument.containsKey(uri)) removeDocument(uri)
        val table = DocumentSymbols(uri, definitions, schemaCode, namespace, packageName)
        byDocument[uri] = table
        for (entry in table.all()) {
            byQname.getOrPut(entry.qname) { mutableListOf() }.add(entry)
        }
    }

    fun removeDocument(uri: String) {
        val table = byDocument[uri] ?: return
        for (entry in table.all()) {
            val list = byQname[entry.qname] ?: continue
            list.removeAll { it.documentUri == uri }
            if (list.isEmpty()) byQname.remove(entry.qname)
        }
        byDocument.remove(uri)
    }

    /**
     * The entry a qname reads as. 0.17: an authored symbol over one the cn desugar synthesized ([SymbolEntry.cnOrigin]
     * — a concept a `concept { }` block declares where a cn def declares it too); among synthesized ones the first
     * by document, then source offset. Mirrors TS `ProjectSymbolTable.get`.
     */
    fun get(qname: String): SymbolEntry? {
        val list = byQname[qname] ?: return null
        if (list.size < 2) return list.firstOrNull()
        list.firstOrNull { it.cnOrigin == null }?.let { return it }
        return list.minWith(compareBy<SymbolEntry>({ it.documentUri }, { it.source.offsetStart }))
    }

    fun getAll(qname: String): List<SymbolEntry> = byQname[qname] ?: emptyList()

    fun allQnames(): List<String> = byQname.keys.toList()

    fun all(): List<SymbolEntry> {
        val result = mutableListOf<SymbolEntry>()
        val seen = HashSet<String>()
        for (entries in byQname.values) {
            for (entry in entries) {
                if (seen.add(entry.qname)) result += entry
            }
        }
        return result
    }

    fun findByName(name: String): List<SymbolEntry> = all().filter { it.name == name }

    fun duplicates(): List<Duplicate> =
        byQname.entries
            .filter { it.value.size > 1 }
            .map { Duplicate(it.key, it.value.toList()) }

    fun getByPackage(packageName: String): List<SymbolEntry> = all().filter { it.packageName == packageName }

    fun getBySuffix(suffix: String): List<SymbolEntry> {
        val result = mutableListOf<SymbolEntry>()
        for (entries in byQname.values) {
            for (entry in entries) {
                if (entry.qname.endsWith(".$suffix") || entry.qname == suffix) result += entry
            }
        }
        return result
    }

    fun listPackages(): List<String> = all().map { it.packageName }.toSortedSet().toList()

    // ----- contract §4.2 aliases -----

    fun lookup(qname: Qname): SymbolEntry? = get(qname.value)

    fun findUnderPackage(pkg: Qname): List<SymbolEntry> = getByPackage(pkg.value)

    fun findByLastSegment(last: String): List<SymbolEntry> = getBySuffix(last)
}
