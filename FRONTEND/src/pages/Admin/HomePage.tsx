/** Visão geral da loja: indicadores, alertas e acesso às rotinas administrativas. */
import {
  ArrowRight, BadgeDollarSign, CreditCard, FileText, History, Landmark,
  Package, PackageCheck, Receipt, RefreshCw, Repeat2, ShoppingCart, UserRoundPlus, type LucideIcon,
} from "lucide-react";
import type { PageKey } from "@/components/AppSidebar/AppSidebar";
import { hasFinanceiroAccess } from "@/utils/authStorage";
import PageHeader from "@/components/Admin/PageHeader";
import KpiTrendCard from "@/components/Admin/KpiTrendCard";
import ListState from "@/components/Admin/ListState";
import ValidadeAlertWidget from "@/components/Admin/ValidadeAlertWidget";
import PageLayout from "@/layout/PageLayout";
import { homeService } from "@/services/api/homeService";
import useRemoteList from "@/hooks/useRemoteList";

type Shortcut = { title: string; description: string; icon: LucideIcon; page: PageKey };
const shortcuts: Shortcut[] = [
  { title: "Produtos", description: "Cadastrar produtos e ajustar preços", icon: Package, page: "cadastro-produto" },
  { title: "Clientes", description: "Cadastrar e consultar clientes", icon: UserRoundPlus, page: "cadastro-cliente" },
  { title: "Histórico de vendas", description: "Consultar vendas finalizadas", icon: History, page: "historico-vendas" },
  { title: "Relatórios", description: "Analisar os resultados da loja", icon: FileText, page: "relatorios" },
];
const managementShortcuts: Shortcut[] = [
  { title: "Caixa", description: "Abertura, movimentações e fechamento", icon: Landmark, page: "caixa" },
  { title: "Estoque e inventário", description: "Conferir quantidades e movimentações", icon: PackageCheck, page: "estoque" },
  { title: "Compras e reposição", description: "Pedidos, recebimento e custos", icon: BadgeDollarSign, page: "compras" },
  { title: "Trocas e devoluções", description: "Consultar e registrar devoluções", icon: Repeat2, page: "devolucoes" },
  { title: "Fiscal NFC-e / NF-e", description: "Consultar documentos e emissões", icon: Receipt, page: "fiscal" },
  { title: "Pagamentos integrados", description: "Pagamentos e conciliação", icon: CreditCard, page: "pagamentos" },
];
const loadCards = async () => (await homeService.get())?.cards ?? [];

function ShortcutList({ items, onNavigate }: { items: Shortcut[]; onNavigate?: (page: PageKey) => void }) {
  return (
    <ul className="grid gap-x-6 sm:grid-cols-2">
      {items.map(({ title, description, icon: Icon, page }) => (
        <li key={page} className="min-w-0 border-b border-border-primary last:border-b-0">
          <button type="button" onClick={() => onNavigate?.(page)}
            className="group flex min-h-20 w-full items-center gap-3 rounded-lg px-2 py-4 text-left transition-colors hover:bg-hover-light">
            <Icon size={20} aria-hidden="true" className="shrink-0 text-secondary" />
            <span className="min-w-0 flex-1">
              <span className="block text-sm font-semibold text-text-primary">{title}</span>
              <span className="mt-1 block text-sm text-text-secondary">{description}</span>
            </span>
            <ArrowRight size={16} aria-hidden="true" className="shrink-0 text-secondary" />
          </button>
        </li>
      ))}
    </ul>
  );
}

type HomePageProps = { onNavigate?: (page: PageKey) => void; onOpenSalesInNewTab?: () => void };
export default function HomePage({ onNavigate, onOpenSalesInNewTab }: HomePageProps) {
  const { items: cards, isLoading, error, reload } = useRemoteList(loadCards, "Não foi possível atualizar os indicadores da loja.");
  return (
    <PageLayout className="space-y-4 py-4 md:space-y-6 md:py-6 lg:py-8">
      <PageHeader title="Visão geral" description="Acompanhe os resultados e acesse as rotinas da loja."
        action={<>
          <button type="button" className="btn-outline-secondary inline-flex min-h-11 items-center gap-2" disabled={isLoading} onClick={() => void reload()}>
            <RefreshCw size={16} aria-hidden="true" /> Atualizar indicadores
          </button>
          <button type="button" className="btn-primary inline-flex min-h-11 items-center gap-2" onClick={onOpenSalesInNewTab}>
            <ShoppingCart size={18} aria-hidden="true" /> Iniciar vendas
          </button>
        </>} />
      <section aria-label="Indicadores da loja" aria-busy={isLoading}>
        {isLoading ? <ListState title="Carregando indicadores…" loading />
          : error ? <ListState title={error} description="Verifique a conexão e tente novamente. Os atalhos continuam disponíveis." error actionLabel="Tentar novamente" onAction={() => void reload()} />
          : cards.length === 0 ? <ListState title="Nenhum indicador disponível" description="Atualize os indicadores após registrar a movimentação da loja." />
          : <div className="grid grid-cols-2 gap-3 xl:grid-cols-4">
            {cards.map(card => <KpiTrendCard key={card.label} label={card.label} value={card.value} hint={card.helper} color={card.color} trend={card.trend} />)}
          </div>}
      </section>
      <ValidadeAlertWidget onNavigate={onNavigate} />
      <section className="card p-4 md:p-5" aria-labelledby="home-shortcuts">
        <h2 id="home-shortcuts" className="text-lg font-bold text-text-primary">Ações rápidas</h2>
        <div className="mt-3"><ShortcutList items={shortcuts} onNavigate={onNavigate} /></div>
      </section>
      <section className="card p-4 md:p-5" aria-labelledby="home-management">
        <h2 id="home-management" className="text-lg font-bold text-text-primary">Gestão da loja</h2>
        <div className="mt-3"><ShortcutList items={managementShortcuts.filter(item => item.page !== "fiscal" || hasFinanceiroAccess())} onNavigate={onNavigate} /></div>
      </section>
    </PageLayout>
  );
}
