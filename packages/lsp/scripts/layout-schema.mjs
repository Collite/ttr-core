// SPDX-License-Identifier: Apache-2.0
export default {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  $id: 'https://tatrman.org/schemas/layout/1.json',
  title: 'Tatrman Modeler layout sidecar',
  type: 'object',
  required: ['version', 'nodes', 'edges'],
  additionalProperties: false,
  properties: {
    version: { const: 1 },
    viewport: { $ref: '#/$defs/viewport' },
    nodes: {
      type: 'object',
      patternProperties: {
        '^.+$': {
          type: 'object',
          required: ['x', 'y'],
          additionalProperties: false,
          properties: { x: { type: 'number' }, y: { type: 'number' } },
        },
      },
      additionalProperties: false,
    },
    edges: {
      type: 'object',
      patternProperties: {
        '^.+$': {
          type: 'object',
          required: ['bendPoints'],
          additionalProperties: false,
          properties: {
            bendPoints: {
              type: 'array',
              items: { type: 'array', items: { type: 'number' }, minItems: 2, maxItems: 2 },
            },
          },
        },
      },
      additionalProperties: false,
    },
  },
  $defs: {
    viewport: {
      type: 'object',
      required: ['zoom', 'panX', 'panY', 'displayMode'],
      additionalProperties: false,
      properties: {
        zoom: { type: 'number', exclusiveMinimum: 0 },
        panX: { type: 'number' },
        panY: { type: 'number' },
        displayMode: { enum: ['just-names', 'with-types', 'with-constraints'] },
      },
    },
  },
};
