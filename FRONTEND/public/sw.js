/**
 * Service Worker: Horus PDV Offline-First
 * Fornece caching de app-shell, roteamento offline para SPA e stale-while-revalidate para assets.
 *
 * Em DEV (vite dev), este arquivo e servido diretamente de public/.
 * Em PROD (vite build), o plugin horusPwaPlugin em vite.config.ts sobrescreve dist/sw.js
 * injetando a lista real de assets com hash e um CACHE_VERSION baseado no conteudo.
 */

// Placeholders — substituidos pelo build plugin em producao
const CACHE_VERSION = "__CACHE_VERSION__";
const PRECACHE_URLS = [];

const CACHE_NAME = `horus-pdv-shell-${CACHE_VERSION}`;

// Instalacao: pre-cache dos arquivos essenciais do app-shell
self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      return cache.addAll(PRECACHE_URLS);
    })
    // NAO chama self.skipWaiting() aqui — a ativacao e controlada pelo usuario
    // via mensagem SKIP_WAITING, evitando mismatch de chunks Vite
  );
});

// Ativacao: remoção de caches legados
self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) => {
      return Promise.all(
        keys.map((key) => {
          if (key !== CACHE_NAME && key.startsWith("horus-pdv-shell-")) {
            return caches.delete(key);
          }
        })
      );
    }).then(() => {
      return self.clients.claim();
    })
  );
});

// Interceptacao de requisicoes
self.addEventListener("fetch", (event) => {
  const { request } = event;

  // Apenas requisicoes GET
  if (request.method !== "GET") {
    return;
  }

  const url = new URL(request.url);

  // Nunca cacheia requisicoes de API (sao tratadas pelo IndexedDB e Outbox)
  if (url.pathname.startsWith("/api") || url.port === "5260" || url.port === "5261") {
    return;
  }

  // Requisicoes de navegacao SPA (ex: /admin/vendas, /admin/caixa, /login)
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
          // Fallback offline: serve o index.html em cache para que o React execute normalmente
          const cache = await caches.open(CACHE_NAME);
          const cached = await cache.match("/index.html") || await cache.match("/");
          if (cached) return cached;
          return new Response("Horus PDV Offline - Sem conexao", {
            headers: { "Content-Type": "text/html; charset=utf-8" },
          });
        })
    );
    return;
  }

  // Assets estaticos (JS, CSS, imagens, fontes, favicon)
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

        // Retorna do cache imediatamente se existir (Stale-While-Revalidate), senao aguarda a rede
        return cachedResponse || fetchPromise;
      })
    );
    return;
  }
});

// Comunicacao com clientes para atualizacoes de versao controladas pelo usuario
self.addEventListener("message", (event) => {
  if (event.data && event.data.type === "SKIP_WAITING") {
    self.skipWaiting();
  }
});
