// SPDX-License-Identifier: Apache-2.0
/**
 * Grammar 0.16 — the automation model's semantic contract (AU-core-P0 S0.2 T4; AU ttr-core
 * contracts §2–§3). The TS twin of Kotlin `AutomationValidator.kt`: the same `ttr/automation-*` /
 * `ttr/states-*` codes on the same documents (a cross-target contract, pinned by the same fixtures).
 *
 * Project-scoped (references cross files) and standalone — the `validateWorldDocument` precedent:
 * not part of the lint rule set and excluded from the conformance dump. The automation stdlib
 * (`stock/automation-stdlib.ttrm`, see `loadAutomationStdlib` in `node-only`) joins the project when
 * the caller passes it and the project needs it (`automationStdlibNeeded`). Name matching is by
 * object name within the project (`role.ops` → the role `ops`).
 */
import type {
  AttributeDef,
  AutoBody,
  AutoValue,
  AutomationDef,
  Document,
  EntityDef,
  ProcessDef,
  RoleDef,
  SourceLocation,
  WorldDef,
} from '@tatrman/parser';

export type AutomationSeverity = 'error' | 'warning' | 'info';

export interface AutomationDiagnostic {
  code: string;
  message: string;
  severity: AutomationSeverity;
  source: SourceLocation;
}

const PARAMETER_TYPES = new Set(['decimal', 'int', 'text', 'date', 'days', 'working_days', 'bool']);
const DURATION_UNITS = new Set(['seconds', 'minutes', 'hours', 'days', 'working_days', 'weeks', 'months']);
const COLUMN_TYPES = new Set(['text', 'int', 'decimal', 'date', 'timestamp', 'bool', 'rows', 'role', 'action']);
const NATIVE_SINKS = new Set(['transition', 'writer', 'deliver', 'task', 'obligation', 'start', 'emit']);
const APPROVAL_KINDS = new Set(['recipe', 'values', 'program', 'deployment', 'personal']);
const PORTS = new Set(['none', 'key', 'keys', 'event']);
const STEP_PORTS = new Set(['key', 'keys']);
const MODES = new Set(['batch', 'on_event', 'windowed']);
const PROTOCOLS = new Set(['always', 'on_route']);
const GRANULARITIES = new Set(['recipe', 'parts', 'islands']);
const UNTIL = new Set(['fulfilled', 'due', 'absent']);
const DIGEST_PER = new Set(['day', 'week']);
const DEPLOYMENT_FLAGS = ['dry_run', 'enabled', 'debug', 'ungoverned', 'allow_restricted_view', 'approval_override', 'obo_only'];

/** Known free-form keys per kind (the typed alternatives are always allowed). */
const KNOWN: Record<string, Set<string>> = {
  parameter: new Set(['value']),
  action: new Set(['sinks', 'approval', 'deliverable', 'due', 'on_due', 'dry_run']),
  service: new Set(['request', 'response', 'timeout', 'budget', 'write', 'idempotency']),
  approval: new Set(['by', 'quorum']),
  program: new Set([
    'recipe', 'port', 'event', 'mode', 'as_of', 'deliver', 'schedule', 'overrides', 'budget', 'turn_budget', 'ask_due', 'protocol',
  ]),
  role: new Set(['resolve', 'members', 'delivery']),
  identity: new Set(['principal', 'credential']),
  grant: new Set(['who', 'program', 'as']),
  deployment: new Set(['program', 'process', 'run_as', 'trigger', 'granularity', 'read_ceiling', ...DEPLOYMENT_FLAGS]),
  process: new Set(['instance']),
  step: new Set(['program', 'port', 'after']),
};

/** A project needs the stdlib when a document declares `model automation` or imports an `automation` package. */
export function automationStdlibNeeded(docs: readonly Document[]): boolean {
  return docs.some(
    (d) => d.modelDirective?.modelCode === 'automation' || d.imports.some((i) => i.target.split('.').includes('automation')),
  );
}

/**
 * Validate every automation object of a project.
 *
 * @param docs    every parsed document of the project
 * @param stdlib  the parsed automation stdlib; joins the project when given and the project needs it
 */
