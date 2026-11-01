// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle.legacy;

/** Models an external pre-D-140 v2 binary: it never opted into revision 1. */
public class OldV2EmitPlugin extends LegacyEmitPlugin {
    @Override public String getTargetId() { return "old-v2"; }
    @Override public int getSpiVersion() { return 2; }
}
