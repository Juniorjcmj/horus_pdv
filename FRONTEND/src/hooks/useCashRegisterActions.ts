/**
 * Arquivo: src/hooks/useCashRegisterActions.ts
 * Objetivo: ações do caixa (abertura, fechamento, sangria e reforço) compartilhadas entre a tela de
 *           Caixa e o painel de caixa da frente de venda. Tenta o servidor; se falhar, grava no
 *           IndexedDB (modo offline) para sincronizar depois. Sempre devolve o novo status ao chamador.
 */
import { useCallback, useState } from "react";
import { Toast } from "@/hooks/Dialog";
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

type StatusSetter = (status: CashRegisterStatusDto | null) => void;

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
      setSaving(true);
      try {
        applyOnline(await cashRegisterService.open(openingAmount));
        Toast.success("Caixa aberto. Frente de caixa liberada para venda.");
        return true;
      } catch (onlineError) {
        try {
          onStatus(await openCashLocal(openingAmount, user?.id, user?.name));
          Toast.info("Caixa aberto localmente (modo offline). Será sincronizado ao reconectar.");
          return true;
        } catch {
          Toast.error(onlineError instanceof Error ? onlineError.message : "Não foi possível abrir o caixa.");
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
      setSaving(true);
      try {
        const status = await cashRegisterService.close(closingAmount, note, differenceReason);
        applyOnline(status);
        Toast.success("Caixa fechado. Vendas bloqueadas até nova abertura.");
        return status?.lastSession ?? null;
      } catch (onlineError) {
        try {
          const localStatus = await closeCashLocal(closingAmount, note, differenceReason, user?.id, user?.name);
          onStatus(localStatus);
          Toast.info("Caixa fechado localmente (modo offline). O encerramento será sincronizado ao reconectar.");
          return localStatus?.lastSession ?? null;
        } catch {
          Toast.error(onlineError instanceof Error ? onlineError.message : "Não foi possível fechar o caixa.");
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
      try {
        applyOnline(await cashRegisterService.registrarMovimento(tipo, valor, motivo));
        Toast.success(tipo === "Sangria" ? "Sangria registrada." : "Reforço registrado.");
        return true;
      } catch (onlineError) {
        try {
          onStatus(await registerMovementLocal(tipo, valor, motivo, user?.id, user?.name));
          Toast.info(
            tipo === "Sangria"
              ? "Sangria registrada localmente (modo offline)."
              : "Reforço registrado localmente (modo offline).",
          );
          return true;
        } catch {
          Toast.error(onlineError instanceof Error ? onlineError.message : "Não foi possível registrar o movimento.");
          return false;
        }
      }
    },
    [applyOnline, onStatus],
  );

  return { saving, openCash, closeCash, registerMovement };
}
