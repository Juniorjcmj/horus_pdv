/**
 * Service Worker: Horus PDV Offline-First
 * Fornece caching de app-shell, roteamento offline para SPA e stale-while-revalidate para assets estáticos.
 */

const CACHE_NAME = "horus-pdv-shell-v1";
const SHELL_ASSETS = [
  "/",
  "/index.html",
  "/manifest.webmanifest",
  "/favicon.svg"
];

// Instalação: pre-cache dos arquivos essenciais do app-shell
self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      return cache.addAll(SHELL_ASSETS);
    }).then(() => {
      return self.skipWaiting();
    })
  );
});

// Ativação: remoção de caches legados
self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) => {
      return Promise.all(
        keys.map((key) => {
          if (key !== CACHE_NAME) {
            return caches.delete(key);
          }
        })
      );
    }).then(() => {
      return self.clients.claim();
    })
  );
});

// Interceptação de requisições
self.addEventListener("fetch", (event) => {
  const { request } = event;

  // Apenas requisições GET
  if (request.method !== "GET") {
    return;
  }

  const url = new URL(request.url);

  // Nunca cacheia requisições de API (são tratadas pelo IndexedDB e Outbox)
  if (url.pathname.startsWith("/api") || url.port === "5260" || url.port === "5261") {
    return;
  }

  // Requisições de navegação SPA (ex: /admin/vendas, /admin/caixa, /login)
  if (request.mode === "navigate") {
    event.respondWith(
      fetch(request)
        .then((response) => {
          if (response && response.status === 200) {
            const clone = response.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put("/index.html", clone));
          }
          return response;
        })
        .catch(async () => {
          // Fallback offline: serve o index.html em cache para que o React Router execute normalmente
          const cache = await caches.open(CACHE_NAME);
          const cached = await cache.match("/index.html") || await cache.match("/");
          if (cached) return cached;
          return new Response("Horus PDV Offline - Sem conexão", {
            headers: { "Content-Type": "text/html; charset=utf-8" },
          });
        })
    );
    return;
  }

  // Assets estáticos (JS, CSS, imagens, fontes, favicon)
  if (
    url.pathname.startsWith("/assets/") ||
    url.pathname.endsWith(".js") ||
    url.pathname.endsWith(".css") ||
    url.pathname.endsWith(".svg") ||
    url.pathname.endsWith(".png") ||
    url.pathname.endsWith(".woff") ||
    url.pathname.endsWith(".woff2") ||
    url.pathname.endsWith(".webmanifest")
  ) {
    event.respondWith(
      caches.match(request).then((cachedResponse) => {
        const fetchPromise = fetch(request)
          .then((networkResponse) => {
            if (networkResponse && networkResponse.status === 200) {
              const clone = networkResponse.clone();
              caches.open(CACHE_NAME).then((cache) => cache.put(request, clone));
            }
            return networkResponse;
          })
          .catch(() => cachedResponse);

        // Retorna do cache imediatamente se existir (Stale-While-Revalidate), senão aguarda a rede
        return cachedResponse || fetchPromise;
      })
    );
    return;
  }
});

// Comunicação com clientes para atualizações de versão
self.addEventListener("message", (event) => {
  if (event.data && event.data.type === "SKIP_WAITING") {
    self.skipWaiting();
  }
});
