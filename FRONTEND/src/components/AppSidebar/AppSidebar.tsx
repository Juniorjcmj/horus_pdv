/**
 * Arquivo: src/components/AppSidebar/AppSidebar.tsx
 * Objetivo: renderiza menu lateral no padrão do legado com suporte a desktop colapsado e drawer mobile.
  * Entradas esperadas: recebe página ativa, usuário, estado responsivo e callbacks de navegação/logout.
*/
import {
  Building2,
  BadgeDollarSign,
  CalendarClock,
  ChevronRight,
  ClipboardList,
  CreditCard,
  FileText,
  GraduationCap,
  History,
  House,
  Info,
  Landmark,
  Menu,
  Package,
  PackageCheck,
  Receipt,
  Repeat2,
  ShieldCheck,
  ShoppingCart,
  Tag,
  Truck,
  UserCog,
  UserRoundPlus,
  WalletCards,
} from "lucide-react";
import { useEffect, useRef, useState, type ReactNode } from "react";
import UserMenu from "./UserMenu";

export type PageKey =
  | "home"
  | "cadastro-cliente"
  | "cadastro-fornecedor"
  | "cadastro-produto"
  | "promocoes"
  | "validade"
  | "fiado"
  | "historico-vendas"
  | "relatorios"
  | "vendas"
  | "pedidos"
  | "fiscal"
  | "nfe-emissao"
  | "pagamentos"
  | "estoque"
  | "caixa"
  | "compras"
  | "devolucoes"
  | "crm-fidelidade"
  | "omnichannel"
  | "conta-de-usuario"
  | "minha-empresa"
  | "gerenciamento-geral"
  | "configuracoes"
  | "detalhe-licenca"
  | "sobre-pdv"
  | "aprendizado"
  | "editar-perfil";

type SidebarItemProps = {
  icon: ReactNode;
  label: string;
  badge?: ReactNode;
  active?: boolean;
  collapsed: boolean;
  onClick: () => void;
};

type SidebarSectionTitleProps = {
  label: string;
  collapsed: boolean;
};

function SidebarSectionTitle({ label, collapsed }: SidebarSectionTitleProps) {
  if (collapsed) {
    return <div className="my-1 border-t border-border-primary/70" />;
  }

  return (
    <h2 className="px-3 pt-1 text-[11px] font-semibold uppercase tracking-[0.08em] text-text-secondary/85">
      {label}
    </h2>
  );
}

function SidebarItem({
  icon,
  label,
  badge,
  active,
  collapsed,
  onClick,
}: SidebarItemProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      title={collapsed ? label : undefined}
      aria-current={active ? "page" : undefined}
      className={`w-full flex min-h-11 items-center gap-3 p-2.5 rounded-xl transition-colors border text-left ${
        active
          ? "border-secondary/30 bg-accent/12 text-text-primary shadow-sm"
          : "border-transparent hover:border-border-secondary hover:bg-accent/10 hover:text-text-primary"
      }`}
    >
      <div aria-hidden="true" className="shrink-0 text-accent">{icon}</div>
      {!collapsed && <span className="min-w-0 flex-1">{label}</span>}
      {!collapsed && badge}
      {!collapsed && <ChevronRight size={14} className="text-text-secondary" />}
    </button>
  );
}

type AppSidebarProps = {
  collapsed: boolean;
  onToggle: () => void;
  activePage: PageKey;
  onChangePage: (page: PageKey) => void;
  currentUserName: string;
  currentUserRole: string;
  currentUserPermission: string;
  currentUserAvatarUrl: string | null;
  companyName?: string;
  companyCnpj?: string;
  isSuperAdmin?: boolean;
  /** Administrador ou perfil Financeiro: mostra Fiscal NFC-e / NF-e e NF-e Modelo 55. */
  canAccessFiscal?: boolean;
  pendingApprovalsCount?: number;
  onOpenProfile: () => void;
  onOpenSettings: () => void;
  onLogout: () => void;
  onOpenSalesInNewTab: () => void;
  mobileOpen: boolean;
  onCloseMobile: () => void;
};

