// SPDX-License-Identifier: Apache-2.0
// The hop edge's paint (VL V-26), shared by the er and db skins: dashed, the hop colour, no marker.

import type { EdgeStyleSpec } from '@tatrman/canvas-core';
import { canvas as palette } from '@tatrman/tokens';

export const HOP_STYLE: EdgeStyleSpec = { stroke: palette.edgeHop, width: 1.6, dash: '6 5', marker: 'none' };
