// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.model

/**
 * Library-owned qualified name (M1 de-proto shim, RM1). The kantheon metadata service keyed every model
 * object on the proto QualifiedName message; the extracted library
 * must not depend on `:shared:proto`, so this is the drop-in replacement. Field
 * shape mirrors the proto message (`package`, `schemaCode`, `namespace`, `name`);
 * consumers convert at their edges (the metadata service's grpc layer does proto↔library
 * conversion in M4). Value semantics (data class) give the same map-key behaviour
 * the proto message had.
 *
 * WORLD is library-only (M2) — the proto SchemaCode (frozen, MD7) has no world
 * tier because the metadata service never did world resolution; M4's proto conversion drops it.
 * AUTOMATION (grammar 0.16) is library-only too — the automation tier is read by the automation
 * service, never sent over the metadata wire. So is CN (grammar 0.17, the conceptual tier — CN-P2 S2.5).
 */
enum class SchemaCode { UNSPECIFIED, DB, ER, CNC, WS, OBJ, WORLD, AUTOMATION, CN }

/**
 * Parse a schema-code token (`"db"`, `"er"`, `"cnc"`, `"ws"`, `"obj"`) into a
 * [SchemaCode]. Case-insensitive; `null` for unknown tokens. Ported verbatim from
 * kantheon SchemaCodes (shared proto module) (provenance: `parseSchemaCode`).
 */
fun parseSchemaCode(code: String): SchemaCode? =
    when (code.lowercase()) {
        "db" -> SchemaCode.DB
        "er" -> SchemaCode.ER
        "cnc" -> SchemaCode.CNC
        "ws" -> SchemaCode.WS
        "obj" -> SchemaCode.OBJ
        "world" -> SchemaCode.WORLD
        "automation" -> SchemaCode.AUTOMATION
        "cn" -> SchemaCode.CN
        "schema_code_unspecified" -> SchemaCode.UNSPECIFIED
        else -> null
    }

/**
 * Render a [SchemaCode] as its lowercase token; empty string for [SchemaCode.UNSPECIFIED].
 * Ported verbatim from kantheon SchemaCodes (`schemaCodeToToken`).
 */
fun schemaCodeToToken(sc: SchemaCode): String =
    when (sc) {
        SchemaCode.DB -> "db"
        SchemaCode.ER -> "er"
        SchemaCode.CNC -> "cnc"
        SchemaCode.WS -> "ws"
        SchemaCode.OBJ -> "obj"
        SchemaCode.WORLD -> "world"
        SchemaCode.AUTOMATION -> "automation"
        SchemaCode.CN -> "cn"
        SchemaCode.UNSPECIFIED -> ""
    }

data class QualifiedName(
    val schemaCode: SchemaCode = SchemaCode.UNSPECIFIED,
    val namespace: String = "",
    val name: String = "",
    @Suppress("ConstructorParameterNaming")
    val `package`: String = "",
) {
    /**
     * GH #53 — render a qname with its canonical lowercase schema token
     * (`er.entity.x`), not the uppercase enum name. Falls back to the namespace
     * when the schema code is UNSPECIFIED so the string still round-trips. Moved
     * here from `MetadataServiceImpl.dotted()` — it belongs to the model, not the
     * wire (M1, T1.1.3).
     */
    fun dotted(): String {
        val token = schemaCodeToToken(schemaCode)
        return "${token.ifEmpty { namespace }}.$namespace.$name"
    }
}
