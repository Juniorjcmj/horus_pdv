import { AlertCircle, AlertTriangle, ArrowRight, CheckCircle2, Clock } from "lucide-react";
import { useEffect, useState } from "react";
import type { PageKey } from "@/components/AppSidebar/AppSidebar";
import { productService, type VencimentoResumo } from "@/services/api/productService";

interface ValidadeAlertWidgetProps {
  onNavigate?: (page: PageKey) => void;
}

export default function ValidadeAlertWidget({ onNavigate }: ValidadeAlertWidgetProps) {
  const [resumo, setResumo] = useState<VencimentoResumo | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    productService
      .getResumoVencimentos()
      .then((data) => {
        if (data) setResumo(data);
      })
      .catch((err) => console.error("Erro ao carregar resumo de vencimentos:", err))
      .finally(() => setLoading(false));
  }, []);

  if (loading || !resumo) {
    return null;
  }

  // Não há produtos controlados
  if (resumo.totalControlados === 0) {
    return null;
  }

  const hasUrgentAlerts = resumo.vencidos > 0;
  const hasWarningAlerts = resumo.venceEm7Dias > 0 || resumo.venceEm15Dias > 0;

  return (
    <div
      className={`card rounded-2xl border p-4 md:p-5 transition-all ${
        hasUrgentAlerts
          ? "border-red-500/30 bg-red-500/5 shadow-sm"
          : hasWarningAlerts
          ? "border-amber-500/30 bg-amber-500/5 shadow-sm"
          : "border-border-primary bg-bg-surface"
      }`}
    >
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-start gap-3">
          <div
            className={`mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-xl ${
              hasUrgentAlerts
                ? "bg-red-500/15 text-red-600 dark:text-red-400"
                : hasWarningAlerts
                ? "bg-amber-500/15 text-amber-600 dark:text-amber-400"
                : "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400"
            }`}
          >
            {hasUrgentAlerts ? (
              <AlertCircle size={20} />
            ) : hasWarningAlerts ? (
              <AlertTriangle size={20} />
            ) : (
              <CheckCircle2 size={20} />
            )}
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h3 className="font-semibold text-text-primary text-base">
                Controle de Validade de Produtos
              </h3>
              <span className="text-xs text-text-tertiary">
                ({resumo.totalControlados} controlados)
              </span>
            </div>
            <p className="mt-0.5 text-xs text-text-secondary">
              {hasUrgentAlerts
                ? "Atenção: existem itens vencidos que requerem descarte ou ação imediata."
                : hasWarningAlerts
                ? "Itens com vencimento próximo para planejar promoções ou queima de estoque."
                : "Nenhum produto próximo do vencimento nos próximos 15 dias."}
            </p>
          </div>
        </div>

        <button
          type="button"
          onClick={() => onNavigate?.("relatorios")}
          className="inline-flex items-center justify-center gap-1.5 self-start sm:self-auto rounded-lg border border-border-secondary bg-bg-surface px-3 py-1.5 text-xs font-medium text-text-primary transition hover:border-primary/40 hover:bg-accent/10"
        >
          <span>Ver relatório completo</span>
          <ArrowRight size={14} />
        </button>
      </div>

      {/* Grid Semafórico */}
      <div className="mt-4 grid grid-cols-2 gap-2.5 sm:grid-cols-4">
        {/* Vencidos */}
        <div
          className={`flex items-center gap-3 rounded-xl border p-2.5 ${
            resumo.vencidos > 0
              ? "border-red-500/40 bg-red-500/10 text-red-700 dark:text-red-300"
              : "border-border-secondary bg-bg-primary/50 text-text-secondary"
          }`}
        >
          <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-red-500/20 text-red-600 dark:text-red-400">
            <AlertCircle size={15} />
          </div>
          <div>
            <div className="text-lg font-bold leading-none">{resumo.vencidos}</div>
            <div className="mt-1 text-[11px] font-medium leading-none">Vencidos</div>
          </div>
        </div>

        {/* Vencem em 7 dias */}
        <div
          className={`flex items-center gap-3 rounded-xl border p-2.5 ${
            resumo.venceEm7Dias > 0
              ? "border-amber-500/40 bg-amber-500/10 text-amber-700 dark:text-amber-300"
              : "border-border-secondary bg-bg-primary/50 text-text-secondary"
          }`}
        >
          <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-amber-500/20 text-amber-600 dark:text-amber-400">
            <Clock size={15} />
          </div>
          <div>
            <div className="text-lg font-bold leading-none">{resumo.venceEm7Dias}</div>
            <div className="mt-1 text-[11px] font-medium leading-none">Em até 7 dias</div>
          </div>
        </div>

        {/* Vencem em 15 dias */}
        <div
          className={`flex items-center gap-3 rounded-xl border p-2.5 ${
            resumo.venceEm15Dias > 0
              ? "border-yellow-500/40 bg-yellow-500/10 text-yellow-700 dark:text-yellow-300"
              : "border-border-secondary bg-bg-primary/50 text-text-secondary"
          }`}
        >
          <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-yellow-500/20 text-yellow-600 dark:text-yellow-400">
            <Clock size={15} />
          </div>
          <div>
            <div className="text-lg font-bold leading-none">{resumo.venceEm15Dias}</div>
            <div className="mt-1 text-[11px] font-medium leading-none">Em até 15 dias</div>
          </div>
        </div>

        {/* Vencem em 30 dias */}
        <div className="flex items-center gap-3 rounded-xl border border-border-secondary bg-bg-primary/50 p-2.5 text-text-secondary">
          <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-blue-500/15 text-blue-600 dark:text-blue-400">
            <Clock size={15} />
          </div>
          <div>
            <div className="text-lg font-bold leading-none text-text-primary">
              {resumo.venceEm30Dias}
            </div>
            <div className="mt-1 text-[11px] font-medium leading-none">Em até 30 dias</div>
          </div>
        </div>
      </div>
    </div>
  );
}
