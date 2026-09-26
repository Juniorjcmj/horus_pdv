/**
 * Arquivo: src/registerServiceWorker.ts
 * Objetivo: registra o Service Worker do PWA no navegador e monitora ciclo de vida.
 */

export function registerServiceWorker(): void {
  if (typeof window === "undefined" || !("serviceWorker" in navigator)) {
    return;
  }

  // Registra após o carregamento completo da página para não disputar banda inicial
  window.addEventListener("load", () => {
    navigator.serviceWorker
      .register("/sw.js")
      .then((registration) => {
        // Monitora novas versões do Service Worker instaladas
        registration.addEventListener("updatefound", () => {
          const newWorker = registration.installing;
          if (!newWorker) return;

          newWorker.addEventListener("statechange", () => {
            if (newWorker.state === "installed" && navigator.serviceWorker.controller) {
              // Nova versão pronta — notifica o usuário ou recarrega silenciosamente
              console.info("[PWA] Nova versão do Horus PDV disponível.");
            }
          });
        });
      })
      .catch((err) => {
        console.warn("[PWA] Falha ao registrar Service Worker:", err);
      });
  });
}
