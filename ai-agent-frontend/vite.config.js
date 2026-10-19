import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const proxy = {
    '/api': {
      target: env.BACKEND_PROXY_TARGET || 'http://localhost:8124',
      changeOrigin: true,
      // 智能体可执行较长时间，不让代理提前终止流。
      timeout: 0,
      proxyTimeout: 0,
    },
  }
  return {
    plugins: [vue(), {
      name: 'huiling-offline-shell',
      apply: 'build',
      generateBundle(options, bundle) {
        const assets = ['/index.html', '/favicon.svg', ...Object.keys(bundle).filter((name) => /\.(js|css)$/.test(name)).map((name) => '/' + name)]
        const revision = assets.find((name) => name.endsWith('.js')).replace(/\W/g, '')
        this.emitFile({ type: 'asset', fileName: 'sw.js', source: `
const CACHE = 'huiling-shell-${revision}';
const ASSETS = ${JSON.stringify(assets)};
self.addEventListener('install', event => event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(ASSETS)).then(() => self.skipWaiting())));
self.addEventListener('activate', event => event.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(key => key.startsWith('huiling-shell-') && key !== CACHE).map(key => caches.delete(key)))).then(() => self.clients.claim())));
self.addEventListener('fetch', event => {
  const url = new URL(event.request.url);
  if (event.request.method !== 'GET' || url.origin !== self.location.origin) return;
  if (event.request.mode === 'navigate' && (url.pathname === '/' || url.pathname === '/index.html')) {
    event.respondWith(fetch(event.request).then(response => { if (response.ok) { const copy = response.clone(); caches.open(CACHE).then(cache => cache.put('/index.html', copy)); } return response; }).catch(() => caches.match('/index.html')));
  } else if (ASSETS.includes(url.pathname)) {
    event.respondWith(caches.match(event.request, { ignoreVary: true }).then(cached => cached || fetch(event.request)));
  }
});
` })
      },
    }],
    server: { port: 5173, strictPort: true, proxy },
    preview: { port: 4173, strictPort: true, proxy },
  }
})
