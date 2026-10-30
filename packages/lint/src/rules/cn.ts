// SPDX-License-Identifier: Apache-2.0
import { DiagnosticCode } from '@tatrman/parser';
import { cncDeprecations, validateCn } from '@tatrman/semantics';
import type { CnValidation } from '@tatrman/semantics';
import { cnDocumentsFor } from './project-documents.js';
import type { ProjectRuleContext, Rule } from '../rule.js';

// 0.17 (CN-P2 S2.3) — surface the ttr-semantics cn analysis (`validateCn`, cn contracts §7) as lint
// rules, one per slug (per-rule severity config, the semantics-rules pattern). The analysis is
// project-wide — a concept, its groundings and the elements they target live in different files — so
// it runs once per `lintProject` pass, memoised in the pass cache, and every cn rule reads it.
// `cn/cnc-deprecated` needs one document only and is document-scoped.

const CN_KEY = 'cn:validation';

export function validationFor(ctx: ProjectRuleContext): CnValidation {
  const cached = ctx.cache.get(CN_KEY) as CnValidation | undefined;
  if (cached) return cached;
  const validation = validateCn(cnDocumentsFor(ctx), ctx.resolver);
  ctx.cache.set(CN_KEY, validation);
  return validation;
}

function cnRule(id: string, code: DiagnosticCode, defaultSeverity: Rule['defaultSeverity'], docs: string): Rule {
  return {
    id,
    code,
    category: 'cn',
    scope: 'project',
    defaultSeverity,
    docs,
    check(ctx) {
      if (ctx.scope !== 'project') return;
      for (const d of validationFor(ctx).diagnostics) {
        if (d.code !== code) continue;
        ctx.report({ source: { ...d.source, file: d.uri }, message: d.message });
      }
    },
  };
}

const cncDeprecated: Rule = {
  id: 'cn-cnc-deprecated',
  code: DiagnosticCode.CnCncDeprecated,
  category: 'cn',
  scope: 'document',
  defaultSeverity: 'warning',
  docs:
    "`model cnc` and an entity's `roles:` are deprecated (cn contracts §1.6): the cnc roles are superseded by " +
    'the conceptual model (`model cn`). They still parse and load through the deprecation window; there is ' +
    'nothing to migrate to (CN-D18). Never reported on the stock cnc vocabulary.',
  check(ctx) {
    if (ctx.scope !== 'document') return;
    for (const d of cncDeprecations(ctx.ast, ctx.uri)) ctx.report({ source: d.source, message: d.message });
  },
};

/** The hygiene slugs: off in `recommended`, `info` in `strict` (cn contracts §7 "hygiene"). */
export const CN_HYGIENE_RULE_IDS: ReadonlySet<string> = new Set([
  'cn-ungrounded',
  'cn-phase-no-definition',
  'cn-event-no-period',
]);

