/**
 * Arquivo: src/utils/salesDailyReport.ts
 * Objetivo: consolida vendas + sangrias/reforços por dia (detalhamento diário do histórico de vendas)
 *           e gera o HTML de impressão em bobina 80mm, com sangria/reforço no mesmo formato de uma venda.
 * Entradas esperadas: vendas e movimentos já normalizados (valores numéricos) pela tela.
 */

export type DailySale = {
  saleNumber: string;
  dateKey: string;
  time: string;
  operatorName: string;
  customerName: string;
  paymentType: string;
  total: number;
  cancelled: boolean;
  /** Valor de cada forma de pagamento (venda com mais de uma forma). Sem isso vale paymentType x total. */
  payments?: Array<{ type: string; amount: number }>;
};

export type DailyMovement = {
  id: string;
  tipo: "Reforco" | "Sangria";
  dateKey: string;
  time: string;
  valor: number;
  motivo: string;
  operatorName: string;
};

export type DailySummary = {
  dateKey: string;
  sales: DailySale[];
  movements: DailyMovement[];
  salesCount: number;
  cancelledCount: number;
  totalSold: number;
  cancelledTotal: number;
  byPayment: Record<string, number>;
  reforcos: number;
  sangrias: number;
  /** Dinheiro em gaveta gerado no dia: vendas em dinheiro + reforços - sangrias. */
  netCash: number;
};

/** Converte "dd/MM/yyyy HH:mm[:ss]" ou ISO "yyyy-MM-ddTHH:mm" em chave AAAA-MM-DD + hora. */
export function parseDateParts(value: string): { dateKey: string; time: string } | null {
  const br = /^(\d{2})\/(\d{2})\/(\d{4})(?:[ T]+(\d{2}:\d{2}))?/.exec(value?.trim() ?? "");
  if (br) return { dateKey: `${br[3]}-${br[2]}-${br[1]}`, time: br[4] ?? "" };
  const iso = /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}:\d{2}))?/.exec(value?.trim() ?? "");
  if (iso) return { dateKey: `${iso[1]}-${iso[2]}-${iso[3]}`, time: iso[4] ?? "" };
  return null;
}

export function formatDateKey(dateKey: string) {
  const [y, m, d] = dateKey.split("-");
  return `${d}/${m}/${y}`;
}

export function buildDailySummaries(sales: DailySale[], movements: DailyMovement[]): DailySummary[] {
  const map = new Map<string, DailySummary>();
  const ensure = (dateKey: string) => {
    let day = map.get(dateKey);
    if (!day) {
      day = {
        dateKey,
        sales: [],
        movements: [],
        salesCount: 0,
        cancelledCount: 0,
        totalSold: 0,
        cancelledTotal: 0,
        byPayment: {},
        reforcos: 0,
        sangrias: 0,
        netCash: 0,
      };
      map.set(dateKey, day);
    }
    return day;
  };

  for (const sale of sales) {
    const day = ensure(sale.dateKey);
    day.sales.push(sale);
    if (sale.cancelled) {
      day.cancelledCount += 1;
      day.cancelledTotal += sale.total;
    } else {
      day.salesCount += 1;
      day.totalSold += sale.total;
      if (sale.payments && sale.payments.length > 0) {
        for (const payment of sale.payments) {
          const key = payment.type.toLowerCase() || "outros";
          day.byPayment[key] = (day.byPayment[key] ?? 0) + payment.amount;
        }
      } else {
        const key = sale.paymentType.toLowerCase() || "outros";
        day.byPayment[key] = (day.byPayment[key] ?? 0) + sale.total;
      }
    }
  }

  for (const movement of movements) {
    const day = ensure(movement.dateKey);
    day.movements.push(movement);
    if (movement.tipo === "Sangria") day.sangrias += movement.valor;
    else day.reforcos += movement.valor;
  }

  const days = [...map.values()];
  for (const day of days) {
    day.sales.sort((a, b) => a.time.localeCompare(b.time));
    day.movements.sort((a, b) => a.time.localeCompare(b.time));
    day.netCash = (day.byPayment.dinheiro ?? 0) + day.reforcos - day.sangrias;
  }
  return days.sort((a, b) => b.dateKey.localeCompare(a.dateKey));
}