export function validateAutomationProject(
  docs: readonly Document[],
  options: { stdlib?: Document; includeStdlib?: boolean } = {},
): AutomationDiagnostic[] {
  const include = options.includeStdlib ?? automationStdlibNeeded(docs);
  const all = include && options.stdlib ? [...docs, options.stdlib] : [...docs];
  const ctx = new Ctx(new Index(all));
  for (const doc of docs) {
    const automationFile = doc.modelDirective?.modelCode === 'automation';
    for (const def of doc.definitions) {
      switch (def.kind) {
        case 'parameter':
        case 'action':
        case 'service':
        case 'approval':
        case 'program':
        case 'recipe':
          ctx.automation(def);
          break;
        case 'role':
          if (automationFile || (def.entries?.length ?? 0) > 0) ctx.role(def);
          break;
        case 'world':
          ctx.world(def);
          break;
        case 'entity':
          for (const a of def.attributes ?? []) ctx.lifecycle(def, a);
          break;
        default:
          break;
      }
    }
  }
  return ctx.out;
}

function entry(body: { entries: { key: string; value: AutoValue }[] }, key: string): AutoValue | undefined {
  for (let i = body.entries.length - 1; i >= 0; i--) if (body.entries[i].key === key) return body.entries[i].value;
  return undefined;
}

function objEntry(v: AutoValue, key: string): AutoValue | undefined {
  return v.kind === 'autoObject' ? entry(v, key) : undefined;
}

function idPath(v: AutoValue | undefined): string | undefined {
  return v?.kind === 'id' ? v.path : undefined;
}

function typeName(t: AutomationDef['type']): string | undefined {
  if (!t) return undefined;
  return t.kind === 'simple' ? t.name : t.typeName;
}

/** A value as written (for messages and the grant triple key) — mirrors Kotlin `render`. */
function render(v: AutoValue): string {
  switch (v.kind) {
    case 'string':
    case 'tripleString':
      return `"${v.value}"`;
    case 'number':
      return String(v.value);
    case 'bool':
      return String(v.value);
    case 'null':
      return 'null';
    case 'id':
      return v.path;
    case 'duration':
      return `${v.amount} ${v.unit}`;
    case 'tagged':
      return `${v.tag} "${v.value}"`;
    case 'call':
      return `${v.name} { … }`;
    case 'star':
      return '*';
    case 'autoObject':
      return `{ ${v.entries.map((e) => `${e.key}: ${render(e.value)}`).join(', ')} }`;
    case 'autoList':
      return `[${v.items.map(render).join(', ')}]`;
  }
}

class Index {
  readonly entities = new Map<string, EntityDef>();
  readonly schemas = new Set<string>();
  readonly autos = new Map<string, Map<string, AutomationDef>>();
  readonly roles = new Set<string>();
  readonly identities = new Set<string>();
  readonly processes = new Set<string>();

  constructor(docs: readonly Document[]) {
    for (const doc of docs) {
      const automationFile = doc.modelDirective?.modelCode === 'automation';
      for (const def of doc.definitions) {
        switch (def.kind) {
          case 'entity':
            this.entities.set(def.name, def);
            break;
          case 'schema':
            this.schemas.add(def.name);
            break;
          case 'parameter':
          case 'action':
          case 'service':
          case 'approval':
          case 'program':
          case 'recipe': {
            let m = this.autos.get(def.kind);
            if (!m) this.autos.set(def.kind, (m = new Map()));
            m.set(def.name, def);
            break;
          }
          case 'role':
            if (automationFile || (def.entries?.length ?? 0) > 0) this.roles.add(def.name);
            break;
          case 'world':
            for (const m of def.identities ?? []) this.identities.add(m.name);
            for (const p of def.processes ?? []) this.processes.add(p.name);
            break;
          default:
            break;
        }
      }
    }
  }

  has(kind: string, name: string): boolean {
    return this.autos.get(kind)?.has(name) === true;
  }

  /** `entity.attribute` (the last two segments of a dotted ref) → the attribute. */
  attribute(path: string): { entity?: EntityDef; attr?: AttributeDef } {
    const parts = path.split('.');
    if (parts.length < 2) return {};
    const entity = this.entities.get(parts[parts.length - 2]);
    if (!entity) return {};
    return { entity, attr: (entity.attributes ?? []).find((a) => a.name === parts[parts.length - 1]) };
  }
}

class Ctx {
  readonly out: AutomationDiagnostic[] = [];

  constructor(private readonly ix: Index) {}

