/**
 * Arquivo: src/components/Pagination/TablePagination.tsx
 * Objetivo: exibe paginação reutilizável para tabelas com controle de página e tamanho de lote.
 * Entradas esperadas: recebe totais, página atual, quantidade por página e callbacks de mudança.
 */

type TablePaginationProps = {
  totalItems: number;
  currentPage: number;
  itemsPerPage: number;
  onPageChange: (page: number) => void;
  onItemsPerPageChange: (value: number) => void;
  itemsPerPageOptions?: number[];
};

function createPageItems(totalPages: number, currentPage: number): Array<number | string> {
  // Para poucas páginas, exibe todas sem truncar.
  if (totalPages <= 7) {
    return Array.from({ length: totalPages }, (_, index) => index + 1);
  }

  const items: Array<number | string> = [1];
  const left = Math.max(2, currentPage - 1);
  const right = Math.min(totalPages - 1, currentPage + 1);

  if (left > 2) items.push("left-ellipsis");
  for (let page = left; page <= right; page += 1) items.push(page);
  if (right < totalPages - 1) items.push("right-ellipsis");

  items.push(totalPages);
  return items;
}

export default function TablePagination({
  totalItems,
  currentPage,
  itemsPerPage,
  onPageChange,
  onItemsPerPageChange,
  itemsPerPageOptions = [10, 20, 50, 100],
}: TablePaginationProps) {
  // Proteções para manter paginação estável em cenários extremos.
  const safeItemsPerPage = Math.max(1, itemsPerPage);
  const totalPages = Math.max(1, Math.ceil(totalItems / safeItemsPerPage));
  const safeCurrentPage = Math.min(Math.max(1, currentPage), totalPages);
  const from = totalItems === 0 ? 0 : (safeCurrentPage - 1) * safeItemsPerPage + 1;
  const to = totalItems === 0 ? 0 : Math.min(totalItems, safeCurrentPage * safeItemsPerPage);
  const pageItems = createPageItems(totalPages, safeCurrentPage);

  return (
    <div
      data-tour="table-pagination"
      className="flex flex-col items-center justify-center gap-3 border-t border-border-primary pt-4 md:flex-row md:items-center md:justify-between"
    >
      <p className="text-center text-xs text-text-secondary md:text-left">
        Mostrando <span className="font-semibold text-text-primary">{from}</span>-<span className="font-semibold text-text-primary">{to}</span> de <span className="font-semibold text-text-primary">{totalItems}</span> registros
      </p>

      <div className="flex w-full flex-col items-center justify-center space-y-5 md:w-auto md:flex-row md:justify-start md:gap-2 md:space-y-0">
        <label className="flex items-center gap-2 text-xs text-text-secondary">
          Linhas por página
          <select
            value={safeItemsPerPage}
            onChange={(event) => onItemsPerPageChange(Number(event.target.value))}
            className="select-field min-h-11 px-3 text-sm"
          >
            {itemsPerPageOptions.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </label>

        <nav aria-label="Paginação da lista" className="flex flex-wrap items-center justify-center gap-1">
          <button
            type="button"
            onClick={() => onPageChange(safeCurrentPage - 1)}
            disabled={safeCurrentPage <= 1}
            className="min-h-11 rounded-lg border border-border-primary bg-bg-light px-3 text-sm font-semibold text-text-secondary transition hover:border-accent/40 hover:text-accent disabled:opacity-60"
          >
            Anterior
          </button>

          {pageItems.map((item) =>
            typeof item === "number" ? (
              <button
                key={item}
                type="button"
                onClick={() => onPageChange(item)}
                aria-label={`Página ${item}`}
                aria-current={item === safeCurrentPage ? "page" : undefined}
                className={`min-h-11 min-w-11 rounded-lg border px-3 text-sm font-semibold transition ${
                  item === safeCurrentPage
                    ? "border-accent bg-(--color-action-accent) text-(--color-action-on-accent)"
                    : "border-border-primary bg-bg-light text-text-secondary hover:border-accent/40 hover:text-accent"
                }`}
              >
                {item}
              </button>
            ) : (
              <span key={item} className="px-1 text-xs text-text-tertiary">
                ...
              </span>
            ),
          )}

          <button
            type="button"
            onClick={() => onPageChange(safeCurrentPage + 1)}
            disabled={safeCurrentPage >= totalPages}
            className="min-h-11 rounded-lg border border-border-primary bg-bg-light px-3 text-sm font-semibold text-text-secondary transition hover:border-accent/40 hover:text-accent disabled:opacity-60"
          >
            Próxima
          </button>
        </nav>
      </div>
    </div>
  );
}
