// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.camunda

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

internal fun validateAuthoringBpmn(xml: String) {
    val factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file")
    val sources =
        listOf(
            "BPMN20.xsd",
            "camunda-supported.xsd",
            "zeebe-supported.xsd",
            "ttr-export.xsd",
            "modeler-supported.xsd",
        ).map {
            StreamSource(requireNotNull(TemplatesDmnSpec::class.java.getResource("/schema/$it")).toExternalForm())
        }
    val schema = factory.newSchema(sources.toTypedArray())
    schema.newValidator().validate(StreamSource(StringReader(xml)))
}