  private report(code: string, message: string, source: SourceLocation, severity: AutomationSeverity = 'error'): void {
    this.out.push({ code, message, severity, source });
  }

  // ----- the six kinds -----

  automation(def: AutomationDef): void {
    if (def.kind === 'recipe') return; // generated by the compiler; its shape is the compiler's contract
    const what = `${def.kind} '${def.name}'`;
    this.commonEntries(def.kind, what, def);
    switch (def.kind) {
      case 'parameter':
        this.parameter(def, what);
        break;
      case 'action':
        this.action(def, what);
        break;
      case 'service':
        this.service(def, what);
        break;
      case 'approval':
        this.approval(def, what);
        break;
      case 'program':
        this.program(def, what);
        break;
    }
  }

  private parameter(def: AutomationDef, what: string): void {
    const type = typeName(def.type);
    if (type === undefined) this.missing(what, 'type', def.source);
    else if (!PARAMETER_TYPES.has(type)) this.invalid(what, 'type', type, PARAMETER_TYPES, def.source);
    const value = entry(def, 'value');
    if (!value) this.missing(what, 'value', def.source);
    else if (type !== undefined && PARAMETER_TYPES.has(type) && !valueFits(type, value)) {
      this.report('ttr/automation-invalid-value', `${what}: value ${render(value)} does not fit its type '${type}'`, value.source);
    }
  }

  private action(def: AutomationDef, what: string): void {
    const columns = def.columns ?? [];
    if (columns.length === 0) this.missing(what, 'columns', def.source);
    for (const col of columns) {
      const t = col.type ? (col.type.kind === 'simple' ? col.type.name : col.type.typeName) : undefined;
      const cw = `${what} column '${col.name}'`;
      if (t === undefined) this.missing(cw, 'type', col.source);
      else if (!COLUMN_TYPES.has(t)) {
        if (t.includes('.')) this.attributeRef(cw, t, col.source);
        else this.invalid(cw, 'type', t, new Set([...COLUMN_TYPES, '<entity>.<attribute>']), col.source);
      }
    }
    const names = new Set(columns.map((c) => c.name));
    const sinks = entry(def, 'sinks');
    if (!sinks) this.missing(what, 'sinks', def.source);
    else if (sinks.kind === 'autoObject') {
      for (const e of sinks.entries) {
        if (e.key === 'native') this.enumId(what, 'sinks.native', e.value, NATIVE_SINKS);
        else if (!(e.value.kind === 'string' || e.value.kind === 'tripleString' || e.value.kind === 'id')) {
          // a target's sink is its own vocabulary (a node name, `door`, `message`, …)
          this.report('ttr/automation-invalid-value', `${what}: sink '${e.key}' must be a name or a string`, e.value.source);
        }
      }
    } else this.shape(what, 'sinks', 'an object `{ native: …, <target>: … }`', sinks);
    const approval = entry(def, 'approval');
    if (approval) this.enumId(what, 'approval', approval, new Set(['none', 'required']));
    const deliverable = entry(def, 'deliverable');
    if (deliverable) this.bool(what, 'deliverable', deliverable);
    const due = entry(def, 'due');
    if (due) this.duration(what, 'due', due);
    const onDue = entry(def, 'on_due');
    if (onDue) {
      if (onDue.kind === 'call') {
        if (!this.ix.has('action', onDue.name)) this.unknownRef(what, 'on_due', 'action', onDue.name, onDue.source);
      } else this.shape(what, 'on_due', 'an action call `<action> { … }`', onDue);
    }
    const dryRun = entry(def, 'dry_run');
    if (dryRun && dryRun.kind !== 'autoObject') this.shape(what, 'dry_run', 'a localized text `{ en: …, cs: … }`', dryRun);
    const t = def.transition;
    if (t) {
      const { entity, attr } = this.attributeRef(`${what} transition`, t.attribute, t.source);
      if (t.toColumn !== undefined && !names.has(t.toColumn)) {
        this.report('ttr/automation-unknown-column', `${what}: transition target \`{${t.toColumn}}\` is not a column`, t.source);
      }
      if (entity && attr && (attr.states?.length ?? 0) > 0) {
        const states = new Set(attr.states);
        const name = `${entity.name}.${attr.name}`;
        if (t.to !== undefined && !states.has(t.to)) {
          this.report('ttr/states-unknown-state', `${what}: '${t.to}' is not a state of ${name} (${[...states].join(', ')})`, t.source);
        }
        for (const from of t.from) {
          if (!states.has(from)) this.report('ttr/states-unknown-state', `${what}: from-state '${from}' is not a state of ${name}`, t.source);
        }
        const allowed = new Map((attr.transitions ?? []).map((x) => [x.from, new Set(x.to)]));
        if (t.to !== undefined && allowed.size > 0) {
          for (const from of t.from) {
            // D-85: a self-transition is implicit and silent.
            if (from !== t.to && states.has(from) && !(allowed.get(from)?.has(t.to) ?? false)) {
              this.report('ttr/states-transition-not-allowed', `${what}: ${name} does not allow ${from} -> ${t.to}`, t.source);
            }
          }
        }
      }
    }
    const s = def.set;
    if (s) {
      this.attributeRef(`${what} set`, s.attribute, s.source);
      if (!names.has(s.column)) this.report('ttr/automation-unknown-column', `${what}: set column '${s.column}' is not a column`, s.source);
    }
  }

