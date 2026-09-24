// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.model

/**
 * MV (member-vocabulary contracts §1, §3, §5.1) — the carriers that HAVE a member vocabulary, in
 * `qname.dotted()` order. One definition, read by every producer of the fact — the lexicon
 * compiler's `memberVocabulary` facet and Veles' `ListMemberVocabularies` — so "the archive says
 * this ref has a vocabulary" and "the loader was handed a vocabulary for it" cannot drift apart.
 *
 * - every [Attribute] with [SearchHints.indexed]: the vocabulary is the values that attribute
 *   takes over its entity's population, keyed by the attribute ref (its category);
 * - every [DbColumn] with [SearchHints.indexed] that does NOT back an indexed attribute: the
 *   db-only estate's shape. A column that backs an indexed attribute is the attribute's storage,
 *   not a second vocabulary — the attribute is the finer grain (its entity may select a subset of
 *   the table's rows), and listing both would index the same values twice under two owners.
 */
fun Model.memberVocabularyCarriers(): List<ModelObject> {
    val objects = objectByQname().values
    val indexedAttributes = objects.filterIsInstance<Attribute>().filter { it.search.indexed }
    val indexedAttributeRefs = indexedAttributes.map { it.qname }.toSet()
    val backingIndexed =
        mappings
            .asSequence()
            .filterIsInstance<Er2DbAttributeMapping>()
            .filter { it.attribute in indexedAttributeRefs }
            .mapNotNull { (it.target as? AttributeMappingTarget.Column)?.qname }
            .toSet()
    val indexedColumns =
        objects
            .filterIsInstance<DbColumn>()
            .filter { it.search.indexed && it.qname !in backingIndexed }
    return (indexedAttributes + indexedColumns).sortedBy { it.qname.dotted() }
}
