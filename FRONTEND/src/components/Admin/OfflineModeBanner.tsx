/**
 * Arquivo: src/components/Admin/OfflineModeBanner.tsx
 * Objetivo: aviso permanente de modo offline nas telas de venda e de caixa. O operador precisa saber que
 *           pode continuar vendendo sem internet e que as vendas ficam guardadas neste caixa (e que não deve
 *           fechar o caixa "por causa da internet", como aconteceu no incidente de 19/10/2026).
 */
import { WifiOff } from "lucide-react";
import { useConnectivity } from "@/hooks/useConnectivity";

export const OFFLINE_MODE_MESSAGE = "Sem internet — pode continuar vendendo. As vendas estão guardadas neste caixa.";

export default function OfflineModeBanner() {
  const status = useConnectivity();
  if (status !== "OFFLINE" && status !== "API_UNAVAILABLE") return null;

  return (
    <div className="rg-offline-banner" role="status" aria-live="polite" data-testid="offline-mode-banner">
      <WifiOff size={16} aria-hidden="true" />
      <span>{OFFLINE_MODE_MESSAGE}</span>
    </div>
  );
}