  private service(def: AutomationDef, what: string): void {
    for (const key of ['request', 'response']) {
      const v = entry(def, key);
      if (!v) this.missing(what, key, def.source);
      else if (v.kind === 'id') this.schemaRef(what, key, v);
      else this.shape(what, key, 'a `def schema` name', v);
    }
    const timeout = entry(def, 'timeout');
    if (timeout) this.duration(what, 'timeout', timeout);
    const budget = entry(def, 'budget');
    if (budget) {
      const perRun = objEntry(budget, 'per_run');
      if (!perRun || !isPositiveInt(perRun)) this.shape(what, 'budget', '`{ per_run: <int> }`', budget);
    }
    const write = entry(def, 'write');
    if (write) this.bool(what, 'write', write);
    const idem = entry(def, 'idempotency');
    if (idem) this.enumId(what, 'idempotency', idem, new Set(['row']));
  }

  private approval(def: AutomationDef, what: string): void {
    if (!APPROVAL_KINDS.has(def.name)) {
      this.report('ttr/automation-invalid-value', `${what}: an approval is named by its kind (${[...APPROVAL_KINDS].join(', ')})`, def.source);
    }
    const by = entry(def, 'by');
    if (!by) this.missing(what, 'by', def.source);
    else this.roleRef(what, 'by', by);
    const quorum = entry(def, 'quorum');
    if (quorum && !isPositiveInt(quorum)) this.shape(what, 'quorum', 'a positive integer', quorum);
  }

