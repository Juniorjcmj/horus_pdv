/**
 * Arquivo: src/components/SyncStatus.tsx
 * Objetivo: indicador global de status de sincronização (Online/Offline/API indisponível).
 *           Renderizado no canto inferior do layout.
 */
import { useConnectivity } from "@/hooks/useConnectivity";
import type { ConnectionStatus } from "@/shared/types/sync";
import { Cloud, CloudOff, Loader2, WifiOff } from "lucide-react";

const STATUS_CONFIG: Record<ConnectionStatus, {
  label: string;
  dotClass: string;
  Icon: typeof Cloud;
  animate?: boolean;
}> = {
  ONLINE: {
    label: "Online",
    dotClass: "bg-emerald-500",
    Icon: Cloud,
  },
  OFFLINE: {
    label: "Offline",
    dotClass: "bg-red-500",
    Icon: WifiOff,
  },
  API_UNAVAILABLE: {
    label: "API indisponível",
    dotClass: "bg-amber-500",
    Icon: CloudOff,
  },
  SYNCING: {
    label: "Sincronizando...",
    dotClass: "bg-blue-500",
    Icon: Loader2,
    animate: true,
  },
};

export default function SyncStatus() {
  const status = useConnectivity();
  const config = STATUS_CONFIG[status];
  const { Icon } = config;

  return (
    <div className="flex items-center gap-1.5 rounded-full border border-border-primary bg-bg-light px-2.5 py-1 text-[11px] font-medium text-text-secondary shadow-sm">
      <span className={`inline-block h-2 w-2 rounded-full ${config.dotClass}`} />
      <Icon size={12} className={config.animate ? "animate-spin" : ""} />
      <span>{config.label}</span>
    </div>
  );
}
