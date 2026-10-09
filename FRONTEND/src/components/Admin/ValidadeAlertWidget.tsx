import { AlertCircle, AlertTriangle, ArrowRight, CheckCircle2 } from "lucide-react";
import type { PageKey } from "@/components/AppSidebar/AppSidebar";
import { productService } from "@/services/api/productService";
import useRemoteList from "@/hooks/useRemoteList";
import ListState from "./ListState";

const loadSummary = async () => {
  const summary = await productService.getResumoVencimentos();
  return summary ? [summary] : [];
};

export default function ValidadeAlertWidget({ onNavigate }: { onNavigate?: (page: PageKey) => void }) {
  const { items, isLoading, error, reload } = useRemoteList(loadSummary, "Não foi possível consultar a validade dos produtos.");
  const summary = items[0];
  if (isLoading) return null;
  if (error) return <section aria-label="Validade dos produtos" className="card">
    <ListState title={error} description="Tente novamente para conferir se há produtos vencidos." error actionLabel="Tentar novamente" onAction={() => void reload()} />
  </section>;
  if (!summary || summary.totalControlados === 0) return null;
  const urgent = summary.vencidos > 0;
  const warning = summary.venceEm7Dias > 0 || summary.venceEm15Dias > 0;
  const Icon = urgent ? AlertCircle : warning ? AlertTriangle : CheckCircle2;
  const counts = [
    ["Vencidos", summary.vencidos],
    ["Em até 7 dias", summary.venceEm7Dias],
    ["Em até 15 dias", summary.venceEm15Dias],
    ["Em até 30 dias", summary.venceEm30Dias],
  ] as const;
  return (
    <section aria-labelledby="expiry-title" className="card p-4 md:p-5">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex min-w-0 items-start gap-3">
          <Icon size={22} aria-hidden="true" className={urgent ? "mt-1 shrink-0 text-primary" : "mt-1 shrink-0 text-secondary"} />
          <div className="min-w-0">
            <h2 id="expiry-title" className="text-lg font-bold text-text-primary">Validade dos produtos</h2>
            <p className="mt-1 text-sm text-text-secondary">
              {urgent ? "Há produtos vencidos que precisam de atenção." : warning ? "Confira os próximos vencimentos e planeje a reposição." : "Nenhum produto vence nos próximos 15 dias."}
            </p>
            <p className="mt-1 text-xs text-text-secondary">{summary.totalControlados} produtos com controle de validade.</p>
          </div>
        </div>
        <button type="button" onClick={() => onNavigate?.("validade")} className="btn-outline-secondary inline-flex min-h-11 shrink-0 items-center justify-center gap-2">
          Ver validades <ArrowRight size={16} aria-hidden="true" />
        </button>
      </div>
      <dl className="mt-4 grid grid-cols-2 gap-4 border-t border-border-primary pt-4 sm:grid-cols-4">
        {counts.map(([label, value]) => <div key={label}>
          <dt className="text-sm text-text-secondary">{label}</dt>
          <dd className={label === "Vencidos" && urgent ? "mt-1 text-xl font-bold tabular-nums text-primary" : "mt-1 text-xl font-bold tabular-nums text-text-primary"}>{value}</dd>
        </div>)}
      </dl>
      {summary.semDataInformada > 0 && <p className="mt-3 text-sm text-text-secondary">{summary.semDataInformada} produtos ainda precisam de uma data de validade.</p>}
    </section>
  );
}