  private program(def: AutomationDef, what: string): void {
    const recipe = entry(def, 'recipe');
    if (!recipe) this.missing(what, 'recipe', def.source);
    else if (recipe.kind === 'id') {
      if (!this.ix.has('recipe', recipe.path)) {
        this.report('ttr/automation-recipe-precatalog', `${what}: recipe '${recipe.path}' is not in the catalog yet (accepted by name)`, recipe.source, 'info');
      }
    } else this.shape(what, 'recipe', 'a recipe name', recipe);
    const port = entry(def, 'port');
    if (!port) this.missing(what, 'port', def.source);
    else this.enumId(what, 'port', port, PORTS);
    const event = entry(def, 'event');
    const isEventPort = idPath(port) === 'event';
    if (isEventPort && !event) this.report('ttr/automation-event-port-mismatch', `${what}: \`port: event\` needs an \`event:\` schema`, def.source);
    if (event && !isEventPort) this.report('ttr/automation-event-port-mismatch', `${what}: \`event:\` is only read with \`port: event\``, event.source);
    if (event) {
      if (event.kind === 'id') this.schemaRef(what, 'event', event);
      else this.shape(what, 'event', 'a `def schema` name', event);
    }
    const mode = entry(def, 'mode');
    if (!mode) this.missing(what, 'mode', def.source);
    else this.enumId(what, 'mode', mode, MODES);
    const asOf = entry(def, 'as_of');
    if (asOf && asOf.kind !== 'string' && asOf.kind !== 'tripleString') this.enumId(what, 'as_of', asOf, new Set(['run', 'event']));
    const deliver = entry(def, 'deliver');
    if (deliver) {
      const digest = objEntry(deliver, 'digest');
      if (idPath(deliver) === 'immediate') {
        // ok
      } else if (digest && digest.kind === 'autoObject') {
        const per = entry(digest, 'per');
        if (per) this.enumId(what, 'deliver.digest.per', per, DIGEST_PER);
        else this.missing(what, 'deliver.digest.per', deliver.source);
        if (!entry(digest, 'by')) this.missing(what, 'deliver.digest.by', deliver.source);
      } else this.shape(what, 'deliver', '`immediate` or `{ digest: { by: …, per: day | week } }`', deliver);
    }
    const schedule = entry(def, 'schedule');
    if (schedule && schedule.kind !== 'string' && schedule.kind !== 'tripleString') this.shape(what, 'schedule', 'a string', schedule);
    const overrides = entry(def, 'overrides');
    if (overrides) {
      if (overrides.kind === 'autoObject') {
        for (const e of overrides.entries) {
          if (!this.ix.has('parameter', e.key)) {
            this.report('ttr/automation-unknown-reference', `${what}: overrides '${e.key}', which is no \`def parameter\``, e.source, 'warning');
          }
        }
      } else this.shape(what, 'overrides', 'an object of parameter values', overrides);
    }
    const budget = entry(def, 'budget');
    if (budget) {
      if (budget.kind === 'autoObject') {
        for (const e of budget.entries) {
          if (e.key === 'sync' || e.key === 'batch') this.duration(what, `budget.${e.key}`, e.value);
          else this.unknownKey(what, `budget.${e.key}`, e.source);
        }
      } else this.shape(what, 'budget', '`{ sync: <duration>, batch: <duration> }`', budget);
    }
    const turnBudget = entry(def, 'turn_budget');
    if (turnBudget) {
      if (turnBudget.kind === 'autoObject') {
        for (const e of turnBudget.entries) {
          if (e.key === 'per_run' || e.key === 'per_candidate') {
            if (!isPositiveInt(e.value)) this.shape(what, `turn_budget.${e.key}`, 'a positive integer', e.value);
          } else this.unknownKey(what, `turn_budget.${e.key}`, e.source);
        }
      } else this.shape(what, 'turn_budget', '`{ per_run: <int>, per_candidate: <int> }`', turnBudget);
    }
    const askDue = entry(def, 'ask_due');
    if (askDue) this.duration(what, 'ask_due', askDue);
    const protocol = entry(def, 'protocol');
    if (protocol) this.enumId(what, 'protocol', protocol, PROTOCOLS);
  }

  // ----- the automation role -----

  role(def: RoleDef): void {
    const what = `role '${def.name}'`;
    const entries = def.entries ?? [];
    for (const e of entries) if (!KNOWN.role.has(e.key)) this.unknownKey(what, e.key, e.source);
    const keys = new Set(entries.map((e) => e.key));
    if (keys.has('resolve') === keys.has('members')) {
      this.report('ttr/automation-role-resolve', `${what}: declare exactly one of \`resolve:\` and \`members:\``, def.source);
    }
    const body = { entries };
    const resolve = entry(body, 'resolve');
    if (resolve && !(resolve.kind === 'tagged' && resolve.tag === 'keycloak_group')) {
      this.shape(what, 'resolve', '`keycloak_group "<group>"`', resolve);
    }
    const members = entry(body, 'members');
    if (members && !(members.kind === 'autoList' && members.items.every((i) => i.kind === 'string' || i.kind === 'tripleString'))) {
      this.shape(what, 'members', 'a list of strings', members);
    }
    const delivery = entry(body, 'delivery');
    if (delivery && delivery.kind !== 'autoObject') this.shape(what, 'delivery', 'an object `{ email: … }`', delivery);
  }

  // ----- the world members -----

