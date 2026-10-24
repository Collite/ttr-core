// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export.xmi

import java.io.StringWriter
import javax.xml.stream.XMLOutputFactory

/** JDK StAX escaping, fixed call-site attribute order, two-space indentation and LF. */
internal class Xml {
    private val text = StringWriter()
    private val writer = XMLOutputFactory.newFactory().createXMLStreamWriter(text)
    private var depth = 0
    private val ids = mutableSetOf<String>()

    init {
        writer.writeStartDocument("UTF-8", "1.0")
        writer.writeCharacters("\n")
    }

    private fun indent() = writer.writeCharacters("  ".repeat(depth))

    private fun attributes(attrs: List<Pair<String, String>>) {
        for ((name, value) in attrs) {
            if (name == "xmi:id") require(ids.add(value)) { "Duplicate XMI identity: $value" }
            require(
                value.codePoints().allMatch {
                    it == 9 ||
                        it == 10 ||
                        it == 13 ||
                        it in 0x20..0xd7ff ||
                        it in 0xe000..0xfffd ||
                        it in 0x10000..0x10ffff
                },
            ) { "Invalid XML 1.0 character in $name" }
            writer.writeAttribute(name, value)
        }
    }

    fun element(
        name: String,
        vararg attrs: Pair<String, String>,
        body: (Xml.() -> Unit)? = null,
    ) {
        indent()
        if (body == null) {
            writer.writeEmptyElement(name)
            attributes(attrs.toList())
            writer.writeCharacters("\n")
        } else {
            writer.writeStartElement(name)
            attributes(attrs.toList())
            writer.writeCharacters("\n")
            depth++
            body()
            depth--
            indent()
            writer.writeEndElement()
            writer.writeCharacters("\n")
        }
    }

    fun bytes(): ByteArray {
        writer.writeEndDocument()
        writer.close()
        // XML attribute normalization would otherwise turn literal tabs/newlines into spaces.
        return Regex("\"[^\"]*\"")
            .replace(text.toString()) {
                it.value
                    .replace("\t", "&#x9;")
                    .replace("\r", "&#xD;")
                    .replace("\n", "&#xA;")
            }.toByteArray(Charsets.UTF_8)
    }
}
