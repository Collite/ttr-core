// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.designer.server.methods

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.ttr.designer.server.graph.TtrgGraphMutations
import org.tatrman.ttr.designer.server.rpc.JsonRpcDispatcher
import org.tatrman.ttr.designer.server.rpc.RpcCodes
import org.tatrman.ttr.designer.server.rpc.TtrmRpcException
import org.tatrman.ttr.parser.loader.TtrgGraphReader
import java.nio.file.Files

/**
 * `ttrm/…` graph-MUTATING methods (FO-21, FO-P0.S2.T5) — the EDIT half split out
 * of the former `registerTtrmWriteMethods`: `applyGraphEdit`/`addObjectToGraph`/
 * `removeObjectFromGraph`/`createGraph`. These are the server-side counterpart of
 * the designer edit surface carved out of the open build in FO-P0.S2.T4.
 *
 * They apply T3.1's ratified edit-application model: this process writes files to
 * disk itself and returns a plain ack — no `WorkspaceEdit` wire type. `.ttrg`
 * mutation goes through [TtrgGraphMutations]; `.ttrg` reads (object counts) reuse
 * the shared parse lib [TtrgGraphReader]. No `deps`: the mutators work on files.
 *
 * WALL / cutover (move manifest §1a): this is the relocation unit for the
 * `ttr-designer-edit-server` module in tatrman-platform. It cannot physically move
 * cross-repo until the read half (transport + JSON-RPC dispatcher + parse lib) is
 * published as an Apache library — a FO-P0.S3 (registry) dependency. Until then it
 * is compiled and registered here so the server keeps working and the specs stay
 * green; the OPEN runtime not serving these routes is the S3-gated cutover step.
 */
fun registerTtrmEditMethods(dispatcher: JsonRpcDispatcher) {
    dispatcher.register("ttrm/applyGraphEdit") { _ ->
        buildJsonObject {
            put("ok", false)
            put("reason", "edit-mode-not-available-in-v1")
        }
    }

    dispatcher.register("ttrm/addObjectToGraph") { params ->
        val uri = requireParam(params, "uri")
        val qname = requireParam(params, "qname")
        val autoImport = (params["autoImport"] as? JsonPrimitive)?.boolean ?: false
        val path = uriToPath(uri)
        if (!Files.isRegularFile(path)) {
            throw TtrmRpcException(
                RpcCodes.NOT_FOUND,
                "not-found",
                buildJsonObject {
                    put("uri", uri)
                },
            )
        }
        val content = Files.readString(path)

        var packageToImport: String? = null
        if (autoImport) {
            val fromQname = qname.substringBefore('.', missingDelimiterValue = "").ifEmpty { null }
            val schemaCodes = setOf("db", "er", "binding", "query", "cnc")
            if (fromQname != null && fromQname !in schemaCodes) packageToImport = fromQname
        }

        val updated =
            TtrgGraphMutations.addObject(content, qname, packageToImport)
                ?: throw TtrmRpcException(RpcCodes.INTERNAL, "no-objects-list", buildJsonObject { put("uri", uri) })
        Files.writeString(path, updated)
        buildJsonObject {
            put("ok", true)
            put("objectCount", TtrgGraphReader.parseObjects(updated).size)
        }
    }

    dispatcher.register("ttrm/removeObjectFromGraph") { params ->
        val uri = requireParam(params, "uri")
        val qname = requireParam(params, "qname")
        val pruneUnusedImport = (params["pruneUnusedImport"] as? JsonPrimitive)?.boolean ?: false
        val path = uriToPath(uri)
        if (!Files.isRegularFile(path)) {
            throw TtrmRpcException(
                RpcCodes.NOT_FOUND,
                "not-found",
                buildJsonObject {
                    put("uri", uri)
                },
            )
        }
        val content = Files.readString(path)

        val updated = TtrgGraphMutations.removeObject(content, qname, pruneUnusedImport)
        if (updated == null) {
            buildJsonObject { put("ok", false) }
        } else {
            Files.writeString(path, updated)
            buildJsonObject {
                put("ok", true)
                put("objectCount", TtrgGraphReader.parseObjects(updated).size)
            }
        }
    }

    dispatcher.register("ttrm/createGraph") { params ->
        val uri = requireParam(params, "uri")
        if (!uri.endsWith(".ttrg")) {
            buildJsonObject {
                put("ok", false)
                put("reason", "uri-must-end-with-ttrg")
            }
        } else {
            val path = uriToPath(uri)
            if (Files.exists(path)) {
                throw TtrmRpcException(
                    RpcCodes.INVALID_PARAMS,
                    "already-exists",
                    buildJsonObject {
                        put("uri", uri)
                    },
                )
            }
            val name = requireParam(params, "name")
            val schema = requireParam(params, "schema")
            val packages =
                (params["packages"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
            val objects =
                (params["objects"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
            val description = (params["description"] as? JsonPrimitive)?.content
            val tags = (params["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
            val content =
                TtrgGraphMutations.createContent(
                    TtrgGraphMutations.CreateGraphParams(name, schema, packages, objects, description, tags),
                )
            Files.createDirectories(path.parent)
            Files.writeString(path, content)
            buildJsonObject {
                put("ok", true)
                put("uri", uri)
            }
        }
    }
}
