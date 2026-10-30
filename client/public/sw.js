const CACHE = 'sensoterra-precache-v2'
const DEV_PATHS = ['/src/', '/@vite/', '/@id/', '/@fs/', '/node_modules/']

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE).then((cache) =>
      cache.addAll(['/', '/index.html', '/site.webmanifest', '/favicon.ico', '/favicon-96x96.png', '/favicon.svg', '/apple-touch-icon.png']).catch(() => {})
    )
  )
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) =>
        Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
      )
      .then(() => caches.open(CACHE).then((c) => c.keys().then((ks) =>
        Promise.all(ks
          .filter((k) => k.url.includes('/api/') || k.url.includes('/ws/'))
          .map((k) => c.delete(k)))
      )))
      .then(() => self.clients.claim())
  )
})

self.addEventListener('fetch', (event) => {
  const req = event.request
  if (req.method !== 'GET') return
  const url = new URL(req.url)
  if (url.origin !== location.origin) return
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/ws')) return
  if (DEV_PATHS.some((path) => url.pathname.startsWith(path))) return
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req)
        .then((res) => {
          const copy = res.clone()
          caches.open(CACHE).then((c) => c.put('/index.html', copy))
          return res
        })
        .catch(() => caches.match('/index.html'))
    )
    return
  }
  event.respondWith(
    caches.match(req).then((cached) =>
      cached ||
      fetch(req).then((res) => {
        // Never store a 404 or a proxy error page in place of an asset.
        if (res.ok) {
          const copy = res.clone()
          caches.open(CACHE).then((c) => c.put(req, copy))
        }
        return res
      })
    )
  )
})