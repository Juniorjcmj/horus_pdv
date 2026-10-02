/**
 * Arquivo: src/components/Admin/CashClosingSummaryModal.tsx
 * Objetivo: exibir e imprimir o comprovante de fechamento de caixa / resumo de turno
 *           em formato térmico de 80mm idêntico ao cupom de venda do PDV, detalhando
 *           todas as sangrias e reforços com seus respectivos motivos, conferência da gaveta
 *           e breakdown por forma de pagamento.
 * Entradas esperadas: recebe a sessão de caixa (CashRegisterSessionDto), dados da empresa e callback de fechamento.
 */
import { Printer, ReceiptText, X } from "lucide-react";
import type { CashRegisterSessionDto } from "@/services/api/cashRegisterService";
import type { CompanyDto } from "@/services/api/companyService";

export type CashClosingCompany = Partial<CompanyDto> | null;

const PAYMENT_LABELS: Record<string, string> = {
  dinheiro: "Dinheiro",
  pix: "PIX",
  debito: "Cartão Débito",
  credito: "Cartão Crédito",
  fiado: "Fiado / A Prazo",
  outros: "Outros",
};

function paymentLabel(paymentType: string): string {
  return PAYMENT_LABELS[paymentType.toLowerCase()] ?? paymentType;
}

function parseMoney(value?: string | number | null): number {
  if (value === undefined || value === null) return 0;
  if (typeof value === "number") return value;
  const cleaned = String(value).replace(/\./g, "").replace(",", ".");
  const num = parseFloat(cleaned);
  return Number.isNaN(num) ? 0 : num;
}

