// SPDX-License-Identifier: Apache-2.0
//
// The copyable federation URL for the active view (FO contracts §3: "every Studio
// surface renders a stable, copyable URL for its current context"). Read-only —
// no edit code (FO-21 safe). The URL is the §3 federation projection of the active
// tab (see `federation-link.ts`) in the standalone viewer. Hosted mode supplies the
// current view under the parent rail instead, so shared links can receive init/auth again.
//
// Chrome styled from @tatrman/tokens (FO-P1.S6.T1 — shared suite palette).

import { useState } from 'react';
import { color, radius, space, fontSize } from '@tatrman/tokens';

export function CopyLinkButton({ url, disabledReason }: { url: string; disabledReason?: string }) {
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    try {
      await navigator.clipboard?.writeText(url);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // clipboard denied (no permission / insecure context) — the URL stays visible
      // in the title for a manual copy; never throw from a share affordance.
    }
  };

  if (disabledReason) {
    // nothing to link to (a local layout file, VL V-35) — visible, disabled, and saying why
    return (
      <button type="button" data-testid="copy-federation-link" disabled aria-disabled="true" title={disabledReason} aria-label={`Copy link — ${disabledReason}`}
        style={{ marginLeft: 'auto', display: 'inline-flex', alignItems: 'center', gap: space.xs + 2, border: `1px dashed ${color.accentBorder}`, borderRadius: radius.sm, background: color.surface, color: '#96989B', padding: `3px ${space.md - 2}px`, fontSize: fontSize.sm, cursor: 'not-allowed' }}>
        🔗 Copy link
      </button>
    );
  }

  return (
    <button
      type="button"
      data-testid="copy-federation-link"
      data-federation-url={url}
      aria-label={`Copy link to this view: ${url}`}
      title={url}
      onClick={copy}
      style={{
        marginLeft: 'auto', display: 'inline-flex', alignItems: 'center', gap: space.xs + 2,
        border: `1px solid ${color.accentBorder}`, borderRadius: radius.sm, background: color.surface,
        color: color.accent, padding: `3px ${space.md - 2}px`, fontSize: fontSize.sm, cursor: 'pointer',
      }}
    >
      🔗 {copied ? 'Copied' : 'Copy link'}
    </button>
  );
}
