// SPDX-License-Identifier: Apache-2.0
import type { FrameMessage } from './host-channel.js';
/** Frame transport and props callbacks implement the same outward notification surface. */
export interface Host {
  send(message: FrameMessage): void;
  dispose(): void;
}