function formatMoney(value: number): string {
  return value.toLocaleString("pt-BR", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatDateTime(value?: string | null) {
  if (!value) return "-";
  const trimmed = value.trim();
  if (/^\d{2}\/\d{2}\/\d{4}/.test(trimmed)) return trimmed;

  const date = new Date(trimmed);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("pt-BR", {
    timeZone: "America/Sao_Paulo",
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

function formatElapsed(minutes?: number | null) {
  if (!minutes || minutes < 1) return "menos de 1 min";
  const hours = Math.floor(minutes / 60);
  const remainingMinutes = minutes % 60;
  if (hours === 0) return `${remainingMinutes} min`;
  return `${hours}h ${String(remainingMinutes).padStart(2, "0")}min`;
}

function escapeHtml(value: string | null | undefined): string {
  if (!value) return "";
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

export function buildSummaryPrintHtml(
  session: CashRegisterSessionDto,
  company?: CashClosingCompany,
  fallbackCompanyName?: string,
) {
  const companyFantasy =
    company?.fantasyName || company?.corporateName || fallbackCompanyName || "HORUS PDV";
  const corporateName = company?.corporateName || companyFantasy;
  const cnpj = company?.cnpj ? `CNPJ: ${company.cnpj}` : "";
  const addressParts = [company?.address, company?.number, company?.neighborhood]
    .filter(Boolean)
    .join(", ");
  const cityUf = [company?.city, company?.uf].filter(Boolean).join(" - ");
  const phones = [company?.phone, company?.sacPhone, company?.mobile].filter(Boolean).join(" / ");

  const totalSangrias = session.movimentos
    .filter((m) => m.tipo.toLowerCase() === "sangria")
    .reduce((acc, m) => acc + parseMoney(m.valor), 0);

  const totalReforcos = session.movimentos
    .filter((m) => m.tipo.toLowerCase() === "reforco")
    .reduce((acc, m) => acc + parseMoney(m.valor), 0);

  const breakdownList = session.paymentBreakdown ?? [];
  const totalVendas = breakdownList.reduce((acc, p) => acc + parseMoney(p.total), 0);
  const vendasDinheiroItem = breakdownList.find((p) => p.paymentType.toLowerCase() === "dinheiro");
  const valorVendasDinheiro = vendasDinheiroItem ? parseMoney(vendasDinheiroItem.total) : 0;

  const diferencaNum = parseMoney(session.differenceAmount);
  const temDiferenca = diferencaNum !== 0;

  const movimentosHtml =
    session.movimentos.length > 0
      ? session.movimentos
          .map((item, idx) => {
            const isSangria = item.tipo.toLowerCase() === "sangria";
            const tipoLabel = isSangria ? "(-) SANGRIA" : "(+) REFORÇO";
            const valorNum = parseMoney(item.valor);
            return `
            <div class="mov-item">
              <div class="line bold">
                <span>${String(idx + 1).padStart(2, "0")}. ${tipoLabel}</span>
                <span class="right">R$ ${formatMoney(valorNum)}</span>
              </div>
              <div class="line mov-meta">
                <span>Data/Hora: ${escapeHtml(formatDateTime(item.createdAt))}</span>
                <span class="right">Op: ${escapeHtml(item.operatorName || "-")}</span>
              </div>
              <div class="mov-motivo">
                <strong>Motivo:</strong> ${escapeHtml(item.motivo || "Não informado")}
              </div>
            </div>`;
          })
          .join("")
      : `<div class="muted center" style="margin: 6px 0; font-size: 11px;">Nenhuma sangria ou reforço registrado.</div>`;

  const breakdownHtml =
    breakdownList.length > 0
      ? breakdownList
          .map(
            (item) => `
            <div class="line">
              <span>${escapeHtml(paymentLabel(item.paymentType))}</span>
              <span class="right">R$ ${formatMoney(parseMoney(item.total))}</span>
            </div>`,
          )
          .join("")
      : `<div class="line"><span>Nenhuma venda registrada</span><span class="right">R$ 0,00</span></div>`;

  return `<!doctype html>
<html lang="pt-BR">
  <head>
    <meta charset="utf-8" />
    <title>Fechamento de Caixa - Turno ${escapeHtml(session.id.slice(0, 8))}</title>
    <style>
      @page { size: 80mm auto; margin: 4mm; }
      * { box-sizing: border-box; }
      body {
        margin: 0;
        color: #020617;
        font: 12px/1.25 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
        -webkit-print-color-adjust: exact;
        print-color-adjust: exact;
      }
      .receipt { width: 72mm; margin: 0 auto; }
      .center { text-align: center; }
      .brand { font-size: 14px; font-weight: 800; text-transform: uppercase; }
      .divider { border-top: 1px dashed #475569; margin: 8px 0; }
      .line { display: flex; justify-content: space-between; gap: 6px; }
      .right { text-align: right; }
      .bold { font-weight: 800; }
      .title { font-size: 12.5px; font-weight: 800; text-align: center; text-transform: uppercase; letter-spacing: 0.5px; margin: 4px 0 2px; }
      .subtitle { font-size: 10px; text-align: center; margin-bottom: 4px; }
      .section-title { font-weight: 800; font-size: 11px; text-transform: uppercase; margin-bottom: 5px; }
      .mov-item { margin-top: 6px; padding-bottom: 5px; border-bottom: 1px dotted #94a3b8; }
      .mov-item:last-child { border-bottom: none; }
      .mov-meta { font-size: 10.5px; color: #1e293b; margin-top: 2px; }
      .mov-motivo { font-size: 11px; margin-top: 2px; word-break: break-word; background: #f8fafc; padding: 2px 4px; border-left: 2px solid #000; }
      .diff-box {
        margin-top: 6px;
        padding: 6px;
        text-align: center;
        border: ${temDiferenca ? "2px solid #000" : "1px solid #64748b"};
        background: ${temDiferenca ? "#fef2f2" : "#f0fdf4"};
      }
      .signatures { margin-top: 26px; text-align: center; font-size: 10.5px; }
      .sig-block { margin-top: 22px; }
      .sig-line { width: 85%; margin: 0 auto 3px auto; border-top: 1px solid #000; }
      .sig-sub { font-size: 9.5px; color: #475569; }
      .footer { margin-top: 16px; text-align: center; font-size: 9.5px; color: #475569; }
      .muted { color: #475569; }
    </style>
  </head>
  <body>
    <main class="receipt">
      <!-- CABEÇALHO DA EMPRESA -->
      <section class="center">
        <div class="brand">${escapeHtml(companyFantasy)}</div>
        ${corporateName && corporateName !== companyFantasy ? `<div>${escapeHtml(corporateName)}</div>` : ""}
        ${cnpj ? `<div>${escapeHtml(cnpj)}</div>` : ""}
        ${addressParts ? `<div>${escapeHtml(addressParts)}</div>` : ""}
        ${cityUf ? `<div>${escapeHtml(cityUf)}</div>` : ""}
        ${phones ? `<div>Tel: ${escapeHtml(phones)}</div>` : ""}
      </section>

      <div class="divider"></div>

      <!-- TÍTULO DO DOCUMENTO -->
      <section class="center">
        <div class="title">FECHAMENTO DE CAIXA</div>
        <div class="subtitle">RESUMO DE TURNO / CONFERÊNCIA GERENCIAL</div>
      </section>

      <div class="divider"></div>

      <!-- DADOS DO TURNO -->
      <section>
        <div class="line"><span>Turno ID:</span><span class="bold">#${escapeHtml(session.id.slice(0, 8).toUpperCase())}</span></div>
        <div class="line"><span>Abertura:</span><span>${escapeHtml(formatDateTime(session.openedAt))}</span></div>
        <div class="line"><span>Fechamento:</span><span>${escapeHtml(formatDateTime(session.closedAt || new Date().toISOString()))}</span></div>
        <div class="line"><span>Duração:</span><span>${escapeHtml(formatElapsed(session.elapsedMinutes))}</span></div>
        <div class="line"><span>Operador Abertura:</span><span>${escapeHtml(session.operatorName || "-")}</span></div>
        <div class="line"><span>Fechado Por:</span><span>${escapeHtml(session.closedByName || session.operatorName || "-")}</span></div>
      </section>

      <div class="divider"></div>

      <!-- VENDAS POR FORMA DE PAGAMENTO -->
      <section>
        <div class="section-title">VENDAS POR FORMA DE PAGAMENTO</div>
        ${breakdownHtml}
        <div class="divider" style="margin: 4px 0;"></div>
        <div class="line bold">
          <span>TOTAL VENDIDO NO TURNO</span>
          <span class="right">R$ ${formatMoney(totalVendas)}</span>
        </div>
      </section>

      <div class="divider"></div>

      <!-- SANGRIAS E REFORÇOS DETALHADOS -->
      <section>
        <div class="section-title">SANGRIA E REFORÇOS DETALHADOS</div>
        ${movimentosHtml}

        <div class="divider" style="margin: 6px 0;"></div>
        <div class="line">
          <span>(+) TOTAL REFORÇOS:</span>
          <span class="bold right">R$ ${formatMoney(totalReforcos)}</span>
        </div>
        <div class="line">
          <span>(-) TOTAL SANGRIAS:</span>
          <span class="bold right">R$ ${formatMoney(totalSangrias)}</span>
        </div>
      </section>

      <div class="divider"></div>

      <!-- CONFERÊNCIA DA GAVETA -->
      <section>
        <div class="section-title">CONFERÊNCIA DA GAVETA (DINHEIRO)</div>
        <div class="line">
          <span>(+) Fundo de Troco (Abertura):</span>
          <span class="right">R$ ${formatMoney(parseMoney(session.openingAmount))}</span>
        </div>
        <div class="line">
          <span>(+) Vendas em Dinheiro:</span>
          <span class="right">R$ ${formatMoney(valorVendasDinheiro)}</span>
        </div>
        <div class="line">
          <span>(+) Reforços no Caixa:</span>
          <span class="right">R$ ${formatMoney(totalReforcos)}</span>
        </div>
        <div class="line">
          <span>(-) Sangrias do Caixa:</span>
          <span class="right">R$ ${formatMoney(totalSangrias)}</span>
        </div>

        <div class="divider" style="margin: 4px 0;"></div>

        <div class="line bold">
          <span>(=) Dinheiro Esperado em Gaveta:</span>
          <span class="right">R$ ${formatMoney(parseMoney(session.expectedCashAmount || "0,00"))}</span>
        </div>
        <div class="line bold">
          <span>(=) Dinheiro Informado / Contado:</span>
          <span class="right">R$ ${formatMoney(parseMoney(session.closingAmount))}</span>
        </div>

        <div class="diff-box">
          <div class="bold" style="font-size: 12px;">
            DIFERENÇA: R$ ${formatMoney(diferencaNum)}
            ${temDiferenca ? (diferencaNum < 0 ? " (FALTA)" : " (SOBRA)") : " (EXATO)"}
          </div>
          ${
            session.differenceReason
              ? `<div style="font-size: 10.5px; margin-top: 3px; font-weight: normal; text-align: left;">
                  <strong>Justificativa da diferença:</strong> ${escapeHtml(session.differenceReason)}
                </div>`
              : ""
          }
        </div>
      </section>

      ${
        session.note
          ? `
          <div class="divider"></div>
          <section>
            <div class="section-title">OBSERVAÇÕES DO FECHAMENTO</div>
            <div style="font-size: 11px; word-break: break-word;">${escapeHtml(session.note)}</div>
          </section>`
          : ""
      }

      <div class="divider"></div>

      <!-- ASSINATURAS PARA AUDITORIA -->
      <section class="signatures">
        <div class="sig-block">
          <div class="sig-line"></div>
          <div class="bold">${escapeHtml(session.closedByName || session.operatorName || "Operador de Caixa")}</div>
          <div class="sig-sub">Operador Responsável pelo Turno</div>
        </div>

        <div class="sig-block">
          <div class="sig-line"></div>
          <div class="bold">Gerente / Conferente</div>
          <div class="sig-sub">Visto e Conferência Administrativa</div>
        </div>
      </section>

      <section class="footer">
        <div>Emissão: ${escapeHtml(new Date().toLocaleString("pt-BR"))}</div>
        <div style="margin-top: 2px;">HORUS PDV - Sistema de Automação Comercial</div>
      </section>
    </main>

    <script>
      window.addEventListener("load", () => {
        window.focus();
        window.print();
      });
    </script>
  </body>
</html>`;
}

export function printCashClosingReceipt(
  session: CashRegisterSessionDto,
  company?: CashClosingCompany,
  fallbackCompanyName?: string,
) {
  const html = buildSummaryPrintHtml(session, company, fallbackCompanyName);
  const blob = new Blob([html], { type: "text/html;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const popup = window.open(url, "_blank", "width=420,height=720");
  if (popup) {
    popup.addEventListener("afterprint", () => {
      popup.close();
      URL.revokeObjectURL(url);
    });
  } else {
    URL.revokeObjectURL(url);
  }
}

export default function CashClosingSummaryModal({
  session,
  company,
  companyName,
  onClose,
}: {
  session: CashRegisterSessionDto;
  company?: CashClosingCompany;
  companyName?: string;
  onClose: () => void;
}) {
  const companyFantasy =
    company?.fantasyName || company?.corporateName || companyName || "HORUS PDV";
  const corporateName = company?.corporateName || companyFantasy;
  const cnpj = company?.cnpj ? `CNPJ: ${company.cnpj}` : "";
  const addressParts = [company?.address, company?.number, company?.neighborhood]
    .filter(Boolean)
    .join(", ");
  const cityUf = [company?.city, company?.uf].filter(Boolean).join(" - ");
  const phones = [company?.phone, company?.sacPhone, company?.mobile].filter(Boolean).join(" / ");

  const totalSangrias = session.movimentos
    .filter((m) => m.tipo.toLowerCase() === "sangria")
    .reduce((acc, m) => acc + parseMoney(m.valor), 0);

  const totalReforcos = session.movimentos
    .filter((m) => m.tipo.toLowerCase() === "reforco")
    .reduce((acc, m) => acc + parseMoney(m.valor), 0);

  const breakdownList = session.paymentBreakdown ?? [];
  const totalVendas = breakdownList.reduce((acc, p) => acc + parseMoney(p.total), 0);
  const vendasDinheiroItem = breakdownList.find((p) => p.paymentType.toLowerCase() === "dinheiro");
  const valorVendasDinheiro = vendasDinheiroItem ? parseMoney(vendasDinheiroItem.total) : 0;

  const diferencaNum = parseMoney(session.differenceAmount);
  const temDiferenca = diferencaNum !== 0;

  const handlePrint = () => {
    printCashClosingReceipt(session, company, companyName);
  };

  return (
    <div className="fixed inset-0 z-layer-dialog flex items-end bg-black/60 px-3 backdrop-blur-sm md:items-center md:justify-center">
      <div className="flex max-h-[92vh] w-full max-w-lg flex-col overflow-hidden rounded-t-2xl border border-border-primary bg-bg-light shadow-2xl md:rounded-2xl">
        {/* CABEÇALHO DO MODAL */}
        <div className="flex items-center justify-between border-b border-border-primary px-4 py-3">
          <div className="flex items-center gap-2">
            <span className="inline-flex h-9 w-9 items-center justify-center rounded-xl bg-accent/10 text-accent">
              <ReceiptText size={18} />
            </span>
            <div>
              <h2 className="text-base font-semibold text-text-primary">
                Fechamento de Caixa (Cupom 80mm)
              </h2>
              <p className="text-xs text-text-secondary">
                Turno #{session.id.slice(0, 8).toUpperCase()} · {formatDateTime(session.closedAt || session.openedAt)}
              </p>
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

        {/* PRÉVIA VISUAL IDÊNTICA AO CUPOM TÉRMICO */}
        <div className="flex-1 overflow-y-auto bg-bg-primary p-4">
          <div className="mx-auto w-full max-w-[380px] rounded border border-border-secondary bg-white p-5 font-mono text-[11.5px] leading-tight text-slate-950 shadow-md">
            {/* CABEÇALHO DA EMPRESA */}
            <div className="text-center">
              <p className="text-sm font-bold uppercase">{companyFantasy}</p>
              {corporateName && corporateName !== companyFantasy ? (
                <p className="text-[10px] text-slate-700">{corporateName}</p>
              ) : null}
              {cnpj ? <p className="text-[10.5px]">{cnpj}</p> : null}
              {addressParts ? <p className="text-[10px]">{addressParts}</p> : null}
              {cityUf ? <p className="text-[10px]">{cityUf}</p> : null}
              {phones ? <p className="text-[10px]">Tel: {phones}</p> : null}
            </div>

            <div className="my-2.5 border-t border-dashed border-slate-500" />

            {/* TÍTULO */}
            <div className="text-center font-bold">
              <div className="text-[12px] tracking-wide">FECHAMENTO DE CAIXA</div>
              <div className="text-[9.5px] text-slate-600">RESUMO DE TURNO / CONFERÊNCIA</div>
            </div>

            <div className="my-2.5 border-t border-dashed border-slate-500" />

            {/* DADOS DO TURNO */}
            <div className="space-y-1 text-[11px]">
              <div className="flex justify-between">
                <span>Turno:</span>
                <span className="font-bold">#{session.id.slice(0, 8).toUpperCase()}</span>
              </div>
              <div className="flex justify-between">
                <span>Abertura:</span>
                <span>{formatDateTime(session.openedAt)}</span>
              </div>
              <div className="flex justify-between">
                <span>Fechamento:</span>
                <span>{formatDateTime(session.closedAt || new Date().toISOString())}</span>
              </div>
              <div className="flex justify-between">
                <span>Duração:</span>
                <span>{formatElapsed(session.elapsedMinutes)}</span>
              </div>
              <div className="flex justify-between">
                <span>Op. Abertura:</span>
                <span>{session.operatorName || "-"}</span>
              </div>
              <div className="flex justify-between">
                <span>Fechado Por:</span>
                <span>{session.closedByName || session.operatorName || "-"}</span>
              </div>
            </div>

            <div className="my-2.5 border-t border-dashed border-slate-500" />

            {/* VENDAS POR FORMA DE PAGAMENTO */}
            <div>
              <div className="font-bold uppercase text-[10.5px]">Vendas por Forma de Pagamento</div>
              <div className="mt-1 space-y-1">
                {breakdownList.length > 0 ? (
                  breakdownList.map((item) => (
                    <div key={item.paymentType} className="flex justify-between">
                      <span>{paymentLabel(item.paymentType)}</span>
                      <span>R$ {formatMoney(parseMoney(item.total))}</span>
                    </div>
                  ))
                ) : (
                  <div className="flex justify-between text-slate-500">
                    <span>Nenhuma venda</span>
                    <span>R$ 0,00</span>
                  </div>
                )}
              </div>
              <div className="mt-1.5 flex justify-between border-t border-dotted border-slate-400 pt-1 font-bold">
                <span>TOTAL VENDIDO</span>
                <span>R$ {formatMoney(totalVendas)}</span>
              </div>
            </div>

            <div className="my-2.5 border-t border-dashed border-slate-500" />

            {/* SANGRIAS E REFORÇOS DETALHADOS */}
            <div>
              <div className="font-bold uppercase text-[10.5px]">Sangria e Reforços Detalhados</div>
              {session.movimentos.length > 0 ? (
                <div className="mt-1 space-y-2">
                  {session.movimentos.map((item, idx) => {
                    const isSangria = item.tipo.toLowerCase() === "sangria";
                    const tipoLabel = isSangria ? "(-) SANGRIA" : "(+) REFORÇO";
                    const valorNum = parseMoney(item.valor);
                    return (
                      <div key={item.id || idx} className="border-b border-dotted border-slate-300 pb-1.5 last:border-b-0">
                        <div className="flex justify-between font-bold">
                          <span className={isSangria ? "text-rose-700" : "text-emerald-700"}>
                            {String(idx + 1).padStart(2, "0")}. {tipoLabel}
                          </span>
                          <span>R$ {formatMoney(valorNum)}</span>
                        </div>
                        <div className="flex justify-between text-[10px] text-slate-600">
                          <span>{formatDateTime(item.createdAt)}</span>
                          <span>Op: {item.operatorName || "-"}</span>
                        </div>
                        <div className="mt-0.5 rounded bg-slate-100 px-1.5 py-0.5 text-[10.5px]">
                          <strong>Motivo:</strong> {item.motivo || "Não informado"}
                        </div>
                      </div>
                    );
                  })}
                </div>
              ) : (
                <div className="my-1.5 text-center text-[10.5px] text-slate-500">
                  Nenhuma sangria ou reforço registrado.
                </div>
              )}

              <div className="mt-2 border-t border-dotted border-slate-400 pt-1 text-[11px]">
                <div className="flex justify-between">
                  <span>(+) Total Reforços:</span>
                  <span className="font-bold">R$ {formatMoney(totalReforcos)}</span>
                </div>
                <div className="flex justify-between">
                  <span>(-) Total Sangrias:</span>
                  <span className="font-bold">R$ {formatMoney(totalSangrias)}</span>
                </div>
              </div>
            </div>

            <div className="my-2.5 border-t border-dashed border-slate-500" />

            {/* CONFERÊNCIA DA GAVETA */}
            <div>
              <div className="font-bold uppercase text-[10.5px]">Conferência da Gaveta (Dinheiro)</div>
              <div className="mt-1 space-y-0.5 text-[11px]">
                <div className="flex justify-between">
                  <span>(+) Fundo Troco:</span>
                  <span>R$ {formatMoney(parseMoney(session.openingAmount))}</span>
                </div>
                <div className="flex justify-between">
                  <span>(+) Vendas Dinheiro:</span>
                  <span>R$ {formatMoney(valorVendasDinheiro)}</span>
                </div>
                <div className="flex justify-between">
                  <span>(+) Reforços:</span>
                  <span>R$ {formatMoney(totalReforcos)}</span>
                </div>
                <div className="flex justify-between">
                  <span>(-) Sangrias:</span>
                  <span>R$ {formatMoney(totalSangrias)}</span>
                </div>
                <div className="mt-1 flex justify-between border-t border-dotted border-slate-400 pt-1 font-bold">
                  <span>(=) Esperado Gaveta:</span>
                  <span>R$ {formatMoney(parseMoney(session.expectedCashAmount || "0,00"))}</span>
                </div>
                <div className="flex justify-between font-bold">
                  <span>(=) Contado Gaveta:</span>
                  <span>R$ {formatMoney(parseMoney(session.closingAmount))}</span>
                </div>
              </div>

              <div
                className={`mt-2 rounded border p-2 text-center text-[11.5px] font-bold ${
                  temDiferenca ? "border-rose-400 bg-rose-50 text-rose-800" : "border-emerald-400 bg-emerald-50 text-emerald-800"
                }`}
              >
                <div>
                  DIFERENÇA: R$ {formatMoney(diferencaNum)}
                  {temDiferenca ? (diferencaNum < 0 ? " (FALTA)" : " (SOBRA)") : " (EXATO)"}
                </div>
                {session.differenceReason ? (
                  <div className="mt-1 text-left text-[10px] font-normal text-slate-700">
                    <strong>Motivo:</strong> {session.differenceReason}
                  </div>
                ) : null}
              </div>
            </div>

            {session.note ? (
              <>
                <div className="my-2.5 border-t border-dashed border-slate-500" />
                <div>
                  <div className="font-bold uppercase text-[10.5px]">Observação</div>
                  <p className="mt-0.5 text-[10.5px] text-slate-800">{session.note}</p>
                </div>
              </>
            ) : null}

            <div className="my-2.5 border-t border-dashed border-slate-500" />

            {/* ASSINATURAS */}
            <div className="mt-4 space-y-4 text-center text-[10.5px]">
              <div>
                <div className="mx-auto w-3/4 border-t border-slate-700" />
                <p className="font-bold mt-1">{session.closedByName || session.operatorName || "Operador de Caixa"}</p>
                <p className="text-[9px] text-slate-500">Operador Responsável</p>
              </div>
              <div>
                <div className="mx-auto w-3/4 border-t border-slate-700" />
                <p className="font-bold mt-1">Gerente / Conferente</p>
                <p className="text-[9px] text-slate-500">Visto e Conferência Administrativa</p>
              </div>
            </div>

            <div className="mt-4 text-center text-[9px] text-slate-500">
              <p>HORUS PDV - Bobina Térmica 80mm</p>
            </div>
          </div>
        </div>

        {/* RODAPÉ COM AÇÕES */}
        <div className="flex justify-end gap-2 border-t border-border-primary bg-bg-light px-4 py-3">
          <button type="button" onClick={onClose} className="btn-secondary">
            Fechar
          </button>
          <button
            type="button"
            onClick={handlePrint}
            className="btn-primary inline-flex items-center gap-2"
          >
            <Printer size={16} />
            Imprimir Fechamento (80mm)
          </button>
        </div>
      </div>
    </div>
  );
}
