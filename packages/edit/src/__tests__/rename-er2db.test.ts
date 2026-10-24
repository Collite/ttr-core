// SPDX-License-Identifier: Apache-2.0
import { expect, it } from 'vitest';
import { buildRenameSymbolEdit } from '../rename-symbol.js';
import { applyWorkspaceEditToText } from '../lint-fixes.js';
it('renames all six ER binding references, preserving member suffixes and trivia (#213)', () => {
  const uri = 'file:///repo/model/er/returns.ttrm';
  const bindingUri = 'file:///repo/model/binding/er2db.ttrm';
  const content = 'package hartland\nmodel er\ndef entity return_request {}\n';
  const binding = `package hartland
model binding
// er.entity.return_request must stay in this comment
` + ['entity: er.entity.return_request', ...['id', 'customer', 'state', 'amount', 'created'].map(a => 'attribute: er.entity.return_request.' + a)]
    .map((slot, i) => `def ${i ? 'er2db_attribute' : 'er2db_entity'} b${i} { ${slot}, target: { ${i ? 'column' : 'table'}: db.dbo.requests${i ? '.c' + i : ''} } }`).join('\n');
  const edit = buildRenameSymbolEdit({ oldQname: 'hartland.er.entity.return_request', newBareName: 'return_case',
    defEntry: { qname: 'hartland.er.entity.return_request', name: 'return_request', kind: 'entity',
      documentUri: uri, packageName: 'hartland', schemaCode: 'er', source: { file: uri, line: 3, column: 11,
        endLine: 3, endColumn: 25, offsetStart: 0, offsetEnd: 0 } },
    defDocumentContent: content, references: [], ttrgDocuments: new Map([[uri, content], [bindingUri, binding]]) });
  const changed = applyWorkspaceEditToText(binding, edit, bindingUri);
  expect(changed.match(/er\.entity\.return_case/g)).toHaveLength(6);
  expect(changed).toContain('// er.entity.return_request must stay');
  expect(changed).toContain('attribute: er.entity.return_case.customer');
});
