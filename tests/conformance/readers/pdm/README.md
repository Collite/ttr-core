# PowerDesigner 16 reader fixtures — MW-P5 S5.4

All model names, descriptions and UUIDs are invented. No client data or PD extension file is included.
`model/` is the complete expected output of one directory import (generator `0.0.1-LOCAL`): cn/er/db documents,
cn2er and er2db binding documents, sidecars and report. D-MW-109 defers multidimensional XML to S5.4b.

```sh
./gradlew :packages:kotlin:ttr-import:test :packages:kotlin:ttr-metadata-cli:test
# Deliberate expectation refresh after inspecting a changed projection:
TTR_IMPORT_GOLDEN=1 ./gradlew :packages:kotlin:ttr-import:test
# Installed CLI:
ttr-metadata import-pdm --from tests/conformance/readers/pdm/sources --profile pdm/16 --out /tmp/pdm-model
```

The XML uses PowerDesigner's documented `Model`/`o:RootObject`/`o:Model`, object `Id`/`Ref`, `a:` scalar properties,
`c:` collections and `PowerDesigner` processing instruction. The signature distinguishes CDM, LDM and PDM; extensions
alone do not select a kind. PD 15 (the separate `pd15.pdm`) is refused with `xm/pdm-version` on line 2.

Schema references used when authoring these fixtures:

- [SAP: the public metamodel](https://infocenter.sybase.com/help/topic/com.sybase.infocenter.dc38628.1600/doc/html/rad1232022065342.html)
  and its model file format documentation.
- [SAP: relationship properties](https://help.sap.com/docs/SAP_POWERDESIGNER/856348b84a7c479489d5172a630f014d/c7c3dc1c6e1b1014a4a4db61b5ac0ca3.html)
  (Object1/Object2, the two role cardinalities).
- The structure of the exported SAP example models `project.cdm`/`project.pdm` in the public
  [PowerDesigner_Docs examples](https://github.com/DeytaLevel39/PowerDesigner_Docs/tree/master/src/testpdm): an Inheritance
  names its ParentEntity, an InheritanceLink connects it to a child via Object1/Object2; ReferenceJoin Object1 is the
  parent column and Object2 the child; GenerationOrigins names the origin model through a Shortcut TargetID, and an
  object's History ORG UUIDs identify origin objects. No example content was copied into these fixtures.

The front-end resolves only the files supplied to this run. Each Model/Package Code segment is lower-cased with
`Locale.ROOT`; distinct raw paths that collide after lower-casing are refused with `ys/package-invalid` naming both.
Definition Codes stay verbatim. `$ref` retains the local XML id, and `@path` carries the target's model/package and
Code ancestry as readable provenance. All links match the resolved `ObjectID` through `by: sourceName` (control room
§66, amending R391-B); repeated table/entity/column Codes in different packages or models are therefore distinct.
`ObjectID` is also the stable sidecar identity. Generation origins join exact ObjectIDs, with no Code-based guess.
An origin not present among the model's supplied GenerationOrigins produces an ignored generation record.
Shortcuts follow `TargetID` only among supplied models; an FK or relationship through an unavailable shortcut is
skipped with an ignored entry naming the missing target. Incomplete ReferenceJoins are skipped as whole pairs and
reported at the join. No external model is opened implicitly. Directory inputs keep `/`-separated paths relative
to `--from` in diagnostics; archive copies that repeat ObjectIDs are ambiguous and the error names both paths.

The profile's type mapping is data: SQL spellings and PD logical abbreviations (`I`, `VA80`, `N12,2`, etc.) map through
`values`, after lexical decomposition of the type name/length/precision. Unlisted type names fall back to text. TTR-M has no binary primitive, so binary spellings also map to text.
Diagrams and display preferences have an explicit ignore reason. Other PD metadata not selected by the generic
profile stays visible in `ignored[]` and aggregated warnings (`unknown: ignore`). CDM attributes and participants are
not asserted as conceptual semantics: entities are abstract kinds and relationships are open-tier relation types.
