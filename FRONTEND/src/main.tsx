/**
 * Arquivo: src/main.tsx
 * Objetivo: inicializa o React e monta a aplicação no elemento raiz.
 * Entradas esperadas: espera que exista um elemento #root no HTML base.
 */
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { ToastContainer } from "@/hooks/Dialog";
import ErrorBoundary from "@/components/ErrorBoundary";
import UpdateNotification from "@/components/UpdateNotification";
import "./index.css";
import App from "./App.tsx";
import { registerServiceWorker } from "./registerServiceWorker";

// Registra o Service Worker do PWA para operação Offline-First
registerServiceWorker();

if (import.meta.env.DEV) {
  import("./infrastructure/testHarness").then((m) => m.setupTestHarness());
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ErrorBoundary>
      <ToastContainer />
      <App />
      <UpdateNotification />
    </ErrorBoundary>
  </StrictMode>,
);
