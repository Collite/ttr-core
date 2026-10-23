// SPDX-License-Identifier: Apache-2.0
import type { Rule } from '../rule.js';
import { STRUCTURE_RULES } from './structure.js';
import { SEARCH_RULES } from './search.js';
import { REFERENCE_RULES } from './references.js';
import { IMPORT_RULES } from './imports.js';
import { PACKAGE_RULES } from './packages.js';
import { AREA_RULES } from './areas.js';
import { GRAPH_RULES } from './graph.js';
import { PROJECT_RULES } from './project.js';
import { MD_HOP_RULES } from './md-hop.js';
import { MD_RULES } from './md.js';
import { SEMANTICS_RULES } from './semantics.js';
import { LEXICON_RULES } from './lexicon.js';
import { DESCRIPTION_RULES } from './descriptions.js';
import { CN_RULES } from './cn.js';

/** Every rule in the registry, assembled from the per-category rule modules. */
export const ALL_RULES: Rule[] = [
  ...STRUCTURE_RULES,
  ...SEARCH_RULES,
  ...REFERENCE_RULES,
  ...IMPORT_RULES,
  ...PACKAGE_RULES,
  ...AREA_RULES,
  ...GRAPH_RULES,
  ...PROJECT_RULES,
  ...MD_RULES,
  ...MD_HOP_RULES,
  ...SEMANTICS_RULES,
  ...LEXICON_RULES,
  ...DESCRIPTION_RULES,
  ...CN_RULES,
];
