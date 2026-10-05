/**
 * Arquivo: src/hooks/useCashRegisterActions.ts
 * Objetivo: ações do caixa (abertura, fechamento, sangria e reforço) compartilhadas entre a tela de
 *           Caixa e o painel de caixa da frente de venda. Tenta o servidor; se falhar POR CONEXÃO, grava no
 *           IndexedDB (modo offline) para sincronizar depois. Sempre devolve o novo status ao chamador.
 *
 * Idempotência: o EventId (e o hash do movimento) é gerado ANTES da chamada online e reaproveitado no
 * outbox. Se o servidor gravou mas a resposta não chegou (timeout), o reenvio vira replay em vez de
 * duplicar a sangria/reforço.
 */
import { useCallback, useState } from "react";
import { Toast } from "@/hooks/Dialog";
import { ApiError } from "@/services/api/apiClient";
import {
  cashRegisterService,
  type CashMovementType,
  type CashRegisterSessionDto,
  type CashRegisterStatusDto,
} from "@/services/api/cashRegisterService";
import {
  closeCashLocal,
  openCashLocal,
  registerMovementLocal,
  saveCashStatus,
} from "@/infrastructure/database/repositories/CashSessionRepository";
import { getStoredAuthUser } from "@/utils/authStorage";
import { computeCashMovementPayloadHash } from "@/utils/cryptoHash";

type StatusSetter = (status: CashRegisterStatusDto | null) => void;

function newEventId(prefix: string): string {
  const unique = typeof crypto !== "undefined" && crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}`;
  return `${prefix}-${unique}`;
}

/**
 * Só falha de conexão vai para o modo offline: sem rede, timeout, 408/429 ou erro 5xx do servidor.
 * Recusa de regra de negócio (4xx: "não possui caixa aberto", motivo curto…) é mostrada ao operador —
 * enfileirar isso só criaria um evento que o servidor vai recusar de novo.
 */
function isConnectivityError(err: unknown): boolean {
  if (!(err instanceof ApiError)) return true;
  return err.status === 0 || err.status === 408 || err.status === 429 || err.status >= 500;
}

function errorMessage(err: unknown, fallback: string): string {
  return err instanceof Error && err.message ? err.message : fallback;
}

export function useCashRegisterActions(onStatus: StatusSetter) {
  const [saving, setSaving] = useState(false);

  const applyOnline = useCallback(
    (status: CashRegisterStatusDto | null | undefined) => {
      onStatus(status ?? null);
      if (status) void saveCashStatus(status);
    },
    [onStatus],
  );

  /** Abre o caixa. Retorna true se abriu (online ou offline). */
  const openCash = useCallback(
    async (openingAmount: string): Promise<boolean> => {
      const user = getStoredAuthUser();
      const eventId = newEventId("ev-cxopen");
      setSaving(true);
      try {
        applyOnline(await cashRegisterService.open(openingAmount, eventId));
        Toast.success("Caixa aberto. Frente de caixa liberada para venda.");
        return true;
      } catch (onlineError) {
        if (!isConnectivityError(onlineError)) {
          Toast.error(errorMessage(onlineError, "Não foi possível abrir o caixa."));
          return false;
        }
        try {
          onStatus(await openCashLocal(openingAmount, user?.id, user?.name, eventId));
          Toast.info("Caixa aberto localmente (modo offline). Será sincronizado ao reconectar.");
          return true;
        } catch (localError) {
          Toast.error(errorMessage(localError, errorMessage(onlineError, "Não foi possível abrir o caixa.")));
          return false;
        }
      } finally {
        setSaving(false);
      }
    },
    [applyOnline, onStatus],
  );

  /** Fecha o caixa. Retorna o turno encerrado (para resumo/impressão) ou null se falhou. */
  const closeCash = useCallback(
    async (closingAmount: string, note: string, differenceReason?: string): Promise<CashRegisterSessionDto | null> => {
      const user = getStoredAuthUser();
      const eventId = newEventId("ev-cxclose");
      setSaving(true);
      try {
        const status = await cashRegisterService.close(closingAmount, note, differenceReason, eventId);
        applyOnline(status);
        Toast.success("Caixa fechado. Vendas bloqueadas até nova abertura.");
        return status?.lastSession ?? null;
      } catch (onlineError) {
        if (!isConnectivityError(onlineError)) {
          Toast.error(errorMessage(onlineError, "Não foi possível fechar o caixa."));
          return null;
        }
        try {
          const localStatus = await closeCashLocal(closingAmount, note, differenceReason, user?.id, user?.name, eventId);
          onStatus(localStatus);
          Toast.info("Caixa fechado localmente (modo offline). O encerramento será sincronizado ao reconectar.");
          return localStatus?.lastSession ?? null;
        } catch (localError) {
          Toast.error(errorMessage(localError, errorMessage(onlineError, "Não foi possível fechar o caixa.")));
          return null;
        }
      } finally {
        setSaving(false);
      }
    },
    [applyOnline, onStatus],
  );

  /** Registra sangria/reforço. Retorna true se registrou (online ou offline). */
  const registerMovement = useCallback(
    async (tipo: CashMovementType, valor: string, motivo: string): Promise<boolean> => {
      const user = getStoredAuthUser();
      const eventId = newEventId("ev-cxmov");
      const payloadHash = await computeCashMovementPayloadHash({ tipo, valor, motivo }).catch(() => undefined);
      try {
        applyOnline(await cashRegisterService.registrarMovimento(tipo, valor, motivo, eventId, payloadHash));
        Toast.success(tipo === "Sangria" ? "Sangria registrada." : "Reforço registrado.");
        return true;
      } catch (onlineError) {
        if (!isConnectivityError(onlineError)) {
          Toast.error(errorMessage(onlineError, "Não foi possível registrar o movimento."));
          return false;
        }
        try {
          onStatus(await registerMovementLocal(tipo, valor, motivo, user?.id, user?.name, eventId));
          Toast.info(
            tipo === "Sangria"
              ? "Sangria registrada localmente (modo offline)."
              : "Reforço registrado localmente (modo offline).",
          );
          return true;
        } catch (localError) {
          Toast.error(errorMessage(localError, errorMessage(onlineError, "Não foi possível registrar o movimento.")));
          return false;
        }
      }
    },
    [applyOnline, onStatus],
  );

  return { saving, openCash, closeCash, registerMovement };
}
