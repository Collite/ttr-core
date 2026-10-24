import tailwindcss from 'tailwindcss';
import autoprefixer from 'autoprefixer';
import scopeDesignerCss from './scripts/scope-css.js';
export default {
  plugins: [tailwindcss(), autoprefixer(), ...(process.env.DESIGNER_LIBRARY === '1' ? [scopeDesignerCss()] : [])],
};
