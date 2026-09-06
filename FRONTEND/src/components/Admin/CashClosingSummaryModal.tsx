/**
 * Arquivo: src/components/Admin/CashClosingSummaryModal.tsx
 * Objetivo: exibir (e opcionalmente imprimir) o resumo de conferência de uma sessão de caixa —
 *           esperado x contado, diferença, sangrias/reforços e vendas por forma de pagamento.
 * Entradas esperadas: recebe a sessão de caixa (aberta ou já fechada) e o nome da empresa.
 */
import { ClipboardList, Printer, X } from "lucide-react";
import type { CashRegisterSessionDto } from "@/services/api/cashRegisterService";

const PAYMENT_LABELS: Record<string, string> = {
  dinheiro: "Dinheiro",
  pix: "PIX",
  debito: "Cartão Débito",
  credito: "Cartão Crédito",
};

function paymentLabel(paymentType: string) {
  return PAYMENT_LABELS[paymentType.toLowerCase()] ?? paymentType;
}

function formatDateTime(value?: string | null) {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("pt-BR", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

function escapeHtml(value: string) {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

function buildSummaryPrintHtml(session: CashRegisterSessionDto, companyName: string) {
  const movimentosRows = session.movimentos
    .map(
      (item) => `
        <tr>
          <td>${escapeHtml(formatDateTime(item.createdAt))}</td>
          <td>${item.tipo === "Sangria" ? "Sangria" : "Reforço"}</td>
          <td class="right">R$ ${escapeHtml(item.valor)}</td>
          <td>${escapeHtml(item.motivo)}</td>
          <td>${escapeHtml(item.operatorName)}</td>
        </tr>`,
    )
    .join("");

  const breakdownRows = (session.paymentBreakdown ?? [])
    .map(
      (item) => `
        <tr>
          <td>${escapeHtml(paymentLabel(item.paymentType))}</td>
          <td class="right">R$ ${escapeHtml(item.total)}</td>
        </tr>`,
    )
    .join("");

  const diferenca = session.differenceAmount;
  const temDiferenca = diferenca && diferenca !== "0,00" && diferenca !== "-0,00";

  return `<!doctype html>
<html lang="pt-BR">
  <head>
    <meta charset="utf-8" />
    <title>Fechamento de caixa ${escapeHtml(session.id)}</title>
    <style>
      @page { size: A4; margin: 16mm; }
      * { box-sizing: border-box; }
      body { margin: 0; color: #0f172a; font: 13px/1.4 system-ui, -apple-system, sans-serif; }
      h1 { font-size: 18px; margin: 0 0 2px; }
      h2 { font-size: 13px; text-transform: uppercase; letter-spacing: .04em; color: #475569; margin: 20px 0 8px; }
      .muted { color: #475569; }
      table { width: 100%; border-collapse: collapse; margin-top: 4px; }
      th, td { text-align: left; padding: 5px 6px; border-bottom: 1px solid #e2e8f0; font-size: 12px; }
      th { color: #475569; font-weight: 600; }
      .right { text-align: right; }
      .totals div { display: flex; justify-content: space-between; padding: 4px 0; }
      .totals .bold { font-weight: 700; }
      .diff { margin-top: 10px; padding: 10px; border-radius: 8px; background: ${temDiferenca ? "#fef2f2" : "#f0fdf4"}; }
    </style>
  </head>
  <body>
    <h1>${escapeHtml(companyName)}</h1>
    <p class="muted">Resumo de fechamento de caixa</p>

    <table>
      <tr><td class="muted">Abertura</td><td>${escapeHtml(formatDateTime(session.openedAt))}</td></tr>
      <tr><td class="muted">Fechamento</td><td>${escapeHtml(formatDateTime(session.closedAt))}</td></tr>
      <tr><td class="muted">Operador (abertura)</td><td>${escapeHtml(session.operatorName || "-")}</td></tr>
      <tr><td class="muted">Fechado por</td><td>${escapeHtml(session.closedByName || "-")}</td></tr>
    </table>

    ${breakdownRows ? `<h2>Vendas por forma de pagamento</h2><table><thead><tr><th>Forma</th><th class="right">Total</th></tr></thead><tbody>${breakdownRows}</tbody></table>` : ""}

    ${movimentosRows ? `<h2>Sangrias e reforços</h2><table><thead><tr><th>Data/hora</th><th>Tipo</th><th class="right">Valor</th><th>Motivo</th><th>Operador</th></tr></thead><tbody>${movimentosRows}</tbody></table>` : ""}

    <h2>Conferência de dinheiro</h2>
    <div class="totals">
      <div><span>Fundo de troco (abertura)</span><span>R$ ${escapeHtml(session.openingAmount)}</span></div>
      <div class="bold"><span>Dinheiro esperado na gaveta</span><span>R$ ${escapeHtml(session.expectedCashAmount || "0,00")}</span></div>
      <div class="bold"><span>Dinheiro contado</span><span>R$ ${escapeHtml(session.closingAmount)}</span></div>
    </div>
    <div class="diff">
      <strong>Diferença: R$ ${escapeHtml(session.differenceAmount || "0,00")}</strong>
      ${session.differenceReason ? `<p class="muted" style="margin:4px 0 0">Motivo: ${escapeHtml(session.differenceReason)}</p>` : ""}
    </div>
    ${session.note ? `<h2>Observação</h2><p>${escapeHtml(session.note)}</p>` : ""}
    <script>window.addEventListener("load", () => window.print());</script>
  </body>
</html>`;
}

export default function CashClosingSummaryModal({
  session,
  companyName,
  onClose,
}: {
  session: CashRegisterSessionDto;
  companyName: string;
  onClose: () => void;
}) {
  const temDiferenca = session.differenceAmount && session.differenceAmount !== "0,00" && session.differenceAmount !== "-0,00";

  const handlePrint = () => {
    const popup = window.open("", "_blank", "width=760,height=900");
    if (!popup) return;
    popup.document.open();
    popup.document.write(buildSummaryPrintHtml(session, companyName));
    popup.document.close();
  };

  return (
    <div className="fixed inset-0 z-layer-dialog flex items-end bg-black/55 px-3 backdrop-blur-sm md:items-center md:justify-center">
      <div className="flex max-h-[90vh] w-full max-w-lg flex-col overflow-hidden rounded-t-2xl border border-border-primary bg-bg-light shadow-2xl md:rounded-2xl">
        <div className="flex items-center justify-between border-b border-border-primary px-4 py-3">
          <div className="flex items-center gap-2">
            <span className="inline-flex h-9 w-9 items-center justify-center rounded-xl bg-accent/10 text-accent">
              <ClipboardList size={18} />
            </span>
            <div>
              <h2 className="text-base font-semibold text-text-primary">Resumo do fechamento</h2>
              <p className="text-xs text-text-secondary">{formatDateTime(session.closedAt || session.openedAt)}</p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="inline-flex h-9 w-9 items-center justify-center rounded-lg border border-border-primary text-text-secondary hover:bg-hover-light"
            aria-label="Fechar resumo"
          >
            <X size={16} />
          </button>
        </div>

        <div className="flex-1 space-y-4 overflow-y-auto p-5 text-sm">
          {session.paymentBreakdown && session.paymentBreakdown.length > 0 ? (
            <div>
              <h3 className="mb-1.5 text-xs font-semibold uppercase text-text-tertiary">
                Vendas por forma de pagamento
              </h3>
              <div className="space-y-1">
                {session.paymentBreakdown.map((item) => (
                  <div key={item.paymentType} className="flex justify-between">
                    <span className="text-text-secondary">{paymentLabel(item.paymentType)}</span>
                    <span className="font-medium text-text-primary">R$ {item.total}</span>
                  </div>
                ))}
              </div>
            </div>
          ) : null}

          {session.movimentos.length > 0 ? (
            <div>
              <h3 className="mb-1.5 text-xs font-semibold uppercase text-text-tertiary">
                Sangrias e reforços
              </h3>
              <div className="space-y-2">
                {session.movimentos.map((item) => (
                  <div key={item.id} className="rounded-lg border border-border-primary p-2">
                    <div className="flex justify-between text-xs">
                      <span className={item.tipo === "Sangria" ? "font-semibold text-danger" : "font-semibold text-success"}>
                        {item.tipo === "Sangria" ? "Sangria" : "Reforço"}
                      </span>
                      <span className="font-semibold">R$ {item.valor}</span>
                    </div>
                    <p className="mt-0.5 text-xs text-text-secondary">{item.motivo}</p>
                  </div>
                ))}
              </div>
            </div>
          ) : null}

          <div className="rounded-xl border border-border-primary bg-bg-primary p-3">
            <div className="flex justify-between text-text-secondary">
              <span>Fundo de troco</span>
              <span>R$ {session.openingAmount}</span>
            </div>
            <div className="mt-1 flex justify-between font-semibold text-text-primary">
              <span>Dinheiro esperado</span>
              <span>R$ {session.expectedCashAmount || "0,00"}</span>
            </div>
            <div className="mt-1 flex justify-between font-semibold text-text-primary">
              <span>Dinheiro contado</span>
              <span>R$ {session.closingAmount}</span>
            </div>
          </div>

          <div
            className={`rounded-xl border p-3 ${
              temDiferenca ? "border-danger/30 bg-danger/10" : "border-success/30 bg-success/10"
            }`}
          >
            <div className="flex justify-between font-bold text-text-primary">
              <span>Diferença</span>
              <span>R$ {session.differenceAmount || "0,00"}</span>
            </div>
            {session.differenceReason ? (
              <p className="mt-1 text-xs text-text-secondary">Motivo: {session.differenceReason}</p>
            ) : null}
          </div>

          {session.note ? (
            <div>
              <h3 className="mb-1 text-xs font-semibold uppercase text-text-tertiary">Observação</h3>
              <p className="text-text-secondary">{session.note}</p>
            </div>
          ) : null}
        </div>

        <div className="flex justify-end gap-2 border-t border-border-primary px-4 py-3">
          <button type="button" onClick={onClose} className="btn-secondary">
            Fechar
          </button>
          <button
            type="button"
            onClick={handlePrint}
            className="btn-primary inline-flex items-center gap-2"
          >
            <Printer size={16} />
            Imprimir
          </button>
        </div>
      </div>
    </div>
  );
}
