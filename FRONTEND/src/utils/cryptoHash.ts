/**
 * Arquivo: src/utils/cryptoHash.ts
 * Objetivo: calcula o hash SHA-256 canônico de um payload de venda via Web Crypto API.
 *           Mantém correspondência estrita com a serialização de HorusPayloadHash.cs no backend.
 */
import type { RegisterSalePayload } from "@/services/api/salesHistoryService";

function parseMoneyBr(value: string | number | undefined | null): number {
  if (value === undefined || value === null) return 0;
  if (typeof value === "number") return value;
  const cleaned = String(value)
    .replace(/R\$/gi, "")
    .replace(/\s/g, "")
    .replace(/\./g, "")
    .replace(",", ".");
  const parsed = parseFloat(cleaned);
  return isNaN(parsed) ? 0 : parsed;
}

export async function computeSalePayloadHash(payload: RegisterSalePayload): Promise<string> {
  const canonical = {
    customerName: (payload.customerName || "").trim(),
    customerCpf: (payload.customerCpf || "").trim(),
    paymentType: (payload.paymentType || "").trim(),
    totalAmount: parseMoneyBr(payload.totalAmount),
    operatorName: (payload.operatorName || "").trim(),
    items: [...(payload.items || [])]
      .sort((a, b) => (a.productCode || "").localeCompare(b.productCode || ""))
      .map((i) => ({
        productCode: (i.productCode || "").trim(),
        quantity: Math.round((i.quantity || 0) * 10000) / 10000,
        unitPrice: Math.round((i.unitPrice || 0) * 100) / 100,
        desconto: Math.round((i.desconto || 0) * 100) / 100,
        promocaoId: i.promocaoId || "",
      })),
    payments: [...(payload.payments || [])]
      .sort((a, b) => (a.paymentType || "").localeCompare(b.paymentType || ""))
      .map((p) => ({
        paymentType: (p.paymentType || "").trim(),
        amount: Math.round((p.amount || 0) * 100) / 100,
        cashGiven: Math.round((p.cashGiven || 0) * 100) / 100,
        changeAmount: Math.round((p.changeAmount || 0) * 100) / 100,
      })),
  };

  const json = JSON.stringify(canonical);
  const encoder = new TextEncoder();
  const data = encoder.encode(json);
  const hashBuffer = await crypto.subtle.digest("SHA-256", data);
  const hashArray = Array.from(new Uint8Array(hashBuffer));
  return hashArray.map((b) => b.toString(16).padStart(2, "0")).join("");
}

export async function computeCashMovementPayloadHash(payload: {
  tipo: string;
  valor: string;
  motivo: string;
}): Promise<string> {
  const canonical = {
    tipo: (payload.tipo || "").trim().toLowerCase(),
    valor: parseMoneyBr(payload.valor),
    motivo: (payload.motivo || "").trim(),
  };

  const json = JSON.stringify(canonical);
  const encoder = new TextEncoder();
  const data = encoder.encode(json);
  const hashBuffer = await crypto.subtle.digest("SHA-256", data);
  const hashArray = Array.from(new Uint8Array(hashBuffer));
  return hashArray.map((b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * Calcula o hash seguro de senha para autenticação offline usando PBKDF2 com SHA-256 e 100.000 iterações.
 * O salt deriva do email normalizado para evitar dicionários pré-computados (rainbow tables).
 */
export async function computePasswordHash(email: string, password: string): Promise<string> {
  const normalizedEmail = email.trim().toLowerCase();
  const encoder = new TextEncoder();
  const salt = encoder.encode(`horus-pdv-pwd-salt:${normalizedEmail}`);

  const keyMaterial = await crypto.subtle.importKey(
    "raw",
    encoder.encode(password),
    { name: "PBKDF2" },
    false,
    ["deriveBits"],
  );

  const derivedBits = await crypto.subtle.deriveBits(
    {
      name: "PBKDF2",
      salt,
      iterations: 100000,
      hash: "SHA-256",
    },
    keyMaterial,
    256,
  );

  const hashArray = Array.from(new Uint8Array(derivedBits));
  return hashArray.map((b) => b.toString(16).padStart(2, "0")).join("");
}
