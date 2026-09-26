/**
 * Arquivo: src/registerServiceWorker.ts
 * Objetivo: registra o Service Worker do PWA no navegador e monitora ciclo de vida.
 * Quando uma nova versao e detectada, dispara evento para que a UI exiba notificacao.
 */

export function registerServiceWorker(): void {
  if (typeof window === "undefined" || !("serviceWorker" in navigator)) {
    return;
  }

  // Registra apos o carregamento completo da pagina para nao disputar banda inicial
  window.addEventListener("load", () => {
    navigator.serviceWorker
      .register("/sw.js")
      .then((registration) => {
        // Monitora novas versoes do Service Worker instaladas
        registration.addEventListener("updatefound", () => {
          const newWorker = registration.installing;
          if (!newWorker) return;

          newWorker.addEventListener("statechange", () => {
            if (newWorker.state === "installed" && navigator.serviceWorker.controller) {
              // Nova versao pronta — notifica a UI para que o usuario decida quando atualizar
              console.info("[PWA] Nova versao do Horus PDV disponivel.");
              window.dispatchEvent(
                new CustomEvent("horuspdv-sw-update-available", {
                  detail: { registration },
                }),
              );
            }
          });
        });
      })
      .catch((err) => {
        console.warn("[PWA] Falha ao registrar Service Worker:", err);
      });
  });
}

/**
 * Envia mensagem SKIP_WAITING ao Service Worker em espera e recarrega a pagina
 * quando o novo SW assumir o controle.
 */
export function applyServiceWorkerUpdate(registration: ServiceWorkerRegistration): void {
  const waiting = registration.waiting;
  if (!waiting) return;

  // Quando o novo SW ativar, recarrega para garantir que os assets sejam consistentes
  navigator.serviceWorker.addEventListener("controllerchange", () => {
    window.location.reload();
  }, { once: true });

  waiting.postMessage({ type: "SKIP_WAITING" });
}
