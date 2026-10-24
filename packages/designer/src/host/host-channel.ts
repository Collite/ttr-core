// SPDX-License-Identifier: Apache-2.0
import type { Host } from './host.js';
/** Stage-1 Studio protocol. Bearers live only in memory, never in the iframe URL. */
export type HostScope = { kind: 'served' } | { kind: 'change'; id: string };
export interface HostInit {
  type: 'ttr.host.init';
  basePath: string;
  workspace: string;
  api: string;
  token: string;
  scope: HostScope;
  lang: 'en' | 'cs';
  theme: 'light';
  sha?: string;
}
export type HostMessage =
  | HostInit
  | { type: 'ttr.host.token'; token: string }
  | { type: 'ttr.host.navigate'; location: string; sha?: string; discard?: boolean }
  | { type: 'ttr.host.refresh'; discard?: boolean }
  | { type: 'ttr.host.collect' }; // implemented with the P1 save seam
export type FrameMessage =
  | { type: 'ttr.frame.ready' }
  | { type: 'ttr.frame.location'; location: string }
  | { type: 'ttr.frame.error'; code: string; message: string }
  | { type: 'ttr.frame.dirty'; changed: number }
  | { type: 'ttr.frame.changedFiles'; files: Array<{ path: string; text: string | null }> };

export function isHosted(): boolean {
  return (
    typeof window !== 'undefined' &&
    new URLSearchParams(window.location.search).get('backend') === 'host'
  );
}

/** Same-origin absolute paths only, including after URL normalization. */
export function isHostPath(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^\/(?!\/)[A-Za-z0-9._~/-]*$/.test(value) &&
    !value.split('/').some((p) => p === '.' || p === '..')
  );
}

function parseMessage(value: unknown): HostMessage | null {
  if (!value || typeof value !== 'object') return null;
  const m = value as Record<string, unknown>;
  switch (m.type) {
    case 'ttr.host.init': {
      const s = m.scope as Record<string, unknown> | undefined;
      return isHostPath(m.api) &&
        isHostPath(m.basePath) &&
        m.basePath.endsWith('/') &&
        typeof m.workspace === 'string' && m.workspace.trim().length > 0 && !m.workspace.includes('/') &&
        typeof m.token === 'string' &&
        (m.lang === 'en' || m.lang === 'cs') &&
        m.theme === 'light' &&
        (m.sha === undefined || typeof m.sha === 'string') &&
        s &&
        (s.kind === 'served' ||
          (s.kind === 'change' && typeof s.id === 'string' && s.id.length > 0))
        ? (value as HostInit)
        : null;
    }
    case 'ttr.host.token':
      return typeof m.token === 'string' ? (value as HostMessage) : null;
    case 'ttr.host.navigate':
      return typeof m.location === 'string' &&
        /^#?\/w\//.test(m.location) &&
        (m.sha === undefined || typeof m.sha === 'string') &&
        (m.discard === undefined || typeof m.discard === 'boolean')
        ? (value as HostMessage)
        : null;
    case 'ttr.host.refresh':
      return m.discard === undefined || typeof m.discard === 'boolean' ? value as HostMessage : null;
    case 'ttr.host.collect':
      return value as HostMessage;
    default:
      return null;
  }
}

/** Ordered async work; token updates bypass the queue so the next fetch sees the latest bearer. */
export class HostChannel implements Host {
  private pending = Promise.resolve();
  private disposed = false;
  constructor(private readonly handle: (message: HostMessage) => void | Promise<void>) {
    if (isHosted()) window.addEventListener('message', this.receive);
  }
  private receive = (event: MessageEvent) => {
    if (event.source !== window.parent || event.origin !== window.location.origin) return;
    const message = parseMessage(event.data);
    if (!message) return;
    if (message.type === 'ttr.host.token' || message.type === 'ttr.host.collect') {
      void this.handle(message);
      return;
    }
    this.pending = this.pending
      .then(async () => {
        if (!this.disposed) await this.handle(message);
      })
      .catch(() => {
        // Keep credentials / server response bodies out of the host-visible error.
        this.send({
          type: 'ttr.frame.error',
          code: 'host/load-failed',
          message: 'Could not load the model snapshot.',
        });
      });
  };
  send(message: FrameMessage): void {
    if (!this.disposed && isHosted()) window.parent.postMessage(message, window.location.origin);
  }
  dispose(): void {
    this.disposed = true;
    window.removeEventListener('message', this.receive);
  }
}
