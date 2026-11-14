// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.writer.TtrRenderer

object TtrWriter {
    /**
     * Renders the full model as a single concatenated TTR string (all schemas
     * merged, no schema directive headers). Useful for diagnostics and simple
     * single-file snapshots; production export uses [MetadataExportRoutes] for
     * the tarball form with per-schema partitioning. With [header] the text is a generated file: the header's line
     * comes first.
     */
    fun writeModel(
        model: Model,
        header: GeneratedHeader? = null,
    ): String {
        val bundle = ModelToDefinitions.convert(model)
        // MW S3.6 T2: a generated file states its inputs on its first line (contracts §6).
        val sb = StringBuilder(header?.line(GeneratedHeader.Form.TTRM).orEmpty())
        for (file in bundle.files) {
            sb.append(TtrRenderer.renderFile(file.schemaCode, file.namespace, file.definitions))
        }
        return sb.toString()
    }
}
