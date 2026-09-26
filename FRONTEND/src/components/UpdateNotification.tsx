/**
 * Arquivo: src/components/UpdateNotification.tsx
 * Objetivo: exibe banner quando uma nova versao do PWA esta disponivel,
 *           permitindo que o usuario atualize no momento que quiser.
 */
import { useEffect, useState } from "react";
import { RefreshCw } from "lucide-react";
import { applyServiceWorkerUpdate } from "@/registerServiceWorker";

export default function UpdateNotification() {
  const [registration, setRegistration] = useState<ServiceWorkerRegistration | null>(null);

  useEffect(() => {
    const handler = (e: Event) => {
      const detail = (e as CustomEvent).detail;
      if (detail?.registration) {
        setRegistration(detail.registration);
      }
    };

    window.addEventListener("horuspdv-sw-update-available", handler);
    return () => window.removeEventListener("horuspdv-sw-update-available", handler);
  }, []);

  if (!registration) return null;

  return (
    <div
      role="alert"
      data-testid="update-notification"
      className="fixed bottom-16 right-3 z-50 flex items-center gap-3 rounded-lg border border-blue-300 bg-blue-50 px-4 py-3 shadow-lg dark:border-blue-700 dark:bg-blue-950"
    >
      <RefreshCw size={18} className="text-blue-600 dark:text-blue-400 shrink-0" />
      <span className="text-sm text-blue-800 dark:text-blue-200">
        Nova versao disponivel
      </span>
      <button
        type="button"
        data-testid="update-button"
        onClick={() => applyServiceWorkerUpdate(registration)}
        className="ml-1 rounded-md bg-blue-600 px-3 py-1 text-xs font-medium text-white shadow-sm transition hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
      >
        Atualizar agora
      </button>
    </div>
  );
}
