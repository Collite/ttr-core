// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import org.tatrman.ttr.profile.ProfileException
import org.tatrman.ttr.profile.ProfileSpan
import java.io.ByteArrayInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/** Shared XML boundary for the XMI and PD readers: no external reads, and positive authored coordinates. */
internal object XmlInput {
    fun reader(
        bytes: ByteArray,
        label: String,
    ): XMLStreamReader =
        try {
            XMLInputFactory
                .newFactory()
                .apply {
                    setProperty(XMLInputFactory.SUPPORT_DTD, false)
                    setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
                    setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
                    setProperty(XMLInputFactory.IS_COALESCING, true)
                }.createXMLStreamReader(ByteArrayInputStream(bytes))
        } catch (e: XMLStreamException) {
            fail(label, e.location?.lineNumber ?: 1, e.location?.columnNumber ?: 1, e.message ?: "Invalid XML")
        }

    fun span(
        label: String,
        line: Int,
        column: Int,
    ): ProfileSpan {
        val l = line.coerceAtLeast(1)
        val c = column.coerceAtLeast(1)
        return ProfileSpan(label, l, c, l, c, "")
    }

    fun fail(
        label: String,
        line: Int,
        column: Int,
        message: String,
    ): Nothing = throw ProfileException("ys/profile-invalid", span(label, line, column), message)
}
