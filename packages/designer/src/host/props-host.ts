// SPDX-License-Identifier: Apache-2.0
import type { Host } from './host.js';
import type { FrameMessage } from './host-channel.js';
export interface SnapshotInstalled { sha: string; changed: number }
export interface DesignerReady { sha: string; manifest: { bindings: { required: boolean } } }
export interface DesignerDiagnostic { uri: string; severity: string; code: string; message: string; source: string }
export interface DesignerError { code: string; message: string; sha?: string }
export interface PropsHostCallbacks {
  /** First successfully installed worker/catalog; delivered after the shell commits. */
  onReady?(ready: DesignerReady): void;
  /** Every successful snapshot input, including an idempotent same-SHA retry. */
  onSnapshot?(snapshot: SnapshotInstalled): void;
  /** Complete validated worker set; replace previous diagnostics, including with []. */
  onDiagnostics?(diagnostics: DesignerDiagnostic[]): void;
  onLocation?(location: string): void;
  onDirty?(changed: number): void;
  onError?(error: DesignerError): void;
}
/** No postMessage, browser history or credential state in a props host. */
export class PropsHost implements Host {
  private disposed = false;
  constructor(private callbacks: () => PropsHostCallbacks) {}
  send(message: FrameMessage): void {
    if (this.disposed) return;
    const callbacks = this.callbacks();
    if (message.type === 'ttr.frame.location') callbacks.onLocation?.(message.location);
    if (message.type === 'ttr.frame.dirty') callbacks.onDirty?.(message.changed);
    if (message.type === 'ttr.frame.ready' && message.sha) callbacks.onReady?.({ sha: message.sha, manifest: message.manifest ?? { bindings: { required: true } } });
    if (message.type === 'ttr.frame.ack') callbacks.onSnapshot?.({ sha: message.sha, changed: message.changed });
    if (message.type === 'ttr.frame.diagnostics') callbacks.onDiagnostics?.(message.diagnostics);
    if (message.type === 'ttr.frame.error') callbacks.onError?.({ code: message.code, message: message.message, ...(message.sha ? { sha: message.sha } : {}) });
  }
  dispose(): void { this.disposed = true; }
}
