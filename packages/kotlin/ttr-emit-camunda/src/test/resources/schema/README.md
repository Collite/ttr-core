# Offline BPMN validation resources

`BPMN20.xsd`, `Semantic.xsd`, `BPMNDI.xsd`, `DI.xsd` and `DC.xsd` are copied, with indentation/trailing whitespace normalized, from
[Camunda 8.8.0's BPMN model resources](https://github.com/camunda/camunda/tree/8.8.0/zeebe/bpmn-model/src/main/resources).
They are the standard OMG BPMN 2.0/DI schema set, also used by the C7 model API.

Neither upstream resource directory publishes extension XSDs. `camunda-supported.xsd` and
`zeebe-supported.xsd` are locally authored, strict schemas for this exporter's extension subset. They are
not represented as official Camunda schemas or a substitute for engine validation. Their shapes follow the
[C7 connector model](https://github.com/camunda/camunda-bpm-platform/tree/7.24.0/model-api/bpmn-model/src/main/java/org/camunda/bpm/model/bpmn/instance/camunda),
[Zeebe model](https://github.com/camunda/camunda/tree/8.8.0/zeebe/bpmn-model/src/main/java/io/camunda/zeebe/model/bpmn/instance/zeebe) and
[REST connector template](https://github.com/camunda/connectors/blob/main/connectors/http/rest/element-templates/http-json-connector.json).
`ttr-export.xsd` defines the exporter's structural contract extension. Tests load all four namespaces so lax
extension validation in the standard schema cannot silently skip these supported shapes.

The Modeler opening is a separate, pending manual acceptance. No DFP draft is used as a schema or fixture.
