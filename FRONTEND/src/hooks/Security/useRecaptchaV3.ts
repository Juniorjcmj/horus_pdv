/**
 * Arquivo: src/hooks/Security/useRecaptchaV3.ts
 * Objetivo: carregar o script oficial do Google reCAPTCHA v3 e executar ações protegidas.
  * Entradas esperadas: recebe chave pública e action; retorna função para executar reCAPTCHA quando habilitado.
*/
import { useCallback } from "react";

declare global {
  interface Window {
    grecaptcha?: {
      ready: (callback: () => void) => void;
      execute: (siteKey: string, options: { action: string }) => Promise<string>;
    };
  }
}

const RECAPTCHA_SCRIPT_ID = "google-recaptcha-v3";
const RECAPTCHA_TIMEOUT_MS = 5_000;
let recaptchaScriptPromise: Promise<void> | null = null;

function loadRecaptchaScript(siteKey: string) {
  if (typeof window === "undefined") {
    return Promise.reject(new Error("reCAPTCHA indisponível fora do navegador."));
  }
  if (window.grecaptcha) return Promise.resolve();
  if (recaptchaScriptPromise) return recaptchaScriptPromise;

  recaptchaScriptPromise = new Promise<void>((resolve, reject) => {
    const existing = document.getElementById(RECAPTCHA_SCRIPT_ID);
    if (existing) {
      existing.addEventListener("load", () => resolve(), { once: true });
      existing.addEventListener(
        "error",
        () => reject(new Error("Falha ao carregar o script do reCAPTCHA.")),
        { once: true },
      );
      return;
    }

    const script = document.createElement("script");
    script.id = RECAPTCHA_SCRIPT_ID;
    script.src = `https://www.google.com/recaptcha/api.js?render=${encodeURIComponent(siteKey)}`;
    script.async = true;
    script.defer = true;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error("Falha ao carregar o script do reCAPTCHA."));
    document.head.appendChild(script);
  }).catch((error) => {
    recaptchaScriptPromise = null;
    throw error;
  });

  return recaptchaScriptPromise;
}

function waitForRecaptchaReady() {
  return new Promise<void>((resolve, reject) => {
    if (!window.grecaptcha?.ready) {
      reject(new Error("reCAPTCHA não inicializado."));
      return;
    }
    window.grecaptcha.ready(() => resolve());
  });
}

export default function useRecaptchaV3() {
  const siteKey = String(import.meta.env.VITE_RECAPTCHA_SITE_KEY || "").trim();
  const isRecaptchaConfigured = Boolean(siteKey);

  const executeRecaptcha = useCallback(
    async (action: string) => {
      if (!siteKey) {
        return "";
      }

      let timeoutId: number | undefined;
      try {
        // Abrange carregamento, ready e execute: sem internet o Google pode ficar pendente
        // indefinidamente, mesmo com navigator.onLine=true (Wi-Fi conectado sem internet).
        return await Promise.race([
          (async () => {
            await loadRecaptchaScript(siteKey);
            await waitForRecaptchaReady();
            const token = await window.grecaptcha?.execute(siteKey, { action });
            if (!token) throw new Error("Não foi possível gerar o token de segurança.");
            return token;
          })(),
          new Promise<never>((_, reject) => {
            timeoutId = window.setTimeout(() => {
              reject(new Error("A validação de segurança demorou demais. Tente novamente."));
            }, RECAPTCHA_TIMEOUT_MS);
          }),
        ]);
      } catch (error) {
        // Permite recarregar o script na próxima tentativa após a conexão voltar.
        if (!window.grecaptcha) {
          recaptchaScriptPromise = null;
          document.getElementById(RECAPTCHA_SCRIPT_ID)?.remove();
        }
        throw error;
      } finally {
        window.clearTimeout(timeoutId);
      }
    },
    [siteKey],
  );

  return { executeRecaptcha, isRecaptchaConfigured };
}
