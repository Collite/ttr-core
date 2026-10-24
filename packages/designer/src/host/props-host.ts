// SPDX-License-Identifier: Apache-2.0
import type { Host } from './host.js';
import type { FrameMessage } from './host-channel.js';
export interface PropsHostCallbacks {
  onLocation?(location: string): void;
  onDirty?(changed: number): void;
  onError?(error: { code: string; message: string }): void;
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
    if (message.type === 'ttr.frame.error') callbacks.onError?.({ code: message.code, message: message.message });
  }
  dispose(): void { this.disposed = true; }
}