  world(world: WorldDef): void {
    const executors = new Set(world.executors.map((e) => e.name));
    for (const m of world.identities ?? []) {
      const what = `identity '${m.name}'`;
      this.commonEntries('identity', what, m);
      const principal = entry(m, 'principal');
      if (!principal || (principal.kind !== 'string' && principal.kind !== 'tripleString')) this.missingOr(what, 'principal', 'a string', principal, m.source);
      const credential = entry(m, 'credential');
      if (!credential || credential.kind !== 'id') this.missingOr(what, 'credential', 'a connection name', credential, m.source);
    }
    const seen = new Set<string>();
    for (const g of world.grants ?? []) {
      this.grant(g);
      const triple = ['who', 'program', 'as'].map((k) => {
        const v = entry(g, k);
        return v ? render(v) : '?';
      }).join(' / ');
      if (seen.has(triple)) this.report('ttr/automation-duplicate-grant', `world '${world.name}': grant (${triple}) is declared twice`, g.source);
      seen.add(triple);
    }
    for (const d of world.deployments ?? []) this.deployment(d, executors);
    for (const p of world.processes ?? []) this.process(p);
    for (const storage of world.storages) {
      const writable = storage.manifest['writable'];
      if (!writable) continue;
      const items = writable.kind === 'list' ? writable.items : [writable];
      // `<entity>` or `<entity>.<attribute>` (a string is a glob — not checked).
      for (const item of items) {
        if (item.kind !== 'id') continue;
        const parts = item.parts;
        const owner = parts.length >= 2 ? this.ix.entities.get(parts[parts.length - 2]) : undefined;
        let problem: string | undefined;
        if (this.ix.entities.has(parts[parts.length - 1])) problem = undefined;
        else if (!owner) problem = 'names no entity of the project';
        else if (!(owner.attributes ?? []).some((a) => a.name === parts[parts.length - 1])) problem = `names no attribute of '${owner.name}'`;
        if (problem) {
          this.report('ttr/automation-writable-unknown-entity', `storage '${storage.name}': writable '${item.path}' ${problem}`, item.source);
        }
      }
    }
  }

  private grant(g: AutoBody & { source: SourceLocation }): void {
    const what = 'grant';
    this.commonEntries('grant', what, g);
    const whoShape = '`role.<r>`, `identity.<i>`, `user.<u>` or "*"';
    const who = entry(g, 'who');
    if (!who) this.missing(what, 'who', g.source);
    else if (who.kind === 'string' || who.kind === 'tripleString') {
      if (who.value !== '*') this.shape(what, 'who', whoShape, who);
    } else if (who.kind === 'id') {
      const head = who.parts[0];
      if (head === 'role') this.roleRef(what, 'who', who);
      else if (head === 'identity') this.identityRef(what, 'who', who);
      else if (head !== 'user') this.shape(what, 'who', whoShape, who);
    } else this.shape(what, 'who', whoShape, who);
    const program = entry(g, 'program');
    if (!program) this.missing(what, 'program', g.source);
    else if (program.kind === 'star') {
      // any program
    } else if (program.kind === 'id') {
      if (!this.ix.has('program', program.path)) this.unknownRef(what, 'program', 'program', program.path, program.source);
    } else this.shape(what, 'program', 'a program name or `*`', program);
    const asV = entry(g, 'as');
    if (!asV) this.missing(what, 'as', g.source);
    else if (asV.kind === 'id') {
      if (asV.path !== 'author') {
        if (asV.parts[0] === 'identity') this.identityRef(what, 'as', asV);
        else this.shape(what, 'as', '`identity.<n>` or `author`', asV);
      }
    } else this.shape(what, 'as', '`identity.<n>` or `author`', asV);
  }

