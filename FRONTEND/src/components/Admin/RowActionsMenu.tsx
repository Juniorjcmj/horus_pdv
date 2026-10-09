/**
 * Arquivo: src/components/Admin/RowActionsMenu.tsx
 * Objetivo: renderiza menu contextual de ações por linha com posicionamento automático no viewport.
 * Entradas esperadas: recebe lista de ações, rótulo do gatilho e flag opcional para abrir para cima.
 */

import { LoaderCircle, MoreVertical } from "lucide-react";
import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import usePopupMenu from "@/hooks/usePopupMenu";

type RowActionItem = {
  key: string;
  label: string;
  icon?: ReactNode;
  onClick: () => void | Promise<void>;
  disabled?: boolean;
  loading?: boolean;
  loadingLabel?: string;
  danger?: boolean;
};

type RowActionsMenuProps = {
  items: RowActionItem[];
  triggerLabel?: string;
  forceUpwards?: boolean;
  triggerDataTour?: string;
  portalZIndex?: number;
};

export default function RowActionsMenu({
  items,
  triggerLabel = "Abrir ações",
  forceUpwards = false,
  triggerDataTour,
  portalZIndex,
}: RowActionsMenuProps) {
  // Controle de visibilidade do dropdown de ações.
  const [open, setOpen] = useState(false);
  // Define se o painel deve abrir para cima para evitar corte no viewport.
  const [openUpwards, setOpenUpwards] = useState(false);
  const [panelTop, setPanelTop] = useState(0);
  const [panelLeft, setPanelLeft] = useState(0);
  const menuRef = useRef<HTMLDivElement | null>(null);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const panelRef = useRef<HTMLDivElement | null>(null);
  const hasLoadingAction = items.some((action) => action.loading);
  const panelId = useId();
  usePopupMenu(open, setOpen, triggerRef, panelRef);

  useEffect(() => {
    // Fecha menu ao clicar fora do componente.
    const handleClickOutside = (event: MouseEvent) => {
      const target = event.target as Node;
      if (menuRef.current?.contains(target)) return;
      if (panelRef.current?.contains(target)) return;
      setOpen(false);
    };

    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, []);

  useEffect(() => {
    if (!open) return;

    // Mede espaço disponível para decidir a direção de abertura do painel.
    const measurePlacement = () => {
      const triggerRect = triggerRef.current?.getBoundingClientRect();
      if (!triggerRect) return;

      const viewportHeight = window.innerHeight;
      const viewportWidth = window.innerWidth;
      const panelRect = panelRef.current?.getBoundingClientRect();
      const panelHeight = panelRect?.height ?? 220;
      const panelWidth = panelRect?.width ?? 176;
      const spaceBelow = viewportHeight - triggerRect.bottom;
      const spaceAbove = triggerRect.top;
      const required = panelHeight + 12;
      const shouldOpenUpwards =
        forceUpwards || (spaceBelow < required && spaceAbove > spaceBelow);
      const nextTop = shouldOpenUpwards
        ? triggerRect.top - panelHeight - 8
        : triggerRect.bottom + 8;
      const nextLeft = triggerRect.right - panelWidth;

      setOpenUpwards(shouldOpenUpwards);
      setPanelTop(Math.max(8, Math.min(nextTop, viewportHeight - panelHeight - 8)));
      setPanelLeft(Math.max(8, Math.min(nextLeft, viewportWidth - panelWidth - 8)));
    };

    const rafId = window.requestAnimationFrame(measurePlacement);
    window.addEventListener("resize", measurePlacement);
    window.addEventListener("scroll", measurePlacement, true);

    return () => {
      window.cancelAnimationFrame(rafId);
      window.removeEventListener("resize", measurePlacement);
      window.removeEventListener("scroll", measurePlacement, true);
    };
  }, [forceUpwards, open]);

  return (
    <div className="relative inline-flex" ref={menuRef}>
      <button
        ref={triggerRef}
        type="button"
        data-tour={triggerDataTour}
        disabled={hasLoadingAction}
        onClick={() => {
          setOpen((current) => !current);
        }}
        aria-label={triggerLabel}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        title="Ações"
        className="inline-flex h-11 w-11 items-center justify-center rounded-xl border border-border-primary text-text-secondary transition hover:bg-hover-light hover:text-text-primary disabled:cursor-not-allowed disabled:opacity-70"
      >
        {hasLoadingAction ? (
          <LoaderCircle size={16} className="animate-spin" aria-hidden="true" />
        ) : (
          <MoreVertical size={16} />
        )}
      </button>

      {open
        ? createPortal(
            <div
              ref={panelRef}
              id={panelId}
              role="menu"
              aria-label={triggerLabel}
              style={{
                top: panelTop,
                left: panelLeft,
                ...(typeof portalZIndex === "number" ? { zIndex: portalZIndex } : {}),
              }}
              className="fixed z-layer-popover max-h-[calc(100dvh-1rem)] min-w-44 overflow-y-auto rounded-xl border border-border-secondary bg-bg-light p-1.5 shadow-lg"
              data-placement={openUpwards ? "top" : "bottom"}
            >
              {items.map((action) => (
                <button
                  key={action.key}
                  type="button"
                  role="menuitem"
                  disabled={action.disabled || action.loading}
                  onClick={() => {
                    if (action.disabled || action.loading) return;
                    setOpen(false);
                    triggerRef.current?.focus();
                    void action.onClick();
                  }}
                  className={`flex min-h-11 w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm font-semibold transition ${
                    action.danger
                      ? "text-primary hover:bg-primary/10"
                      : "text-text-primary hover:bg-accent/10"
                  } disabled:cursor-not-allowed disabled:opacity-50`}
                >
                  {action.loading ? (
                    <LoaderCircle size={13} className="animate-spin" aria-hidden="true" />
                  ) : (
                    action.icon
                  )}
                  {action.loading && action.loadingLabel ? action.loadingLabel : action.label}
                </button>
              ))}
            </div>,
            document.body,
          )
        : null}
    </div>
  );
}