export const CN_RULES: Rule[] = [
  cnRule('cn-subkind-temporal-definition', DiagnosticCode.CnSubkindTemporalDefinition, 'error', 'CN-P8 things-in-time validation: subkind temporal definition.'),
  cnRule('cn-subkind-bearer', DiagnosticCode.CnSubkindBearer, 'error', 'CN-P8 things-in-time validation: a subkind specializes a rigid bearer (kind, subkind, role or event).'),
  cnRule('cn-partition-overlap', DiagnosticCode.CnPartitionOverlap, 'error', 'CN-P8 things-in-time validation: partition overlap.'),
  cnRule('cn-partition-incomplete', DiagnosticCode.CnPartitionIncomplete, 'warning', 'CN-P8 things-in-time validation: partition incomplete.'),
  cnRule('cn-subkind-as-kind', DiagnosticCode.CnSubkindAsKind, 'warning', 'CN-P8 things-in-time validation: subkind as kind.'),
  cnRule('cn-extent-retired', DiagnosticCode.CnExtentRetired, 'warning', 'CN-P8 things-in-time validation: extent retired.'),
  cnRule('cn-durative-event', DiagnosticCode.CnDurativeEvent, 'error', 'CN-P8 things-in-time validation: durative event.'),
  cnRule('cn-episode-subject-missing', DiagnosticCode.CnEpisodeSubjectMissing, 'error', 'CN-P8 things-in-time validation: episode subject missing.'),
  cnRule('cn-phase-bounds-participant', DiagnosticCode.CnPhaseBoundsParticipant, 'error', 'CN-P8 things-in-time validation: phase bounds participant.'),
  cnRule('cn-phase-source-conflict', DiagnosticCode.CnPhaseSourceConflict, 'error', 'CN-P8 things-in-time validation: phase source conflict.'),
  cnRule('cn-projection-source', DiagnosticCode.CnProjectionSource, 'error', 'CN-P8 things-in-time validation: projection source.'),
  cnRule('cn-lifetime-period', DiagnosticCode.CnLifetimePeriod, 'error', 'CN-P8 things-in-time validation: lifetime period.'),

  cnRule(
    'cn-duplicate-grounding',
    DiagnosticCode.CnDuplicateGrounding,
    'error',
    'A concept is grounded more than once — one meaning, one binding (CN-D12): a second binding, `grounds:` ' +
      'or `concept { }` block is reported at each later site (the first in document and source order is the ' +
      'one read). Also a `concept { }` block that names a concept a cn def declares and states it differently ' +
      '(another stereotype, or a property both state with different values).',
  ),
  cnRule(
    'cn-grounding-target-kind',
    DiagnosticCode.CnGroundingTargetKind,
    'error',
    "A grounding's target is not what its stereotype grounds to on that layer (cn contracts §1.2): md — a kind " +
      'or role to a dimension or a domain, a quality to a measure or an md attribute, an event to a cubelet; ' +
      'er — a kind to an entity or an attribute (a value kind), a role or an event to an entity, a quality to ' +
      'an attribute, a phase to an entity stacked on a query (AM-2); db — any db object. Also a binding whose ' +
      'concept slot names a concept of another stereotype, a phase grounded to md or db, a relation type with ' +
      'a grounding, and a `grounds:` that names no md, er or db element.',
  ),
  cnRule('cn-role-no-event', DiagnosticCode.CnRoleNoEvent, 'error', 'A role names no defining event (`event:`, CN-D6) — a role is played in an event.'),
  cnRule(
    'concept-bearer-missing',
    DiagnosticCode.RequiredPropertyMissing,
    'error',
    'A quality or a phase names no bearer: `of:` is required on both (cn contracts §1.1).',
  ),
  cnRule(
    'cn-phase-double-definition',
    DiagnosticCode.CnPhaseDoubleDefinition,
    'error',
    'A phase has both a `definition:` and a grounding — a phase is defined, or grounded to an entity stacked on ' +
      'a query whose query is the definition, never both (AM-2).',
  ),
  cnRule(
    'cn-phase-no-definition',
    DiagnosticCode.CnPhaseNoDefinition,
    'info',
    'Hygiene: a phase has neither a `definition:` nor a grounding, so no consumer can tell its members. A ' +
      'superconcept marked `abstract: true` is exempt.',
  ),
  cnRule(
    'cn-event-no-period',
    DiagnosticCode.CnEventNoPeriod,
    'info',
    'Hygiene: an event has no `period:` — no consumer can place it in time (legitimate for an act with no time ' +
      'of its own; the hygiene report lists it).',
  ),
  cnRule(
    'cn-ungrounded',
    DiagnosticCode.CnUngrounded,
    'info',
    'Hygiene: a concept has no grounding yet, so no consumer reaches it (P-1: eventually grounded — a normal ' +
      'working state, never an error). Exempt: relation types (open tier), phases (`cn-phase-no-definition` ' +
      'speaks for them), defined qualities and member-restricted sub-kinds.',
  ),
  cnRule(
    'cn-db-grounding',
    DiagnosticCode.CnDbGrounding,
    'info',
    'Advisory: a concept is grounded to db (`cn2db_concept`) — documentation only; no consumer reaches a db ' +
      'grounding. Ground it to md (what analysts aggregate) or er.',
  ),
  cnRule(
    'cn-hierarchy-shape',
    DiagnosticCode.CnHierarchyShape,
    'error',
    "A grounding's `hierarchy:` is malformed: its `closure:` must be an er entity that carries the `ancestor:` " +
      'and `descendant:` attributes it names, and a key.',
  ),
  cnRule(
    'cn-participant-path-broken',
    DiagnosticCode.CnParticipantPathBroken,
    'error',
    "An event binding's participant path does not chain: its first relation must touch the event's entity, each " +
      "next one must share an entity with the one before, and where the participant concept is grounded to er " +
      'directly the path must end on that entity. An md-grounded participant\'s end is not checked here.',
  ),
  cnRule(
    'cn-involves-unlabelled-duplicate',
    DiagnosticCode.CnInvolvesUnlabelledDuplicate,
    'error',
    "Two participants of one event share a label — a participant's label defaults to its concept's name in " +
      'lower case, so the same concept twice needs labels (`Warehouse as from, Warehouse as to`).',
  ),
  cnRule(
    'cn-unknown-relation-type',
    DiagnosticCode.CnUnknownRelationType,
    'error',
    "An open-tier edge's `type:` names no `def relation_type` (cn contracts §1.3).",
  ),
  cnRule(
    'cn-antirigid-supertype',
    DiagnosticCode.CnAntirigidSupertype,
    'warning',
    "A kind's `is_a` chain reaches a role or a phase. A kind is rigid — every instance is one for as long as it " +
      'exists — so it cannot specialize an anti-rigid concept (OntoUML, CN-D8). Make it a role or a phase.',
  ),
  cncDeprecated,
  cnRule(
    'cn-duplicate-default-sense',
    DiagnosticCode.CnDuplicateDefaultSense,
    'error',
    'Two lexicon terms claim `default: true` for one bare form in one locale (cn contracts §1.5) — at most one ' +
      'default sense per bare form per locale per model.',
  ),
  cnRule(
    'cn-phase-as-default',
    DiagnosticCode.CnPhaseAsDefault,
    'warning',
    "A term for a phase is marked `default: true` while one of its forms also names a concept up the phase's " +
      '`of:` chain. A bare term never defaults to a phase — the resolver ignores the default (AM-36).',
  ),
];