  private deployment(d: AutoBody & { name: string; source: SourceLocation }, executors: Set<string>): void {
    const what = `deployment '${d.name}'`;
    this.commonEntries('deployment', what, d);
    const program = entry(d, 'program');
    const process = entry(d, 'process');
    if ((program === undefined) === (process === undefined)) {
      this.report('ttr/automation-deployment-program-and-process', `${what}: declare exactly one of \`program:\` and \`process:\``, d.source);
    }
    if (program) {
      if (program.kind === 'id') {
        if (!this.ix.has('program', program.path)) this.unknownRef(what, 'program', 'program', program.path, program.source);
      } else this.shape(what, 'program', 'a program name', program);
    }
    if (process) {
      if (process.kind === 'id') {
        if (!this.ix.processes.has(process.path)) this.unknownRef(what, 'process', 'process', process.path, process.source);
      } else this.shape(what, 'process', 'a process name', process);
    }
    const target = d.target;
    if (!target) this.missing(what, 'target', d.source);
    else if ('path' in target) {
      if (target.path !== 'native' && !executors.has(target.path)) this.unknownRef(what, 'target', 'executor', target.path, target.source);
    } else this.report('ttr/automation-invalid-value', `${what}: target is \`native\` or an executor name`, target.source);
    const runAs = entry(d, 'run_as');
    if (!runAs) this.missing(what, 'run_as', d.source);
    else if (runAs.kind === 'id') {
      if (runAs.path !== 'caller' && runAs.path !== 'author') {
        if (runAs.parts[0] === 'identity') this.identityRef(what, 'run_as', runAs);
        else this.shape(what, 'run_as', '`caller`, `author` or `identity.<n>`', runAs);
      }
    } else this.shape(what, 'run_as', '`caller`, `author` or `identity.<n>`', runAs);
    const trigger = entry(d, 'trigger');
    if (trigger && !((trigger.kind === 'tagged' && trigger.tag === 'schedule') || idPath(trigger) === 'none')) {
      this.shape(what, 'trigger', '`schedule "<cron or HH:MM TZ>"` or `none`', trigger);
    }
    const granularity = entry(d, 'granularity');
    if (granularity) this.enumId(what, 'granularity', granularity, GRANULARITIES);
    for (const flag of DEPLOYMENT_FLAGS) {
      const v = entry(d, flag);
      if (v) this.bool(what, flag, v);
    }
    const ceiling = entry(d, 'read_ceiling');
    if (ceiling && !isPositiveInt(ceiling)) this.shape(what, 'read_ceiling', 'a positive integer', ceiling);
  }

  private process(p: ProcessDef): void {
    const what = `process '${p.name}'`;
    for (const e of p.entries) if (!KNOWN.process.has(e.key)) this.unknownKey(what, e.key, e.source);
    const instance = entry(p, 'instance');
    if (!instance) this.missing(what, 'instance', p.source);
    else if (instance.kind === 'id') this.attributeRef(`${what} instance`, instance.path, instance.source);
    else this.shape(what, 'instance', '`<entity>.<attribute>`', instance);
    if (p.steps.length === 0) this.missing(what, 'steps', p.source);
    for (const step of p.steps) {
      const sw = `${what} step '${step.name}'`;
      this.commonEntries('step', sw, step);
      const program = entry(step, 'program');
      if (!program) this.missing(sw, 'program', step.source);
      else if (program.kind === 'id') {
        if (!this.ix.has('program', program.path)) this.unknownRef(sw, 'program', 'program', program.path, program.source);
      } else this.shape(sw, 'program', 'a program name', program);
      const port = entry(step, 'port');
      if (!port) this.missing(sw, 'port', step.source);
      else this.enumId(sw, 'port', port, STEP_PORTS);
      const after = entry(step, 'after');
      if (after) {
        if (after.kind !== 'autoObject') this.shape(sw, 'after', '`{ obligation: …, time: …, until: [ … ] }`', after);
        else {
          for (const e of after.entries) {
            if (e.key === 'obligation') continue;
            if (e.key === 'time') this.duration(sw, 'after.time', e.value);
            else if (e.key === 'until') {
              const items = e.value.kind === 'autoList' ? e.value.items : [e.value];
              for (const i of items) this.enumId(sw, 'after.until', i, UNTIL);
            } else this.unknownKey(sw, `after.${e.key}`, e.source);
          }
        }
      }
    }
  }

  // ----- attribute lifecycle (D-67) -----

  lifecycle(entity: EntityDef, attr: AttributeDef): void {
    const transitions = attr.transitions ?? [];
    if (transitions.length === 0) return;
    const name = `${entity.name}.${attr.name}`;
    if ((attr.states?.length ?? 0) === 0) {
      this.report('ttr/states-transitions-without-states', `${name} declares \`transitions:\` without \`states:\``, attr.source, 'warning');
      return;
    }
    const states = new Set(attr.states);
    for (const t of transitions) {
      for (const s of [t.from, ...t.to]) {
        if (!states.has(s)) this.report('ttr/states-unknown-state', `${name}: transition names '${s}', which is not in its states`, t.source);
      }
    }
  }

  // ----- shared checks -----

  /** Unknown free-form keys, and a typed property that fell through to a free-form entry. */
  private commonEntries(kind: string, what: string, body: { entries: { key: string; source: SourceLocation }[] }): void {
    const known = KNOWN[kind] ?? new Set<string>();
    for (const e of body.entries) {
      if (e.key === 'transition' || e.key === 'set') {
        const form = e.key === 'set' ? '<entity>.<attribute> = <column>' : '<entity>.<attribute> -> <state>';
        this.report('ttr/automation-malformed-property', `${what}: \`${e.key}:\` is \`${form}\``, e.source);
      } else if (!known.has(e.key)) this.unknownKey(what, e.key, e.source);
    }
  }

