// Shell-only cache. Never cache sensitive screenshots or API responses.
'use strict';
const CACHE = 'wasp-control-shell-v1';
self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(['/manifest.webmanifest','/icon.svg'])));
  self.skipWaiting();
});
self.addEventListener('activate', event => {
  event.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(key => key !== CACHE).map(key => caches.delete(key)))));
  self.clients.claim();
});
self.addEventListener('fetch', event => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin || url.pathname.startsWith('/api/') || url.pathname === '/' || url.pathname === '/index.html') return;
  if (url.pathname !== '/icon.svg' && url.pathname !== '/manifest.webmanifest') return;
  event.respondWith(caches.match(req).then(cached => cached || fetch(req)));
});
