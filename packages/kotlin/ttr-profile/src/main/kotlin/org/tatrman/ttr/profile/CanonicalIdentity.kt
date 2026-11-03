// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import org.tatrman.ttr.yaml.CanonicalYaml

/** Identity on canonical bytes, with the codec's original located errors and normalization for noncanonical input. */
object CanonicalIdentity {
    fun apply(
        document: String,
        file: String = "<yaml>",
    ): String = CanonicalYaml.write(CanonicalYaml.read(document, file))
}
