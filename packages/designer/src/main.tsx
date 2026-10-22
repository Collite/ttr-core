import React from 'react';
import ReactDOM from 'react-dom/client';
import { flushSync } from 'react-dom';
import App from './App';
import { defaultRuntimeConfig, loadRuntimeConfig, setRuntimeConfig } from './runtime-config';
import { isHosted } from './host/host-channel';
import './index.css';

// The runtime config (base path, catalog, language — ttr-core#169) is read ONCE, before the first
// render: backend selection and every address-bar rewrite depend on it. A missing file is fine.
function mount(config: ReturnType<typeof defaultRuntimeConfig>) {
  setRuntimeConfig(config);
  flushSync(() => ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>
  ));
}
// Install the parent listener before iframe.onload. Hosted configuration comes from init;
// a delayed config.json request would otherwise race the parent's first message.
if (isHosted()) {
  document.documentElement.dataset.hosted = 'true';
  mount(defaultRuntimeConfig());
}
else void loadRuntimeConfig().then(mount);
