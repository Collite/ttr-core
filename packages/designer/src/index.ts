// SPDX-License-Identifier: Apache-2.0
import './designer.css';
export { DesignerPage, type DesignerPageProps, type CollectFiles, type AuthoringExtension } from './page/DesignerPage.js';
export type { Host } from './host/host.js';
export type { HostInit, HostScope, HostMessage, FrameMessage } from './host/host-channel.js';
export type { ShellEditContext, AuthoringCommand, EditSlotProps, ProcessingDoorsSlotProps } from './shell/edit-context.js';
export type { Snapshot } from './data/bff-files-data-source.js';
export type { ChangedFile } from './fs/changed-files.js';
