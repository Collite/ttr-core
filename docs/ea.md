# Enterprise Architect: TTR metadata projections

Generate the MDG Technology:

```sh
./gradlew :packages:kotlin:ttr-metadata:renderTtrMdg
```

The file is `packages/kotlin/ttr-metadata/build/distributions/ttr-mdg.xml`.
Grammar publish runs also attach it as `ttr-mdg-<version>` in workflow Artifacts.

1. In EA, use **Specialize → Technologies → Import MDG Technology** and choose the XML.
   Enable **Tatrman TTR** in Manage Technologies; verify entity, concept, metric and the other stereotypes.
2. Use **Publish → Import XMI** to import a projection into an empty package. For the local
   acceptance fixture, use `packages/kotlin/ttr-metadata/src/test/resources/xm-goldens/xmi/xm-world.xmi`.
3. Check cn, er, md and db.retail packages, class stereotypes, qname and other tagged values,
   typed attributes, two sale_buyer associations, their 0..* / 1 multiplicities, labelled
   involves associations, generalization and grounds dependencies. No diagrams are generated.
4. Record screenshots and plain-text import findings in the XM tracker in `project/`.
   The captured EA export and live import have not yet been verified; retain them as acceptance
   checks and repeat after any required fixes.

The library facade accepts `ExportRequest(format = "xmi", profile = "ttr-mdg/1")`;
that profile is the default. It returns one UTF-8 XMI 2.1 document, also for multiple
models. Explicit lexicon export returns `L-XMI-2`; synonyms on model elements flatten
to `lang:form;…`. `ea-erd/1` is reserved for P5. CLI/HTTP entry points follow in S1.5.
Descriptions use English; expression bodies, full lexicon data and layouts are outside
this projection. Out-of-scope targets are minimal placeholders in External; they retain
stable ids without importing their bodies.

Ids use RFC 4122 UUIDv5 with the URL namespace and the object's IRI, formatted as
`EAID_<UPPERCASE_UUID_WITH_UNDERSCORES>`. Authored concept IRIs win. Packages, primitive
types, associations ends, stereotype applications and unnamed semantic edges have
stable synthetic IRIs; changing a label on a named node preserves its identity.
The `Types` package holds each distinct primitive type once. Standalone properties
are owned by an auxiliary `Attributes` class. Relation-type concepts retain
`cnKind=relation_type`; a foreign key uses the relation stereotype and 0..* / 1 defaults.

Reference MDG shape: [Sparx Testing technology](https://sparxsystems.org/downloads/mdg_technology/testing-technology.xml),
linked by [Sparx MDG downloads](https://sparxsystems.com/resources/mdg_tech/).
The checked-in original and its attribution are test-only; no Sparx content ships in the metadata jar.
