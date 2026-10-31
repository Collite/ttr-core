// SPDX-License-Identifier: Apache-2.0
// Restricted n8n node interpreter for trusted exporter output; never sends email or waits on a human.
import vm from 'node:vm';
import { readFileSync } from 'node:fs';
const canonicalNumber = text => {
  let [mantissa, exponent='0']=String(text).toLowerCase().split('e');
  const negative=mantissa.startsWith('-');mantissa=mantissa.replace(/^[+-]/,'');
  const parts=mantissa.split('.');let digits=(parts[0]+(parts[1]||'')).replace(/^0+/,'');
  let power=Number(exponent)-(parts[1]||'').length;
  if(!digits) return '0';
  while(digits.endsWith('0')) {digits=digits.slice(0,-1);power++;}
  return `${negative?'-':''}${digits}e${power}`;
};
const exactParse = text => JSON.parse(text,(_,value,context)=>{
  if(typeof value==='number' && (!Number.isFinite(value) || (Number.isInteger(value) && !Number.isSafeInteger(value)) || !context?.source || canonicalNumber(context.source)!==canonicalNumber(String(value)))) throw Error('unsafe JSON number');
  return value;
});
const config = exactParse(readFileSync(0, 'utf8'));
const { workflow, server, token, input, asOf, mode } = config;
if(config.validateOnly) {
  const expressions=value=>{
    if(typeof value==='string' && value.startsWith('={{')) new vm.Script(`(${value.replace(/^=\{\{\s*/, '').replace(/\s*\}\}$/, '')})`);
    else if(value && typeof value==='object') Object.values(value).forEach(expressions);
  };
  for(const node of workflow.nodes) {
    if(node.type==='n8n-nodes-base.code') new vm.Script(`(function(){${node.parameters.jsCode}\n})()`);
    expressions(node.parameters);
  }
  process.stdout.write('{"valid":true}');
  process.exit(0);
}

