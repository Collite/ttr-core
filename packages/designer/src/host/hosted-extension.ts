// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import * as jsx from 'react/jsx-runtime';
import type { AuthoringLoader } from '../ext/authoring-registry.js';
import type { HostInit } from './host-channel.js';
interface HostedModule { activate(context: Parameters<AuthoringLoader>[0] & { bindingsRequired: boolean }): ReturnType<AuthoringLoader> }
/** The commercial file is supplied by Studio. The open image contains only this fixed hook. */
export async function hostedAuthoring(init: HostInit, bindingsRequired: boolean,
  load: (url: string) => Promise<HostedModule> = url => import(/* @vite-ignore */ url)) : Promise<AuthoringLoader | null> {
  if (init.scope.kind !== 'change' || !init.features?.includes('modeler') || !init.extensions?.includes('authoring')) return null;
  // Share the frame's React dispatcher rather than bundling a second React into the extension.
  (globalThis as typeof globalThis & { __ttrExtensionRuntime?: unknown }).__ttrExtensionRuntime = { react: React, jsx };
  const module = await load('/m/_designer/ext/authoring.js');
  return context => module.activate({ ...context, bindingsRequired });
}
