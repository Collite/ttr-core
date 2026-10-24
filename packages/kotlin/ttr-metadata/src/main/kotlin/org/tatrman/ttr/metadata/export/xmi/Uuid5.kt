// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export.xmi

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** RFC 4122 §4.3: UTF-8 name, network-order namespace, SHA-1, version 5 and RFC variant. */
object Uuid5 {
    val DNS: UUID = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
    val URL: UUID = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")

    fun of(
        namespace: UUID,
        name: String,
    ): UUID {
        val ns =
            ByteBuffer
                .allocate(
                    16,
                ).putLong(namespace.mostSignificantBits)
                .putLong(namespace.leastSignificantBits)
                .array()
        val hash = MessageDigest.getInstance("SHA-1").digest(ns + name.toByteArray(Charsets.UTF_8))
        hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(hash)
        return UUID(buffer.long, buffer.long)
    }
}

/** C-12 uses the standard URL namespace for IRI names, including stable synthetic fragment IRIs. */
object EaIds {
    fun of(iri: String): String =
        "EAID_" +
            Uuid5
                .of(Uuid5.URL, iri)
                .toString()
                .uppercase()
                .replace('-', '_')
}
