# TTR RDF vocabulary

Namespace: `https://tatrman.org/ns/ttr#` (`ttr:`). This document defines the vocabulary
used by `cn.ttl`. Publishing it at the namespace URL is a separate docs-site step.
The assertions describe the conceptual model; OWL and SKOS terms are aliases for
consumers. They do not replace TTR semantics or evaluate TTR-PL expressions.

## Profiles and identity

`format=ttl` supports `profile=both` (default), `owl`, and `skos`. `model=cn` and
`model=world` emit the same conceptual projection: selected cn concepts, schemes,
areas, and grounding target IRIs. Physical, ER and MD definitions are not emitted.
Other model selectors return cannot-carry `L-TTL-1`. Expression bodies remain opaque
(`L-TTL-2`). Scope is applied before projection; references to concepts outside the
scope retain their authored IRI without emitting that concept's definition.

Every profile carries the asserted `ttr:` statements and one `owl:Ontology` at
`urn:ttr:<world>`, with `dct:identifier` (snapshot) and `owl:versionInfo` (grammar).
The `owl` profile adds OWL/RDFS aliases and has no SKOS predicates or concept
schemes. The `skos` profile adds SKOS aliases and schemes, omitting OWL concept and
object-property aliases, including `owl:sameAs`. The ontology header is retained.
`both` emits both sets. OpenMetadata initially consumes `owl`; importing the punned
`both` projection is a later live conformance check.

A concept's default IRI is `urn:ttr:<world>:<kinded-qname>`. An authored `iri:`
overrides it; OWL-capable profiles assert `owl:sameAs` the default urn. Sugar concepts
have the same identity and representation as standalone concepts. A relation type
is represented by a concept and, in OWL-capable profiles, a property named
`ttr:rel_<name>`. Each labelled involvement has `ttr:involves_<label>` as an additional
OWL object property. These names are vocabulary IRIs, shared across the world;
repeated labels accumulate domain/range assertions.

## Classes

Here “concept” means any of the six conceptual stereotypes below. The domain and
range descriptions document the emitted statements; this file does not add RDFS
inference rules to exported worlds.

| Class | Domain / instances | Comment |
|---|---|---|
| `ttr:Kind` | `def kind` | A kind, including abstract/open kinds. |
| `ttr:Role` | `def role` | A conceptual role. |
| `ttr:Phase` | `def phase` | A conceptual phase; its definition is opaque text. |
| `ttr:Quality` | `def quality` | A conceptual quality. |
| `ttr:Event` | `def event` | An event, with optional extent and period paths. |
| `ttr:RelationType` | `def relation_type` | An open-tier relation type concept. |
| `ttr:Participation` | Inline blank nodes | One event/concept participant, with optional role label. |
| `ttr:Area` | Selected areas | A grouping with members addressed by IRI. |

## Properties

| Property | Domain | Range | Comment |
|---|---|---|---|
| `ttr:tier` | Concept | String `core` or `open` | Abstract kinds and relation types are open-tier. |
| `ttr:abstract` | Concept | Boolean | Emitted as `true` when explicitly abstract. |
| `ttr:is_a` | Concept | Concept IRI | Asserted specialization. |
| `ttr:involves` | Concept | `ttr:Participation` | Participant structure, retained in all profiles. |
| `ttr:label` | `ttr:Participation` | String | Authored involvement label; absent for unlabelled participants. |
| `ttr:concept` | `ttr:Participation` | Concept IRI | Participating concept. |
| `ttr:involves_<label>` | Involving concept | Participating concept | OWL-only derived property with explicit domain/range. |
| `ttr:extent` | Concept | String | Authored extent, e.g. `instantaneous` or `durative`. |
| `ttr:basis` | Concept | String | Authored phase basis. |
| `ttr:periodAt` | Concept | String | Authored instant path. |
| `ttr:periodStart` | Concept | String | Authored interval start path. |
| `ttr:periodEnd` | Concept | String | Authored interval end path. |
| `ttr:end_open` | Concept | String or boolean | Authored period endpoint status, e.g. `open` or `unknown`. |
| `ttr:imprecision` | Concept | String or boolean | Authored imprecision value. |
| `ttr:definition` | Concept | String | Retained expression text, without source spans. |
| `ttr:definitionLanguage` | Concept | String `ttr-pl` | Language of the opaque definition. |
| `ttr:valueSpace` | Concept | IRI | ER target of a bound vocabulary; otherwise the referenced concept IRI. |
| `ttr:memberRestriction` | Concept | String | Opaque `members:` expression. |
| `ttr:groundsTo` | Concept | MD/ER/DB IRI | Target only; no target resource definition is emitted. |
| `ttr:groundingLayer` | Concept | String `md`, `er`, or `db` | Layers represented by that concept's groundings. |
| `ttr:example` | Concept | Language-tagged string | Inline or canonical lexicon example in its file locale. |
| `ttr:rel_<name>` | Concept | Concept IRI | Derived OWL object property for an open relation type. |
| `ttr:member` | `ttr:Area` | IRI | Package or definition member. |

## Alias table

| TTR assertion or source | OWL/RDFS (`owl`, `both`) | SKOS (`skos`, `both`) |
|---|---|---|
| Concept stereotype | `a owl:Class` | `a skos:Concept` |
| Package containing a concept | — | `a skos:ConceptScheme`; concept `skos:inScheme` |
| `ttr:is_a` | `rdfs:subClassOf` | `skos:broader` |
| Labelled `ttr:involves` | `ttr:involves_<label> a owl:ObjectProperty`; `rdfs:domain/range` | — |
| Authored `iri:` | `owl:sameAs` the generated urn | — |
| Bound vocabulary value space | — | `skos:inScheme` the ER vocabulary target |
| DB grounding | — | `skos:exactMatch` the target IRI |
| Display label, or name fallback | `rdfs:label` | `skos:prefLabel` |
| Every lexicon term form | — | `skos:altLabel` |
| Localized description | `rdfs:comment` | `skos:definition` |
| Open-tier relation type | `ttr:rel_<name> a owl:ObjectProperty`; `rdfs:label` | Concept representation |

All available languages travel. Canonical lexicon entries use their file locale;
inline lexicon defaults to English when no locale is retained. Grounding target
IRIs and participation targets can refer to external or scoped-out definitions.

## Serialization

The serializer uses no RDF runtime library. Prefixes appear in this order: `ttr`,
`rdf`, `rdfs`, `owl`, `skos`, `xsd`, `dct`, `w` (the world's urn namespace). Subjects
sort bytewise by full IRI, predicates follow the contract table order, and objects
sort by term and lexical value. Blank participations are inline property lists.
Single-line strings use escaped double quotes; only multiline strings use triple
quotes. Boolean literals use `true`/`false`; languages use RDF tags. Bytes are UTF-8
with LF separators and a final LF, without timestamps or comments.

Jena is test-only (Turtle/SPARQL; Protobuf transport excluded). The published
metadata runtime bans `org.apache.jena`. Handwritten and generated goldens, Jena
parse/triple-count checks, shape queries, and shuffled-input comparisons guard the
contract. Live catalog behavior is validated in the later OM conformance stage.
