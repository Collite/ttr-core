// SPDX-License-Identifier: Apache-2.0
import { expect, it, vi } from 'vitest';
import { HostChannel } from '../host-channel.js';
it.each(['refresh','navigate'])('accepts only boolean discard on %s',async type=>{
 window.history.replaceState(null,'','/?backend=host');
 const handle=vi.fn(),channel=new HostChannel(handle);
 const message={type:`ttr.host.${type}`,...(type==='navigate'?{location:'/w/world/s/er',sha:'two'}:{})};
 const post=(discard:unknown)=>window.dispatchEvent(new MessageEvent('message',{data:{...message,discard},origin:window.location.origin,source:window.parent}));
 try {
  post('true');post(1);post({});expect(handle).not.toHaveBeenCalled();
  post(false);post(true);await vi.waitFor(()=>expect(handle).toHaveBeenCalledTimes(2));
  expect(handle.mock.calls.map(([value])=>value.discard)).toEqual([false,true]);
 }finally {channel.dispose();window.history.replaceState(null,'','/');}
});
