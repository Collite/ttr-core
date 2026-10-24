// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle.legacy;

import java.util.TreeMap;
import org.tatrman.ttrp.emit.spi.EmitRequest;
import org.tatrman.ttrp.emit.spi.EmitResult;
import org.tatrman.ttrp.emit.spi.TtrEmitPlugin;

/** Implements only SPI v1; v2 methods must remain JVM defaults for old binaries. */
public class LegacyEmitPlugin implements TtrEmitPlugin {
    @Override public String getTargetId() { return "legacy-v1"; }
    @Override public int getSpiVersion() { return 1; }
    @Override public String executorTypeManifest() { return "legacy test manifest"; }
    @Override public EmitResult emit(EmitRequest request) { return new EmitResult(new TreeMap<>()); }
}