function escapeHtml(value: string) {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

export type DailyPrintOptions = {
  companyName: string;
  periodLabel: string;
  filtersLabel?: string;
  /** Quando true, sangrias e reforços saem na impressão como lançamentos, igual às vendas. */
  includeMovements: boolean;
  formatMoney: (value: number) => string;
  paymentLabel: (type: string) => string;
};

export function buildDailyReportPrintHtml(days: DailySummary[], options: DailyPrintOptions) {
  const { formatMoney, paymentLabel, includeMovements } = options;
  const ordered = [...days].sort((a, b) => a.dateKey.localeCompare(b.dateKey));

  const grand = ordered.reduce(
    (acc, day) => {
      acc.sold += day.totalSold;
      acc.cancelled += day.cancelledTotal;
      acc.reforcos += day.reforcos;
      acc.sangrias += day.sangrias;
      return acc;
    },
    { sold: 0, cancelled: 0, reforcos: 0, sangrias: 0 },
  );

  const dayBlocks = ordered
    .map((day) => {
      type Entry = { time: string; html: string };
      const entries: Entry[] = day.sales.map((sale) => ({
        time: sale.time,
        html: `
          <div class="line${sale.cancelled ? " cancelled" : ""}">
            <span>${escapeHtml(sale.time)} VENDA ${escapeHtml(sale.saleNumber)}${sale.cancelled ? " (CANC)" : ""}</span>
            <span>${formatMoney(sale.total)}</span>
          </div>
          <div class="meta">${escapeHtml(paymentLabel(sale.paymentType))} - ${escapeHtml(sale.operatorName || "-")}</div>`,
      }));

      if (includeMovements) {
        for (const movement of day.movements) {
          const isSangria = movement.tipo === "Sangria";
          entries.push({
            time: movement.time,
            html: `
              <div class="line">
                <span>${escapeHtml(movement.time)} ${isSangria ? "SANGRIA" : "REFORCO"}</span>
                <span>${isSangria ? "-" : "+"}${formatMoney(movement.valor)}</span>
              </div>
              <div class="meta">${escapeHtml(movement.motivo || "-")} - ${escapeHtml(movement.operatorName || "-")}</div>`,
          });
        }
      }
      entries.sort((a, b) => a.time.localeCompare(b.time));

      const payments = Object.entries(day.byPayment)
        .map(
          ([type, total]) =>
            `<div class="line"><span>${escapeHtml(paymentLabel(type))}</span><span>${formatMoney(total)}</span></div>`,
        )
        .join("");

      return `
        <div class="divider"></div>
        <section>
          <div class="bold">DIA ${formatDateKey(day.dateKey)}</div>
          ${entries.map((e) => e.html).join("") || '<div class="meta">Sem lancamentos.</div>'}
          <div class="divider thin"></div>
          <div class="line bold"><span>Vendas (${day.salesCount})</span><span>${formatMoney(day.totalSold)}</span></div>
          ${day.cancelledCount > 0 ? `<div class="line"><span>Canceladas (${day.cancelledCount})</span><span>${formatMoney(day.cancelledTotal)}</span></div>` : ""}
          ${payments}
          ${
            includeMovements
              ? `<div class="line"><span>Reforcos</span><span>+${formatMoney(day.reforcos)}</span></div>
                 <div class="line"><span>Sangrias</span><span>-${formatMoney(day.sangrias)}</span></div>
                 <div class="line bold"><span>Saldo dinheiro</span><span>${formatMoney(day.netCash)}</span></div>`
              : ""
          }
        </section>`;
    })
    .join("");

  return `<!doctype html>
<html lang="pt-BR">
  <head>
    <meta charset="utf-8" />
    <title>Detalhamento diario</title>
    <style>
      @page { size: 80mm auto; margin: 4mm; }
      * { box-sizing: border-box; }
      body { margin: 0; color: #020617; font: 12px/1.25 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; -webkit-print-color-adjust: exact; print-color-adjust: exact; }
      .receipt { width: 72mm; margin: 0 auto; }
      .center { text-align: center; }
      .brand { font-size: 14px; font-weight: 800; text-transform: uppercase; }
      .divider { border-top: 1px dashed #475569; margin: 10px 0; }
      .divider.thin { margin: 6px 0; }
      .line { display: flex; justify-content: space-between; gap: 8px; margin-top: 4px; }
      .meta { padding-left: 4px; font-size: 10px; color: #334155; }
      .bold { font-weight: 800; }
      .cancelled { text-decoration: line-through; }
    </style>
  </head>
  <body>
    <main class="receipt">
      <section class="center">
        <div class="brand">${escapeHtml(options.companyName)}</div>
        <div>DETALHAMENTO DIARIO - NAO FISCAL</div>
        <div>Periodo: ${escapeHtml(options.periodLabel)}</div>
        ${options.filtersLabel ? `<div class="meta">${escapeHtml(options.filtersLabel)}</div>` : ""}
      </section>
      ${dayBlocks || '<div class="divider"></div><p class="center">Nenhum registro no periodo.</p>'}
      <div class="divider"></div>
      <section>
        <div class="line bold"><span>TOTAL VENDIDO</span><span>R$ ${formatMoney(grand.sold)}</span></div>
        ${grand.cancelled > 0 ? `<div class="line"><span>Canceladas</span><span>R$ ${formatMoney(grand.cancelled)}</span></div>` : ""}
        ${
          includeMovements
            ? `<div class="line"><span>Reforcos</span><span>+R$ ${formatMoney(grand.reforcos)}</span></div>
               <div class="line"><span>Sangrias</span><span>-R$ ${formatMoney(grand.sangrias)}</span></div>`
            : ""
        }
      </section>
      <div class="divider"></div>
      <p class="center">Impresso em ${escapeHtml(new Date().toLocaleString("pt-BR", { timeZone: "America/Sao_Paulo" }))}</p>
    </main>
    <script>
      window.addEventListener("load", function () {
        setTimeout(function () { window.focus(); window.print(); }, 200);
      });
    </script>
  </body>
</html>`;
}

export function printDailyReport(days: DailySummary[], options: DailyPrintOptions) {
  const html = buildDailyReportPrintHtml(days, options);
  const blob = new Blob([html], { type: "text/html;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const popup = window.open(url, "_blank", "width=420,height=720");
  if (popup) {
    popup.addEventListener("afterprint", () => {
      popup.close();
      URL.revokeObjectURL(url);
    });
    return true;
  }
  URL.revokeObjectURL(url);
  return false;
}
