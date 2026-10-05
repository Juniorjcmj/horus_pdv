/**
 * Arquivo: src/utils/cashRegisterFormat.ts
 * Objetivo: formatação e validação compartilhadas entre a tela de Caixa e o painel de caixa do PDV.
 */
import type { FormEvent } from "react";

export const MANAGER_ROLES = ["administrador", "gerente"];

const PAYMENT_LABELS: Record<string, string> = {
  dinheiro: "Dinheiro",
  pix: "PIX",
  debito: "Cartão Débito",
  credito: "Cartão Crédito",
  fiado: "Fiado / A Prazo",
};

export function cashPaymentLabel(paymentType: string) {
  return PAYMENT_LABELS[paymentType.toLowerCase()] ?? paymentType;
}

export function formatElapsed(minutes: number) {
  if (minutes < 1) return "menos de 1 min";
  const hours = Math.floor(minutes / 60);
  const remainingMinutes = minutes % 60;
  if (hours === 0) return `${remainingMinutes} min`;
  return `${hours}h ${String(remainingMinutes).padStart(2, "0")}min`;
}

/** Bloqueia caracteres que não sejam dígito, vírgula ou ponto em campos de valor. */
export function preventInvalidMoneyBeforeInput(event: FormEvent<HTMLInputElement>) {
  const data = (event.nativeEvent as InputEvent).data ?? "";
  if (data && /[^0-9,.]/.test(data)) {
    event.preventDefault();
  }
}

export function hasNonZeroDifference(value?: string | null) {
  if (!value) return false;
  return value !== "0,00" && value !== "-0,00";
}
