// SPDX-License-Identifier: Apache-2.0
//
// FO-P1.S6.T1 — the token audit anchor: the shared palette is a stable contract,
// so a change here is a deliberate suite-wide restyle, not an accident.

import { describe, it, expect } from 'vitest';
import { color, space, radius, fontSize, tokens, canvas } from '../index.js';

describe('@tatrman/tokens', () => {
  it('exposes the semantic brand + chrome colors', () => {
    expect(color.brand).toBe('#16283F');
    expect(color.accent).toBe('#33506E');
    expect(color.accentBorder).toBe('#CBD8E6');
  });

  it('every color is a 6-digit hex', () => {
    for (const [name, value] of Object.entries(color)) {
      expect(value, name).toMatch(/^#[0-9A-F]{6}$/);
    }
  });

  it('exposes a monotonic spacing scale and the radii', () => {
    expect(space.xs < space.sm && space.sm < space.md && space.md < space.lg).toBe(true);
    expect(radius.pill).toBe(999);
    expect(fontSize.sm).toBe(12);
  });

  it('bundles the full set under `tokens`', () => {
    expect(tokens.color.brand).toBe(color.brand);
    expect(tokens.space).toBe(space);
    expect(tokens.canvas).toBe(canvas);
  });
});

// FO-A1 P1 (contracts §6) — the canvas family palette-stability audit. D1 changes
// PLUMBING, not colour: these values are pinned so a hue edit is a deliberate act,
// never an accidental drift through the skin migration.
describe('@tatrman/tokens — canvas family (D1 palette-stability audit)', () => {
  it('pins every canvas token value (1:1 with the pre-migration skin palette)', () => {
    expect(canvas).toEqual({
      bg: '#E9F0F8',
      bgCnc: '#F3F7FC',
      bgStar: '#EEF4FB',
      grid: '#CBD8E6',
      nodeFill: '#FFFFFF',
      nodeFillMuted: '#F2F5F9',
      nodeFillGhost: '#EEF3F9',
      nodeStroke: '#CBDDF4',
      nodeStrokeGhost: '#B8C8DA',
      ink: '#16283F',
      inkInverse: '#E7EEF7',
      headerTint: '#EAF1FB',
      accentDeep: '#24405F',
      slate: '#33506E',
      regionDark: '#1D3A5C',
      edgeStroke: '#4A4B4D',
      edgeData: '#16283F',
      edgeCnc: '#5B7EA6',
      edgeStar: '#7C93AE',
      edgeScript: '#7EA4D6',
      edgeLabel: '#4A4B4D',
      edgeHop: '#B4651A',
      alive: '#FFCB2E',
      aliveDeep: '#F2A200',
      selOutline: '#F2A200',
      warnInk: '#8A6A10',
      warnInkDark: '#C7B36A',
      warnInkSoft: '#92400E',
      warnBg: '#FBF2DA',
      warnBgSoft: '#FEF3C7',
      warnBorderSoft: '#FDE68A',
      err: '#B3261E',
      errPort: '#C4453C',
      errBg: '#FBE9E7',
      errBorder: '#E7A9A2',
      ok: '#3E7D4E',
      muted: '#96989B',
      divider: '#EDF2F9',
      tableHeaderBg: '#F6F9FD',
      codeText: '#B9C9DE',
      scriptMuted: '#9DB4D0',
      chipRoleBg: '#E6EEF7',
      chipRoleBorder: '#B9CCE0',
      chipCncBg: '#F1F5FB',
      chipCncBorder: '#D9E4F1',
      badgeBg: '#DBE6F4',
      badgeBgDark: '#0F2036',
    });
  });

  it('every canvas token is a 6-digit uppercase hex', () => {
    for (const [name, value] of Object.entries(canvas)) {
      expect(value, name).toMatch(/^#[0-9A-F]{6}$/);
    }
  });

  it('the "alive" token stays Tatrman Yellow (invariant 8 — value FROZEN)', () => {
    expect(canvas.alive).toBe('#FFCB2E');
  });
});
