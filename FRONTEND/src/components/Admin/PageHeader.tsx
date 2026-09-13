/**
 * Arquivo: src/components/Admin/PageHeader.tsx
 * Objetivo: renderiza título, descrição e ação principal das páginas administrativas.
 * Entradas esperadas: título, descrição e opcional de ação no topo da tela.
 */
import type { ReactNode } from "react";
import { CircleHelp } from "lucide-react";
import { openGuidedTour } from "@/domain/navigation/events";

type PageHeaderProps = {
  title: string;
  description: string;
  action?: ReactNode;
  className?: string;
};

export default function PageHeader({
  title,
  description,
  action,
  className = "",
}: PageHeaderProps) {
  return (
    <header
      data-tour="page-header"
      className={`mb-6 flex flex-col gap-4 xl:flex-row xl:items-start xl:justify-between ${className}`.trim()}
    >
      <div className="min-w-0 shrink-0">
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-bold tracking-tight text-text-primary sm:text-3xl whitespace-nowrap">
            {title}
          </h1>
          <button
            type="button"
            onClick={openGuidedTour}
            className="hidden h-7 shrink-0 items-center gap-1.5 rounded-full border border-secondary/35 bg-bg-light px-2.5 text-xs font-semibold text-secondary transition hover:bg-secondary/10 lg:inline-flex"
            aria-label="Abrir tour da tela"
            title="Tour da tela"
          >
            <CircleHelp size={13} />
            <span>Tour da tela</span>
          </button>
        </div>
        <p className="mt-1.5 max-w-2xl text-sm text-text-secondary">
          {description}
        </p>
      </div>
      {action ? (
        <div
          data-tour="page-header-action"
          className="flex flex-wrap items-center gap-2 xl:justify-end"
        >
          {action}
        </div>
      ) : null}
    </header>
  );
}
