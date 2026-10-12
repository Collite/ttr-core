// SPDX-License-Identifier: Apache-2.0
// ⌘K command registry (DS-P2.S2 / contracts §6). A small ordered registry of Commands, plus a
// structural parity check (E-4/D-6): every toolbar action must have a mirroring ⌘K command.

import type { Command } from './types.js';

export class CommandRegistry {
  private readonly order: string[] = [];
  private readonly byId = new Map<string, Command>();

  /** Register a command, deduped by id. Re-registering an existing id replaces it in place. */
  register(cmd: Command): void {
    if (!this.byId.has(cmd.id)) {
      this.order.push(cmd.id);
    }
    this.byId.set(cmd.id, cmd);
  }

  unregister(id: string): void {
    if (!this.byId.delete(id)) return;
    const idx = this.order.indexOf(id);
    if (idx !== -1) this.order.splice(idx, 1);
  }

  /** All commands in registration order. */
  all(): Command[] {
    return this.order.map((id) => this.byId.get(id)!);
  }

  /** Case-insensitive substring match on title (and group). Empty query returns all(). */
  find(query: string): Command[] {
    const q = query.trim().toLowerCase();
    if (q === '') return this.all();
    return this.all().filter((c) => {
      const haystack = `${c.title} ${c.group ?? ''}`.toLowerCase();
      return haystack.includes(q);
    });
  }

  /** Run the command's run(); no-op if the id is unknown or the command is disabled. */
  execute(id: string): void {
    const cmd = this.byId.get(id);
    if (cmd && !cmd.disabled) cmd.run();
  }
}

/**
 * Parity (E-4/D-6): every toolbar action id must have a command mirroring it. Returns the
 * missing toolbarAction ids (empty ⇒ full parity).
 */
export function commandParityGaps(registry: CommandRegistry, toolbarActionIds: string[]): string[] {
  const covered = new Set(
    registry
      .all()
      .map((c) => c.toolbarAction)
      .filter((ta): ta is string => ta != null),
  );
  return toolbarActionIds.filter((ta) => !covered.has(ta));
}
