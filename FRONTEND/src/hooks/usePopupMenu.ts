import { useEffect, type Dispatch, type RefObject, type SetStateAction } from "react";

/** Mantém o foco e o teclado nos menus que são renderizados fora da tabela/sidebar. */
export default function usePopupMenu(
  open: boolean,
  setOpen: Dispatch<SetStateAction<boolean>>,
  triggerRef: RefObject<HTMLButtonElement | null>,
  panelRef: RefObject<HTMLDivElement | null>,
) {
  useEffect(() => {
    if (!open) return;
    const panel = panelRef.current;
    if (!panel) return;
    const items = () => Array.from(panel.querySelectorAll<HTMLButtonElement>('[role="menuitem"]:not(:disabled)'));
    const frame = requestAnimationFrame(() => items()[0]?.focus());
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape" || event.key === "Tab") {
        if (event.key === "Escape") {
          event.preventDefault();
          event.stopPropagation();
        }
        setOpen(false);
        triggerRef.current?.focus();
        return;
      }
      const buttons = items();
      if (!buttons.length || !["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
      event.preventDefault();
      const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
      const next = event.key === "Home" ? 0 : event.key === "End" ? buttons.length - 1
        : (index + (event.key === "ArrowDown" ? 1 : -1) + buttons.length) % buttons.length;
      buttons[next]?.focus();
    };
    panel.addEventListener("keydown", handleKeyDown);
    return () => {
      cancelAnimationFrame(frame);
      panel.removeEventListener("keydown", handleKeyDown);
    };
  }, [open, setOpen, triggerRef, panelRef]);
}
