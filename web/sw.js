// Service worker: permite abrir la app sin conexión. Los datos los sincroniza Firestore aparte.
const CACHE = 'mibebe-__BUILD__';
const SHELL = ['./', './index.html', './sync.js', './config.js', './manifest.webmanifest',
  './icon-192.png', './icon-512.png', './apple-touch-icon.png'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

// Primero red (con 3 s de margen) para tener siempre la última versión; si no hay, la copia guardada.
self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET' || new URL(req.url).origin !== location.origin) return;
  e.respondWith(new Promise((resolve) => {
    let done = false;
    const fromCache = () => caches.match(req, { ignoreSearch: true })
      .then((r) => r || caches.match('./index.html'));
    const timer = setTimeout(() => { fromCache().then((r) => { if (r && !done) { done = true; resolve(r); } }); }, 3000);
    fetch(req).then((res) => {
      clearTimeout(timer);
      if (res.ok) { const copy = res.clone(); caches.open(CACHE).then((c) => c.put(req, copy)); }
      if (!done) { done = true; resolve(res); }
    }).catch(() => {
      clearTimeout(timer);
      fromCache().then((r) => { if (!done) { done = true; resolve(r || Response.error()); } });
    });
  }));
});
