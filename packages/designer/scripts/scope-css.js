// SPDX-License-Identifier: Apache-2.0
/** Scope dependency CSS too (React Flow), preserving keyframe selectors. */
export default function scopeDesignerCss() {
  return {
    postcssPlugin: 'ttr-designer-scope',
    OnceExit(root) {
      const animations = new Map();
      root.walkAtRules(/keyframes$/, rule => {
        const name = rule.params;
        const scoped = `ttr-designer-${name}`;
        animations.set(name, scoped);
        rule.params = scoped;
      });
      root.walkDecls(/^(?:-webkit-)?animation(?:-name)?$/, declaration => {
        declaration.value = declaration.value.replace(/[A-Za-z_][A-Za-z0-9_-]*/g, token => animations.get(token) ?? token);
      });
      root.walkRules(rule => {
        if (rule.parent?.type === 'atrule' && /keyframes$/.test(rule.parent.name)) return;
        rule.selectors = rule.selectors.map(selector => selector.startsWith('.ttr-designer') ? selector : `.ttr-designer ${selector}`);
      });
    },
  };
}
