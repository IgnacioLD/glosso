// The web app's service worker: the app shell loads without a connection,
// and the app then shows its own offline state. Only this origin's files are
// handled; the API is on another origin and always goes to the network.
// Network first, so a deploy is picked up on the next load; the cache is
// only the fallback. One cache per release (shared/build.gradle.kts fills
// the version in); older releases' caches are dropped when this one activates.
const CACHE = 'glosso-shell-__VERSION__';

self.addEventListener('install', () => self.skipWaiting());

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    // Only this worker's own caches: Compose keeps its resources in one of its own.
    for (const name of await caches.keys()) {
      if (name.startsWith('glosso-shell-') && name !== CACHE) await caches.delete(name);
    }
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET' || new URL(request.url).origin !== self.location.origin) return;
  event.respondWith((async () => {
    const cache = await caches.open(CACHE);
    try {
      const response = await fetch(request);
      if (response.ok) cache.put(request, response.clone());
      return response;
    } catch (error) {
      const cached = await cache.match(request, { ignoreSearch: request.mode === 'navigate' });
      if (cached) return cached;
      throw error;
    }
  })());
});
