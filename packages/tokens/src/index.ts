// SPDX-License-Identifier: Apache-2.0
//
// @tatrman/tokens — the shared Studio design tokens (FO-P1.S6, PF F).
//
// One dependency-free source of the suite's semantic palette/spacing/radii so
// the Studio Viewer, the authoring extension, and the launcher render as one
// product ("suite coherence is earned", P-2). Values are the palette already in
// use across the shell chrome, lifted to named semantic tokens — adopt these
// instead of inlining hex. Cross-repo surfaces (launcher, authoring extension)
// adopt this once it is published (RO-6/⚑2); see skin-deltas-fo-p1.md.

/** Semantic colors — named by role, not by hue. */
export const color = {
  /** the Studio brand navy — active affordances, headings. */
  brand: '#16283F',
  /** text/icon on a brand-filled surface. */
  onBrand: '#FFFFFF',
  /** the chrome accent (button text, active slate). */
  accent: '#33506E',
  /** the chrome accent border. */
  accentBorder: '#CBD8E6',
  /** app surface + neutral borders. */
  surface: '#FFFFFF',
  surfaceMuted: '#F9FAFB',
  border: '#E5E7EB',
  /** text roles. */
  textPrimary: '#16283F',
  textMuted: '#6B7280',
  /** feedback — warn / danger / success (bg + fg pairs). */
  warnBg: '#FEF3C7',
  warnFg: '#92400E',
  warnBorder: '#FDE68A',
  dangerBg: '#FEE2E2',
  dangerFg: '#B91C1C',
  successBg: '#DCFCE7',
  successFg: '#15803D',
} as const;

/** Spacing scale (px). */
export const space = {
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 24,
} as const;

/** Corner radii (px). `pill` = fully rounded. */
export const radius = {
  sm: 6,
  md: 10,
  pill: 999,
} as const;

/** Font sizes (px). */
export const fontSize = {
  xs: 11,
  sm: 12,
  md: 13,
  lg: 16,
  xl: 24,
} as const;

/**
 * Canvas + skin family (FO-A1 P1, contracts §6 — the D1 map). The Designer canvas
 * chrome and every notation skin render through these named tokens instead of raw
 * hex (enforced by designer's `no-raw-hex` guard). Values are the palette already in
 * use, lifted 1:1 — D1 changes *plumbing*, not colour (the palette-stability audit
 * below is the tripwire). Any NEW colour is added here, never inlined.
 *
 * Contract §6 identity names (`--ttr-*`) map to these keys:
 *   --ttr-canvas-bg → bg · --ttr-canvas-grid → grid ·
 *   --ttr-node-fill → nodeFill · --ttr-node-stroke → nodeStroke · --ttr-node-text → ink ·
 *   --ttr-node-accent-{db,stage} → ink · -er → accentDeep · -md → headerTint ·
 *   -cnc/-script → slate ·
 *   --ttr-edge-stroke → edgeStroke · --ttr-edge-label → edgeLabel ·
 *   --ttr-state-alive → alive (FROZEN, invariant 8) · --ttr-sel-outline → selOutline ·
 *   --ttr-warn → warnInk · --ttr-err → err.
 */
export const canvas = {
  // canvas surface + grid
  bg: '#E9F0F8', // ice — default canvas background + region header tint
  bgCnc: '#F3F7FC', // cnc-bubbles canvas
  bgStar: '#EEF4FB', // md-star canvas
  grid: '#CBD8E6', // grid dots + generic light line/border

  // node body
  nodeFill: '#FFFFFF',
  nodeFillMuted: '#F2F5F9', // disabled / idle fill
  nodeFillGhost: '#EEF3F9', // ghost / placeholder node fill
  nodeStroke: '#CBDDF4', // card border
  nodeStrokeGhost: '#B8C8DA', // ghost node border
  ink: '#16283F', // node text; db/stage accent; data port/edge
  inkInverse: '#E7EEF7', // node text on the stage-navy dark theme
  headerTint: '#EAF1FB', // md / cube light header strip

  // per-kind accents
  accentDeep: '#24405F', // er / cube / star / stage-navy header
  slate: '#33506E', // cnc / script accent; slate labels & toggles
  regionDark: '#1D3A5C', // processing region header (dark)

  // edges
  edgeStroke: '#4A4B4D', // default edge (also structure gray)
  edgeData: '#16283F',
  edgeCnc: '#5B7EA6', // cnc edge; unknown-port mid-blue; region-label border
  edgeStar: '#7C93AE', // star edge; cnc-bubble border
  edgeScript: '#7EA4D6',
  edgeLabel: '#4A4B4D',
  edgeHop: '#B4651A', // a hop edge — a path through a hidden card, dashed (VL V-26)

  // living / active (Tatrman Yellow — living/active ONLY, invariant 8; value FROZEN)
  alive: '#FFCB2E',
  aliveDeep: '#F2A200', // key marks + warn border (deep yellow)
  selOutline: '#F2A200', // base-layer selection ring

  // feedback chrome
  warnInk: '#8A6A10',
  warnInkDark: '#C7B36A', // warn ink on dark
  warnInkSoft: '#92400E', // md warn chip fg
  warnBg: '#FBF2DA',
  warnBgSoft: '#FEF3C7', // md warn chip bg
  warnBorderSoft: '#FDE68A',
  err: '#B3261E',
  errPort: '#C4453C', // err / rejects port
  errBg: '#FBE9E7',
  errBorder: '#E7A9A2',
  ok: '#3E7D4E', // run-ready / done green

  // neutral text / chips / structure
  muted: '#96989B',
  divider: '#EDF2F9', // row separators, soft borders
  tableHeaderBg: '#F6F9FD', // result-drawer header cells
  codeText: '#B9C9DE', // processing code preview text
  scriptMuted: '#9DB4D0', // script-variant muted
  chipRoleBg: '#E6EEF7',
  chipRoleBorder: '#B9CCE0',
  chipCncBg: '#F1F5FB',
  chipCncBorder: '#D9E4F1',
  badgeBg: '#DBE6F4', // engine badge (light)
  badgeBgDark: '#0F2036', // engine badge (dark)
} as const;

/** The full token set, for a single-import consumer. */
export const tokens = { color, space, radius, fontSize, canvas } as const;

export type ColorToken = keyof typeof color;
export type SpaceToken = keyof typeof space;
export type RadiusToken = keyof typeof radius;
export type CanvasToken = keyof typeof canvas;