const nodes = new Map(workflow.nodes.map(n => [n.name, n]));
const declared = workflow.meta.tatrman.calls;
const opens = [];
const results = new Map();
const runs = new Map();
const loops = new Map();
const queue = [['trigger', [{json:{body:input}}]]];
const counts = new Map();
let currentItems = [];
let itemIndex = 0;
let currentName = '';
const context = vm.createContext({
  $env: {TTR_AUTOMATION_URL:server, TTR_MAIL_FROM:'dry@example.invalid', TTR_MAIL_RECIPIENTS:'{}'},
  $execution: {id:config.executionId || 'dry-replay', resumeUrl:'https://dry.invalid/resume'},
  $: name => ({
    isExecuted:results.has(name),
    first: () => {if (!results.has(name)) throw Error('node has not executed'); return results.get(name)[0];},
    all: () => results.get(name) || [],
    get item() {const list=results.get(name); if(!list) throw Error('node has not executed'); return list[Math.min(itemIndex,list.length-1)];},
  }),
});
const evaluate = (code, expression=false) => {
  context.$input = {all:()=>currentItems, first:()=>currentItems[0]};
  context.$json = currentItems[itemIndex]?.json;
  context.$runIndex = (counts.get(currentName)||1)-1;
  const source = expression ? `(${code.replace(/^=\{\{\s*/, '').replace(/\s*\}\}$/, '')})` : `(function(){${code}\n})()`;
  return new vm.Script(source).runInContext(context, {timeout:1000});
};
const booleanCondition = condition => {
  const values = condition.conditions.map(c => {
    const left = evaluate(c.leftValue, true);
    if(c.operator.type==='boolean' && c.operator.operation==='true') return left === true;
    throw Error('unsupported condition');
  });
  return condition.combinator === 'or' ? values.some(Boolean) : values.every(Boolean);
};
const schedule = (name, outputs) => {
  const links=workflow.connections[name]?.main||[];
  outputs.forEach((items,index) => {if(items.length) (links[index]||[]).forEach(link=>queue.push([link.node,items]));});
};
const request = async (node) => {
  const call=declared.find(c=>c.node===node.name);
  if(!call || JSON.stringify(call.parameters)!==JSON.stringify(node.parameters)) throw Error('call declaration differs from HTTP node');
  if(node.credentials?.httpBearerAuth?.name!=='tatrman-automation' || node.retryOnFail!==true || node.maxTries!==3) throw Error('HTTP node credential/retry contract differs');
  const output=[];
  for(itemIndex=0;itemIndex<currentItems.length;itemIndex++) {
    const url=new URL(evaluate(node.parameters.url,true));
    if(url.origin!==new URL(server).origin) throw Error('HTTP node escaped Server origin');
    let body=node.parameters.sendBody ? evaluate(node.parameters.jsonBody,true) : undefined;
    if(call.name==='open') {
      // A replay selects fixture keys and clock, while preserving the rendered deployment and fingerprints.
      body={...body,dryRun:true,asOf,origin:'driver'};
      if(mode==='batch' && config.keys) body.port={keys:config.keys};
      if(mode!=='batch' && call.port==='event') throw Error('event replay needs an event fixture');
    }
    let offset=0;
    let lastResponse;
    let query={};
    const pagination=node.parameters.options?.pagination?.pagination;
    for(let page=0;page<10000;page++) {
      if(call.name==='route') {
        if(!pagination) throw Error('incomplete route pagination');
        context.$pageCount=page;context.$request={qs:query};context.$response=lastResponse;
        for(const parameter of pagination.parameters.parameters) {
          const value=parameter.value.startsWith('={{') ? evaluate(parameter.value,true) : parameter.value;
          query={...query,[parameter.name]:value};url.searchParams.set(parameter.name,String(value));
        }
        if(Number(query.offset)!==offset) throw Error('incomplete route pagination offset');
      }
      const response=await fetch(url,{method:node.parameters.method,headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),redirect:'error',signal:AbortSignal.timeout(30000)});
      if(!response.ok) throw Error(`HTTP ${response.status} at ${call.name}`);
      const text=await response.text();
      // n8n uses JS numbers. Refuse integer keys outside its exact range instead of certifying rounded values.
      const data=exactParse(text);
      if(['part','island'].includes(call.name) && (!Array.isArray(data.rows) || !Number.isInteger(data.rowCount) || typeof data.ref!=='string')) throw Error('invalid part response');
      if(call.name==='evaluate' && response.status!==202 && !data.verdict?.routes) throw Error('invalid evaluate response');
      if(call.name==='perform' && (!Array.isArray(data.performed) || !Array.isArray(data.skipped))) throw Error('invalid perform response');
      if(call.name==='digests' && !Array.isArray(data.groups)) throw Error('invalid digest response');
      if(call.name==='status' && !['open','running','evaluated','waiting','closed','failed','abandoned'].includes(data.status)) throw Error('invalid status response');
      if(call.name==='close' && !['closed','waiting'].includes(data.status)) throw Error('invalid close response');
      if(call.name==='open') {
        if(!data.runId) throw Error('open omitted runId');
        opens.push({recipe:call.recipe,id:data.runId,key:call.key});
      }
      output.push({json:{body:data,statusCode:response.status,headers:Object.fromEntries(response.headers)}});
      if(call.name!=='route') break;
      if(!Array.isArray(data.rows) || !Number.isInteger(data.rowCount)) throw Error('route omitted rows/rowCount');
      offset+=data.rows.length;
      context.$request={qs:query};context.$response={body:data};
      const complete=evaluate(pagination.completeExpression,true);
      if(complete && offset!==data.rowCount) throw Error('incomplete route pagination termination');
      if(offset===data.rowCount) {if(!complete) throw Error('incomplete route pagination termination');break;}
      lastResponse={body:data};
      if(offset>data.rowCount || data.rows.length===0 || page===9999) throw Error('incomplete route paging');
    }
  }
  return output;
};
let error;
try {
  for(let steps=0;queue.length;steps++) {
    if(steps>10000) throw Error('workflow step limit exceeded');
    [currentName,currentItems]=queue.shift();itemIndex=0;
    const node=nodes.get(currentName);
    if(!node) throw Error('connection names an unknown node');
    counts.set(currentName,(counts.get(currentName)||0)+1);
    let outputs;
    switch(node.type.replace('n8n-nodes-base.','')) {
      case 'webhook':case 'scheduleTrigger':outputs=[currentItems];break;
      case 'httpRequest':outputs=[await request(node)];break;
      case 'code':outputs=[evaluate(node.parameters.jsCode)];break;
      case 'if': {
        const yes=[],no=[];
        currentItems.forEach((item,i)=>{itemIndex=i;(booleanCondition(node.parameters.conditions)?yes:no).push(item);});
        outputs=[yes,no];break;
      }
      case 'switch': {
        const rules=node.parameters.rules.values;outputs=Array.from({length:rules.length+1},()=>[]);
        currentItems.forEach((item,i)=>{itemIndex=i;let matched=false;
          for(let r=0;r<rules.length;r++) if(booleanCondition(rules[r].conditions)) {outputs[r].push(item);matched=true;if(!node.parameters.options.allMatchingOutputs) break;}
          if(!matched) outputs[rules.length].push(item);
        });break;
      }
      case 'splitInBatches': {
        let state=loops.get(currentName);
        if(!state) {state={items:currentItems,index:0,done:[]};loops.set(currentName,state);} else state.done.push(...currentItems);
        if(state.index<state.items.length) outputs=[[],[state.items[state.index++]]];
        else {outputs=[state.done,[]];loops.delete(currentName);}
        break;
      }
      case 'splitOut': {
        const field=node.parameters.fieldToSplitOut;const destination=node.parameters.options.destinationFieldName||field;
        outputs=[currentItems.flatMap(item=>item.json[field].map(value=>({json:{[destination]:value}})))];break;
      }
      case 'emailSend': {
        // Validate the actual expressions, but dry replay never sends SMTP.
        outputs=[currentItems.map((item,i)=>{itemIndex=i;for(const field of ['fromEmail','toEmail','text']) evaluate(node.parameters[field],true);return item;})];break;
      }
      case 'wait':outputs=[currentItems];break;
      default:throw Error('unsupported node type');
    }
    const data=outputs.flat();results.set(currentName,data);
    runs.set(currentName,[...(runs.get(currentName)||[]),data]);
    schedule(currentName,outputs);
  }
} catch(e) {
  // Expressions never see bearer credentials; avoid echoing arbitrary body data in diagnostics.
  error=(String(e.message).startsWith('HTTP ') || /^(call declaration|HTTP node|workflow step|unsafe JSON|incomplete route|invalid .* response)/.test(String(e.message))) ? e.message : `dry node failed: ${currentName}`;
}
if(error) {
  for(const open of opens) {
    const call=declared.find(c=>c.recipe===open.recipe && c.name==='close');
    if(call) {try {const url=new URL(server.replace(/\/$/,'')+call.path.replace('{id}',encodeURIComponent(open.id)));if(url.origin!==new URL(server).origin) continue;await fetch(url,{method:'POST',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:'{}',redirect:'error',signal:AbortSignal.timeout(5000)});} catch {}}
  }
}
process.stdout.write(JSON.stringify({opens,error,executed:[...results.keys()]}));