  private attributeRef(what: string, path: string, source: SourceLocation): { entity?: EntityDef; attr?: AttributeDef } {
    const found = this.ix.attribute(path);
    if (!found.entity) this.report('ttr/automation-unknown-attribute', `${what}: '${path}' names no entity of the project`, source, 'warning');
    else if (!found.attr) {
      this.report('ttr/automation-unknown-attribute', `${what}: '${found.entity.name}' has no attribute '${path.split('.').pop()}'`, source);
    }
    return found;
  }

  private schemaRef(what: string, key: string, v: Extract<AutoValue, { kind: 'id' }>): void {
    const name = v.parts.length === 2 && v.parts[0] === 'schema' ? v.parts[1] : v.path;
    if (!this.ix.schemas.has(name)) this.report('ttr/automation-event-not-schema', `${what}: ${key} '${v.path}' is no \`def schema\``, v.source);
  }

  private roleRef(what: string, key: string, v: AutoValue): void {
    if (v.kind !== 'id' || v.parts.length !== 2 || v.parts[0] !== 'role') {
      this.shape(what, key, '`role.<name>`', v);
      return;
    }
    if (!this.ix.roles.has(v.parts[1])) this.unknownRef(what, key, 'role', v.parts[1], v.source);
  }

  private identityRef(what: string, key: string, v: Extract<AutoValue, { kind: 'id' }>): void {
    if (v.parts.length !== 2 || !this.ix.identities.has(v.parts[1])) {
      this.unknownRef(what, key, 'identity', v.parts.slice(1).join('.'), v.source);
    }
  }

  private duration(what: string, key: string, v: AutoValue): void {
    if (v.kind !== 'duration') this.shape(what, key, 'a duration (`2 working_days`)', v);
    else if (!DURATION_UNITS.has(v.unit)) this.invalid(what, `${key} unit`, v.unit, DURATION_UNITS, v.source);
  }

  private bool(what: string, key: string, v: AutoValue): void {
    if (v.kind !== 'bool') this.shape(what, key, '`true` or `false`', v);
  }

  private enumId(what: string, key: string, v: AutoValue, allowed: Set<string>): void {
    const id = idPath(v);
    if (id === undefined || !allowed.has(id)) this.invalid(what, key, render(v), allowed, v.source);
  }

  private missing(what: string, key: string, source: SourceLocation): void {
    this.report('ttr/automation-missing-property', `${what}: \`${key}:\` is required`, source);
  }

  private missingOr(what: string, key: string, expected: string, v: AutoValue | undefined, source: SourceLocation): void {
    if (!v) this.missing(what, key, source);
    else this.shape(what, key, expected, v);
  }

  private shape(what: string, key: string, expected: string, v: AutoValue): void {
    this.report('ttr/automation-invalid-value', `${what}: \`${key}:\` must be ${expected}, not ${render(v)}`, v.source);
  }

  private invalid(what: string, key: string, value: string, allowed: Set<string>, source: SourceLocation): void {
    this.report('ttr/automation-invalid-value', `${what}: ${key} '${value}' is not one of ${[...allowed].join(', ')}`, source);
  }

  private unknownKey(what: string, key: string, source: SourceLocation): void {
    this.report('ttr/automation-unknown-property', `${what}: unknown property \`${key}:\``, source, 'warning');
  }

  private unknownRef(what: string, key: string, kind: string, name: string, source: SourceLocation): void {
    this.report('ttr/automation-unknown-reference', `${what}: ${key} names no ${kind} '${name}'`, source);
  }
}

function valueFits(type: string, v: AutoValue): boolean {
  switch (type) {
    case 'decimal':
    case 'int':
    case 'days':
    case 'working_days':
      return v.kind === 'number';
    case 'bool':
      return v.kind === 'bool';
    default:
      return v.kind === 'string' || v.kind === 'tripleString';
  }
}

function isPositiveInt(v: AutoValue): boolean {
  return v.kind === 'number' && v.value >= 1 && Number.isInteger(v.value);
}
