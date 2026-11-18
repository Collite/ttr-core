// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import com.networknt.schema.PathType
import com.networknt.schema.SchemaValidatorsConfig

/**
 * MW S4.2a (✅D-MW-93, S4.2 Found (a)) — the one networknt configuration for the bundled schemas
 * (`model-yaml.schema.json`, `profile.schema.json`), which live as long as the process.
 *
 * networknt preloads every validator of a schema by default: for `profile.schema.json` (1 861 `$defs`, keyword
 * alternations of ~2 000 characters) that kept ~142 000 `JsonSchema` and 2.85 M regex nodes on the heap — 236 MB
 * after loading the 13 canonical demo-cs files, on a metadata pod with a 256 MB heap. With preload off, a validator
 * is built the first time a document reaches it, so only the paths documents use are ever built. Unresolvable
 * references then fail at validation rather than at schema construction; these schemas are generated and checked
 * (`schemas:check`), and the suites exercise every branch.
 */
internal val SCHEMA_VALIDATORS_CONFIG: SchemaValidatorsConfig =
    SchemaValidatorsConfig
        .builder()
        .pathType(PathType.JSON_POINTER)
        .preloadJsonSchema(false)
        .build()
