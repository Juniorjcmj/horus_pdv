import { useId, type ReactNode } from "react";

type TableScrollAreaProps = { children: ReactNode; label: string; className?: string };

/** Mantém todas as colunas disponíveis por toque, mouse e teclado. */
export default function TableScrollArea({ children, label, className = "" }: TableScrollAreaProps) {
  const helpId = useId();
  return (
    <div role="region" aria-label={label} aria-describedby={helpId} tabIndex={0}
      className={`rg-admin-table min-w-0 overflow-x-auto ${className}`.trim()}>
      <p id={helpId} className="sticky left-0 px-4 py-2 text-xs text-text-secondary lg:sr-only">
        Role para os lados para ver todas as colunas.
      </p>
      {children}
    </div>
  );
}
