/**
 * Arquivo: src/layout/PageLayout.tsx
 * Objetivo: padroniza largura máxima e espaçamento lateral das páginas.
 * Entradas esperadas: children e opcional de tamanho visual do container.
 */
import type { ReactNode } from "react";

type PageLayoutSize = "default" | "wide";

type PageLayoutProps = {
  children: ReactNode;
  size?: PageLayoutSize;
  className?: string;
};

const SIZE_CLASS: Record<PageLayoutSize, string> = {
  default: "max-w-[1440px]",
  wide: "max-w-[1600px]",
};

export default function PageLayout({
  children,
  size = "default",
  className = "",
}: PageLayoutProps) {
  return (
    <section
      className={`mx-auto min-w-0 w-full px-3 sm:px-4 md:px-5 lg:px-5 2xl:px-4 ${SIZE_CLASS[size]} ${className}`.trim()}
    >
      {children}
    </section>
  );
}
