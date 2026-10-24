// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle.legacy;

/** A future SPI major must still be refused by the v2 host. */
public class FutureEmitPlugin extends LegacyEmitPlugin {
    @Override public String getTargetId() { return "future-spi"; }
    @Override public int getSpiVersion() { return 99; }
}
