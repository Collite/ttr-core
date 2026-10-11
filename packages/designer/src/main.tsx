import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import { loadRuntimeConfig, setRuntimeConfig } from './runtime-config';
import './index.css';

// The runtime config (base path, catalog, language — ttr-core#169) is read ONCE, before the first
// render: backend selection and every address-bar rewrite depend on it. A missing file is fine.
void loadRuntimeConfig().then((config) => {
  setRuntimeConfig(config);
  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>
  );
});