export default function AppSidebar({
  collapsed: desktopCollapsed,
  onToggle,
  activePage,
  onChangePage,
  currentUserName,
  currentUserRole,
  currentUserPermission,
  currentUserAvatarUrl,
  companyName,
  companyCnpj,
  isSuperAdmin = false,
  canAccessFiscal = false,
  pendingApprovalsCount = 0,
  onOpenProfile,
  onOpenSettings,
  onLogout,
  onOpenSalesInNewTab,
  mobileOpen,
  onCloseMobile,
}: AppSidebarProps) {
  const [isDesktop, setIsDesktop] = useState(() => window.matchMedia("(min-width: 1024px)").matches);
  const asideRef = useRef<HTMLElement | null>(null);
  const collapsed = desktopCollapsed && isDesktop;
  const isCaixaRole = currentUserRole.toLowerCase() === "caixa";

  useEffect(() => {
    const media = window.matchMedia("(min-width: 1024px)");
    const update = () => {
      setIsDesktop(media.matches);
      if (media.matches) onCloseMobile();
    };
    media.addEventListener("change", update);
    return () => media.removeEventListener("change", update);
  }, [onCloseMobile]);

  useEffect(() => {
    if (isDesktop || !mobileOpen) return;
    const previousFocus = document.activeElement as HTMLElement | null;
    const buttons = () => Array.from(document.querySelectorAll<HTMLElement>(
      '#admin-sidebar button:not(:disabled), [data-sidebar-popup] [role="menuitem"]:not(:disabled)',
    )).filter(element => element.getBoundingClientRect().width > 0);
    const frame = requestAnimationFrame(() => asideRef.current?.querySelector<HTMLElement>('nav [aria-current="page"]')?.focus());
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        onCloseMobile();
      } else if (event.key === "Tab") {
        const controls = buttons();
        const first = controls[0];
        const last = controls[controls.length - 1];
        if (event.shiftKey && document.activeElement === first) {
          event.preventDefault();
          last?.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault();
          first?.focus();
        }
      }
    };
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      cancelAnimationFrame(frame);
      document.removeEventListener("keydown", handleKeyDown);
      previousFocus?.focus();
    };
  }, [isDesktop, mobileOpen, onCloseMobile]);

  const handleChangePage = (page: PageKey) => {
    onChangePage(page);
    onCloseMobile();
  };

  return (
    <>
      <aside
        id="admin-sidebar"
        ref={asideRef}
        role={isDesktop ? undefined : "dialog"}
        aria-label="Menu principal"
        aria-modal={!isDesktop && mobileOpen ? true : undefined}
        aria-hidden={!isDesktop && !mobileOpen ? true : undefined}
        inert={!isDesktop && !mobileOpen}
        className={`fixed top-0 left-0 z-layer-sidebar h-dvh shrink-0 bg-bg-light border-r border-border-primary transition-[width,transform] duration-200 motion-reduce:transition-none flex flex-col justify-between ${
          collapsed ? "w-20" : "w-72"
        } ${mobileOpen ? "translate-x-0" : "-translate-x-full"} lg:translate-x-0 lg:static`}
      >
      <div className="flex min-h-0 flex-1 flex-col">
        <div className="flex items-center justify-between p-3.5 border-b border-border-primary">
          {!collapsed ? (
            <div className="flex items-center gap-2.5 min-w-0 pr-2">
              <img
                src="/quack-icon-v5.png"
                alt="Quack Sistemas"
                className="h-9 w-9 rounded-lg object-cover border border-accent/20 shadow-xs shrink-0"
              />
              <div className="min-w-0">
                <h1 className="text-base font-bold tracking-tight text-accent truncate leading-tight">
                  Quack Sistemas
                </h1>
                <p className="text-[11px] text-text-secondary truncate" title={companyName || "Soluções para seu negócio"}>
                  {companyName || "Soluções para seu negócio"}
                </p>
              </div>
            </div>
          ) : (
            <div className="mx-auto py-0.5">
              <img
                src="/quack-icon-v5.png"
                alt="Quack Sistemas"
                className="h-8 w-8 rounded-lg object-cover border border-accent/20 shadow-xs"
              />
            </div>
          )}

          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={onToggle}
              aria-label={collapsed ? "Expandir menu lateral" : "Recolher menu lateral"}
              className="hidden lg:inline-flex h-11 w-11 items-center justify-center rounded-lg hover:bg-accent/10"
            >
              <Menu size={20} className="text-accent" />
            </button>

            <button
              type="button"
              onClick={onCloseMobile}
              className="inline-flex h-11 w-11 items-center justify-center rounded-lg hover:bg-accent/10 lg:hidden"
              aria-label="Fechar menu"
            >
              <Menu size={20} className="text-accent" />
            </button>
          </div>
        </div>

        <nav aria-label="Navegação principal" className="flex-1 min-h-0 space-y-4 overflow-y-auto overflow-x-hidden px-2 py-3 text-sm font-medium">
          {isCaixaRole ? (
            <div className="space-y-2">
              <SidebarSectionTitle label="Caixa" collapsed={collapsed} />
              <SidebarItem
                icon={<ShoppingCart size={20} />}
                label="Iniciar Vendas"
                active={activePage === "vendas"}
                collapsed={collapsed}
                onClick={onOpenSalesInNewTab}
              />
              <SidebarItem
                icon={<Landmark size={20} />}
                label="Abertura e Fechamento"
                active={activePage === "caixa"}
                collapsed={collapsed}
                onClick={() => handleChangePage("caixa")}
              />
              <SidebarItem
                icon={<GraduationCap size={20} />}
                label="Aprendizado"
                active={activePage === "aprendizado"}
                collapsed={collapsed}
                onClick={() => handleChangePage("aprendizado")}
              />
            </div>
          ) : (
            <>
              <div className="space-y-2">
                <SidebarSectionTitle label="Principal" collapsed={collapsed} />

                <SidebarItem
                  icon={<House size={20} />}
                  label="Visão geral"
                  active={activePage === "home"}
                  collapsed={collapsed}
                  onClick={() => handleChangePage("home")}
                />
              </div>

              {isSuperAdmin && (
                <div className="space-y-2">
                  <SidebarSectionTitle label="Administração Master" collapsed={collapsed} />
                  <SidebarItem
                    icon={<ShieldCheck size={20} className="text-amber-400" />}
                    label="Gerenciamento Geral"
                    badge={
                      pendingApprovalsCount > 0 ? (
                        <span className="px-1.5 py-0.5 rounded-full text-[10px] font-bold bg-amber-500 text-slate-950 animate-pulse">
                          {pendingApprovalsCount}
                        </span>
                      ) : undefined
                    }
                    active={activePage === "gerenciamento-geral"}
                    collapsed={collapsed}
                    onClick={() => handleChangePage("gerenciamento-geral")}
                  />
                </div>
              )}

              <div className="space-y-2">
                <SidebarSectionTitle label="Cadastros" collapsed={collapsed} />
                <SidebarItem
                  icon={<UserRoundPlus size={20} />}
                  label="Cliente"
                  active={activePage === "cadastro-cliente"}
                  collapsed={collapsed}
                  onClick={() => handleChangePage("cadastro-cliente")}
                />
                <SidebarItem
                  icon={<Truck size={20} />}
                  label="Fornecedor"
                  active={activePage === "cadastro-fornecedor"}
                  collapsed={collapsed}
                  onClick={() => handleChangePage("cadastro-fornecedor")}
                />
                <SidebarItem
                  icon={<Package size={20} />}
                  label="Produto"
                  active={activePage === "cadastro-produto"}
                  collapsed={collapsed}
                  onClick={() => handleChangePage("cadastro-produto")}
                />
                <SidebarItem
                  icon={<UserCog size={20} />}
                  label="Contas de Usuários"
                  active={activePage === "conta-de-usuario"}
                  collapsed={collapsed}
                  onClick={() => handleChangePage("conta-de-usuario")}
                />
              </div>

              <div className="space-y-2">
                <SidebarSectionTitle label="Operação" collapsed={collapsed} />
                <SidebarItem
                  icon={<History size={20} />}
                  label="Histórico de Vendas"
                  active={activePage === "historico-vendas"}
                  collapsed={collapsed}
                  onClick={() => handleChangePage("historico-vendas")}
                />
                <SidebarItem
                  icon={<Tag size={20} />}
                  label="Promoções"
                  active={activePage === "promocoes"}
                  collapsed={collapsed}
                  onClick={() => handleChangePage("promocoes")}
                />
                <SidebarItem
                  icon={<CalendarClock size={20} />}
                  label="Controle de Validade"
                  active={activePage === "validade"}
                  collapsed={collapsed}
                  onClick={() => handleChangePage("validade")}
                />
                <SidebarItem
                  icon={<Landmark size={20} />}
                  label="Fiado / Conta Corrente"
                  active={activePage === "fiado"}
                  collapsed={collapsed}
                  onClick={() => handleChangePage("fiado")}
                />
                <SidebarItem
                  icon={<FileText size={20} />}
                  label="Relatórios"
                  active={activePage === "relatorios"}
                  collapsed={collapsed}
                  onClick={() => handleChangePage("relatorios")}
                />
                <SidebarItem
                  icon={<ShoppingCart size={20} />}
                  label="Iniciar Vendas"
                  active={activePage === "vendas"}
                  collapsed={collapsed}
                  onClick={onOpenSalesInNewTab}
                />
                <SidebarItem
                  icon={<ClipboardList size={20} />}
                  label="Novo Pedido"
                  active={activePage === "pedidos"}
                  collapsed={collapsed}
                  onClick={() => handleChangePage("pedidos")}
                />
              </div>

              <div className="space-y-2">
                <SidebarSectionTitle label="Gestão Avançada" collapsed={collapsed} />
                {canAccessFiscal ? (
                  <>
                    <SidebarItem
                      icon={<Receipt size={20} />}
                      label="Fiscal NFC-e / NF-e"
                      active={activePage === "fiscal"}
                      collapsed={collapsed}
                      onClick={() => handleChangePage("fiscal")}
                    />
                    <SidebarItem
                      icon={<FileText size={20} />}
                      label="NF-e Modelo 55"
                      active={activePage === "nfe-emissao"}
                      collapsed={collapsed}
                      onClick={() => handleChangePage("nfe-emissao")}
                    />
                  </>
                ) : null}
                <SidebarItem
                  icon={<CreditCard size={20} />}
                  label="Pagamentos Integrados"
                  active={activePage === "pagamentos"}
                  collapsed={collapsed}
                  onClick={() => handleChangePage("pagamentos")}
                />
                <SidebarItem
                  icon={<PackageCheck size={20} />}
                  label="Estoque e Inventário"
                  active={activePage === "estoque"}
                  collapsed={collapsed}
                  onClick={() => handleChangePage("estoque")}
                />
                <SidebarItem
                  icon={<Landmark size={20} />}
                  label="Abertura e Fechamento"
                  active={activePage === "caixa"}
                  collapsed={collapsed}
                  onClick={() => handleChangePage("caixa")}
                />
                <SidebarItem
                  icon={<BadgeDollarSign size={20} />}
                  label="Compras e Reposição"
                  active={activePage === "compras"}
                  collapsed={collapsed}
                  onClick={() => handleChangePage("compras")}
                />
                <SidebarItem
                  icon={<Repeat2 size={20} />}
                  label="Trocas e Devoluções"
                  active={activePage === "devolucoes"}
                  collapsed={collapsed}
                  onClick={() => handleChangePage("devolucoes")}
                />
              </div>

              <div className="space-y-2">
                <SidebarSectionTitle label="Sistema" collapsed={collapsed} />
                <SidebarItem
                  icon={<Building2 size={20} />}
                  label="Minha Empresa"
                  active={activePage === "minha-empresa"}
                  collapsed={collapsed}
                  onClick={() => handleChangePage("minha-empresa")}
                />
                <SidebarItem
                  icon={<WalletCards size={20} />}
                  label="Detalhes da Licença"
                  active={activePage === "detalhe-licenca"}
                  collapsed={collapsed}
                  onClick={() => handleChangePage("detalhe-licenca")}
                />
                <SidebarItem
                  icon={<Info size={20} />}
                  label="Sobre PDV"
                  active={activePage === "sobre-pdv"}
                  collapsed={collapsed}
                  onClick={() => handleChangePage("sobre-pdv")}
                />
                <SidebarItem
                  icon={<GraduationCap size={20} />}
                  label="Aprendizado"
                  active={activePage === "aprendizado"}
                  collapsed={collapsed}
                  onClick={() => handleChangePage("aprendizado")}
                />
              </div>
            </>
          )}
        </nav>
      </div>

      <div className="p-3 border-t border-border-primary">
        <UserMenu
          collapsed={collapsed}
          currentUserName={currentUserName}
          currentUserPermission={currentUserPermission}
          companyName={companyName}
          companyCnpj={companyCnpj}
          hideCompanyLinks={isCaixaRole}
          avatarUrl={currentUserAvatarUrl}
          onOpenProfile={() => {
            onOpenProfile();
            onCloseMobile();
          }}
          onOpenSettings={() => {
            onOpenSettings();
            onCloseMobile();
          }}
          onOpenCompany={() => handleChangePage("minha-empresa")}
          onOpenLicense={() => handleChangePage("detalhe-licenca")}
          onOpenAbout={() => handleChangePage("sobre-pdv")}
          onLogout={() => {
            onLogout();
            onCloseMobile();
          }}
        />
      </div>
      </aside>
    </>
  );
}
