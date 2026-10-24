#!/usr/bin/env node
// FO-P0.S2.T1 — proves the Studio Viewer build carries NO edit-surface code.
//
// The FO-21 wall is honesty-by-absence: "commercial code is simply absent from
// open distributions" (FO contracts §2). The open `@tatrman/designer-core` build IS
// the Studio Viewer. This greps its built bundle for edit markers — the
// `@tatrman/edit` synthesizer, the user-facing edit affordances, and the edit
// RPC method names (wire strings that survive minification). Any hit ⇒ exit 1.
//
// SCOPE (important): the wall is the *graphical edit surface in the Designer
// app*, NOT the LSP. The open tier keeps first-class IDE/LSP authoring (FO-27)
// and the `@tatrman/edit` WorkspaceEdit synthesizer (Bora ruling 2026-07-18);
// the LSP browser-worker bundle (`browser.worker*.js`) legitimately carries
// that synthesis and is EXCLUDED from this scan. What must be absent is the
// commercial graphical authoring surface. SO D-19 amends FO-21: the text drawer
// and modeler/edit request forwarding are open; commercial forms remain absent.
//
// Red until FO-P0.S2.T4 removes the edit surface from the core. Wired into CI.
//
// Usage: `node scripts/check-viewer-bundle.mjs` (build the designer first, or
// pass --build to build it here).
import { readdirSync, readFileSync, existsSync, statSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execSync } from 'node:child_process';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const distDir = join(repoRoot, 'packages', 'designer', 'dist');

// Edit-surface markers that must be ABSENT from the Viewer build. Chosen to
// survive minification: an npm specifier, user-visible affordance text, and
// edit-only RPC method leaf names sent verbatim over the wire.
const FORBIDDEN = [
  '@tatrman/edit',
  '+ Add object',
  'Remove from graph',
  '+ Create New Graph',
  'addObjectToGraph',
  'removeObjectFromGraph',
  'applyGraphEdit',
];

if (process.argv.includes('--build')) {
  console.log('[check-viewer-bundle] building @tatrman/designer-core …');
  execSync('pnpm --filter @tatrman/designer-core build', { cwd: repoRoot, stdio: 'inherit' });
}

if (!existsSync(distDir)) {
  console.error(`[check-viewer-bundle] no build at ${relative(repoRoot, distDir)} — run the designer build first (or pass --build).`);
  process.exit(2);
}

// The LSP browser worker is an open server capability (retains WorkspaceEdit
// synthesis for IDE authoring) — not the graphical edit surface. Exclude it.
const LSP_WORKER = /(^|[/\\])(?:server-[^/\\]*|browser\.worker(?:-[^/\\]*)?)\.m?js$/;

/** Emitted app JS/HTML/CSS in the build, excluding the LSP worker bundle. */
function bundleFiles(dir) {
  const out = [];
  for (const entry of readdirSync(dir)) {
    const p = join(dir, entry);
    if (statSync(p).isDirectory()) out.push(...bundleFiles(p));
    else if (/\.(m?js|html|css)$/.test(entry) && !LSP_WORKER.test(p)) out.push(p);
  }
  return out;
}

const files = bundleFiles(distDir);
const hits = [];
for (const file of files) {
  const text = readFileSync(file, 'utf8');
  for (const marker of FORBIDDEN) {
    if (text.includes(marker)) hits.push({ file: relative(repoRoot, file), marker });
  }
}

if (hits.length > 0) {
  console.error('[check-viewer-bundle] FAIL — edit-surface code present in the Viewer build:');
  for (const { file, marker } of hits) console.error(`  ${file}: contains ${JSON.stringify(marker)}`);
  console.error(`\n${hits.length} marker hit(s). The Studio Viewer build must ship no authoring code (FO-21/FO contracts §2).`);
  process.exit(1);
}

console.log(`[check-viewer-bundle] OK — ${files.length} bundle file(s) scanned, no edit-surface markers.`);
