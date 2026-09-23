/**
 * Arquivo: src/components/Admin/PurchaseOrderPrintModal.tsx
 * Objetivo: exibe o espelho do pedido de compra para conferência, impressão em folha A4 e envio via WhatsApp.
 */
import { MessageCircle, Printer, X } from "lucide-react";
import { useEffect, useState } from "react";
import { companyService, type CompanyDto } from "@/services/api/companyService";
import type { PurchaseOrderDetailDto } from "@/services/api/purchaseOrderService";
import { ocStatusLabel, type OcStatus } from "@/services/api/purchaseOrderService";

interface PurchaseOrderPrintModalProps {
  order: PurchaseOrderDetailDto;
  onClose: () => void;
}

function formatMoney(value: number) {
  return value.toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
}

function formatDate(value?: string | null) {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleDateString("pt-BR");
}

function formatDateTime(value?: string | null) {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("pt-BR", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function escapeHtml(val?: string | null) {
  if (!val) return "";
  return val
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

export default function PurchaseOrderPrintModal({
  order,
  onClose,
}: PurchaseOrderPrintModalProps) {
  const [company, setCompany] = useState<CompanyDto | null>(null);

  useEffect(() => {
    let active = true;
    void companyService.get().then((data) => {
      if (active && data) setCompany(data);
    });
    return () => {
      active = false;
    };
  }, []);

  const subtotalItens = order.itens.reduce(
    (sum, item) => sum + item.quantity * item.unitCost,
    0,
  );
  const valorFrete = order.valorFrete ?? 0;
  const valorDesconto = order.valorDesconto ?? 0;
  const totalGeral = Math.max(0, subtotalItens + valorFrete - valorDesconto);

  const handlePrint = () => {
    const printWindow = window.open("", "_blank", "width=850,height=900");
    if (!printWindow) {
      window.print();
      return;
    }

    const itemsHtml = order.itens
      .map(
        (item, idx) => `
        <tr>
          <td style="text-align:center; padding:6px; border-bottom:1px solid #e2e8f0; font-size:12px;">${idx + 1}</td>
          <td style="padding:6px; border-bottom:1px solid #e2e8f0; font-family:monospace; font-size:11px;">${escapeHtml(item.productCode)}</td>
          <td style="padding:6px; border-bottom:1px solid #e2e8f0; font-size:12px;">${escapeHtml(item.productName)}</td>
          <td style="text-align:right; padding:6px; border-bottom:1px solid #e2e8f0; font-size:12px;">${item.quantity}</td>
          <td style="text-align:right; padding:6px; border-bottom:1px solid #e2e8f0; font-size:12px;">${formatMoney(item.unitCost)}</td>
          <td style="text-align:right; padding:6px; border-bottom:1px solid #e2e8f0; font-weight:bold; font-size:12px;">${formatMoney(item.quantity * item.unitCost)}</td>
        </tr>`,
      )
      .join("");

    const companyName = company?.fantasyName || company?.corporateName || "Hórus PDV";
    const companyCnpj = company?.cnpj ? `CNPJ: ${company.cnpj}` : "";
    const companyPhone = company?.phone || company?.mobile || "";
    const companyAddress = [company?.address, company?.number, company?.city, company?.uf]
      .filter(Boolean)
      .join(", ");

    const html = `
      <!DOCTYPE html>
      <html lang="pt-BR">
      <head>
        <meta charset="utf-8" />
        <title>Ordem de Compra #${escapeHtml(order.orderNumber)}</title>
        <style>
          * { box-sizing: border-box; }
          body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Arial, sans-serif; color: #1e293b; margin: 0; padding: 24px; font-size: 13px; }
          .header { border-bottom: 2px solid #0f172a; padding-bottom: 12px; margin-bottom: 16px; display: flex; justify-content: space-between; align-items: flex-start; }
          .title { font-size: 20px; font-weight: bold; color: #0f172a; }
          .company-name { font-size: 16px; font-weight: 700; color: #0f172a; }
          .section { margin-bottom: 16px; border: 1px solid #cbd5e1; border-radius: 6px; padding: 12px; }
          .section-title { font-size: 11px; font-weight: 700; text-transform: uppercase; color: #64748b; margin-bottom: 8px; letter-spacing: 0.5px; }
          .grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; }
          table { width: 100%; border-collapse: collapse; margin-top: 8px; }
          th { background: #f1f5f9; padding: 8px 6px; text-align: left; font-size: 11px; text-transform: uppercase; color: #475569; border-bottom: 2px solid #cbd5e1; }
          .totals { margin-top: 16px; display: flex; justify-content: flex-end; }
          .totals-table { width: 280px; }
          .totals-table td { padding: 4px 6px; }
          .totals-table .total-row { font-size: 15px; font-weight: bold; border-top: 2px solid #0f172a; color: #0f172a; }
          .signature-box { margin-top: 48px; display: flex; justify-content: space-between; gap: 32px; }
          .signature-line { flex: 1; border-top: 1px solid #64748b; text-align: center; padding-top: 6px; font-size: 12px; color: #475569; }
          @media print {
            body { padding: 0; }
            @page { margin: 1.5cm; }
          }
        </style>
      </head>
      <body>
        <div class="header">
          <div>
            <div class="company-name">${escapeHtml(companyName)}</div>
            <div style="font-size: 12px; color: #64748b;">${escapeHtml(companyCnpj)} ${companyPhone ? `• Tel: ${escapeHtml(companyPhone)}` : ""}</div>
            ${companyAddress ? `<div style="font-size: 11px; color: #64748b;">${escapeHtml(companyAddress)}</div>` : ""}
          </div>
          <div style="text-align: right;">
            <div class="title">ORDEM DE COMPRA</div>
            <div style="font-size: 15px; font-weight: bold; color: #0284c7;">#${escapeHtml(order.orderNumber)}</div>
            <div style="font-size: 11px; color: #64748b;">Emissão: ${escapeHtml(formatDateTime(order.createdAt))}</div>
            <div style="font-size: 11px; font-weight: bold; text-transform: uppercase;">Status: ${escapeHtml(ocStatusLabel(order.status as OcStatus))}</div>
          </div>
        </div>

        <div class="grid">
          <div class="section">
            <div class="section-title">Dados do Fornecedor</div>
            <div style="font-weight: bold; font-size: 14px;">${escapeHtml(order.supplierName)}</div>
            ${order.supplierCnpj ? `<div style="font-size: 12px; color: #475569;">CNPJ: ${escapeHtml(order.supplierCnpj)}</div>` : ""}
          </div>
          <div class="section">
            <div class="section-title">Condições do Pedido</div>
            <div><strong>Previsão de Entrega:</strong> ${escapeHtml(formatDate(order.previsaoEntrega))}</div>
            <div><strong>Condição de Pagamento:</strong> ${escapeHtml(order.condicaoPagamento || "Não informada")}</div>
            ${order.formaPagamento ? `<div><strong>Forma de Pagamento:</strong> ${escapeHtml(order.formaPagamento)}</div>` : ""}
            ${order.createdByName ? `<div><strong>Comprador:</strong> ${escapeHtml(order.createdByName)}</div>` : ""}
          </div>
        </div>

        <div class="section" style="padding: 0; overflow: hidden;">
          <table>
            <thead>
              <tr>
                <th style="text-align:center; width:40px;">#</th>
                <th style="width:120px;">Código</th>
                <th>Descrição do Produto</th>
                <th style="text-align:right; width:80px;">Qtd</th>
                <th style="text-align:right; width:110px;">Custo Unit.</th>
                <th style="text-align:right; width:120px;">Subtotal</th>
              </tr>
            </thead>
            <tbody>
              ${itemsHtml}
            </tbody>
          </table>
        </div>

        <div class="totals">
          <table class="totals-table">
            <tr>
              <td>Subtotal Itens:</td>
              <td style="text-align:right;">${formatMoney(subtotalItens)}</td>
            </tr>
            ${
              valorFrete > 0
                ? `<tr>
                    <td>Frete (+):</td>
                    <td style="text-align:right;">${formatMoney(valorFrete)}</td>
                  </tr>`
                : ""
            }
            ${
              valorDesconto > 0
                ? `<tr>
                    <td>Desconto (-):</td>
                    <td style="text-align:right; color:#dc2626;">-${formatMoney(valorDesconto)}</td>
                  </tr>`
                : ""
            }
            <tr class="total-row">
              <td>Total da Ordem:</td>
              <td style="text-align:right;">${formatMoney(totalGeral)}</td>
            </tr>
          </table>
        </div>

        ${
          order.note
            ? `<div class="section" style="margin-top: 16px;">
                <div class="section-title">Observações do Pedido</div>
                <div style="font-size: 12px; color: #334155;">${escapeHtml(order.note)}</div>
              </div>`
            : ""
        }

        <div class="signature-box">
          <div class="signature-line">
            <strong>${escapeHtml(order.createdByName || "Comprador Autorizado")}</strong><br />
            Emissão do Pedido
          </div>
          <div class="signature-line">
            <strong>Representante / Fornecedor</strong><br />
            Confirmação de Recebimento do Pedido
          </div>
        </div>

        <script>
          window.addEventListener('load', function() {
            setTimeout(function() {
              window.print();
            }, 300);
          });
        </script>
      </body>
      </html>
    `;

    printWindow.document.open();
    printWindow.document.write(html);
    printWindow.document.close();
  };

  const handleShareWhatsApp = () => {
    const companyName = company?.fantasyName || company?.corporateName || "Hórus PDV";
    const itensSummary = order.itens
      .map(
        (i) => `• ${i.productName} - ${i.quantity} un x ${formatMoney(i.unitCost)} = ${formatMoney(i.quantity * i.unitCost)}`,
      )
      .join("\n");

    const message = `*ORDEM DE COMPRA #${order.orderNumber}*\n` +
      `*Comprador:* ${companyName}\n` +
      `*Fornecedor:* ${order.supplierName}\n` +
      `*Previsão de Entrega:* ${formatDate(order.previsaoEntrega)}\n` +
      (order.condicaoPagamento ? `*Condição de Pagto:* ${order.condicaoPagamento}\n` : "") +
      `\n*ITENS DO PEDIDO:*\n${itensSummary}\n\n` +
      (valorFrete > 0 ? `*Frete:* ${formatMoney(valorFrete)}\n` : "") +
      (valorDesconto > 0 ? `*Desconto:* -${formatMoney(valorDesconto)}\n` : "") +
      `*TOTAL:* ${formatMoney(totalGeral)}\n` +
      (order.note ? `\n*Obs:* ${order.note}` : "");

    const url = `https://api.whatsapp.com/send?text=${encodeURIComponent(message)}`;
    window.open(url, "_blank");
  };

  return (
    <div className="dept-modal-overlay" onClick={onClose}>
      <div
        className="relative flex max-h-[92vh] w-full max-w-3xl flex-col rounded-2xl border border-border-primary bg-bg-primary shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b border-border-primary px-6 py-4">
          <div>
            <h2 className="text-lg font-bold text-text-primary">
              Espelho da Ordem de Compra #{order.orderNumber}
            </h2>
            <p className="text-xs text-text-secondary">
              {order.supplierName} • {formatDateTime(order.createdAt)}
            </p>
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={handleShareWhatsApp}
              className="inline-flex items-center gap-1.5 rounded-lg border border-emerald-500/30 bg-emerald-500/10 px-3 py-1.5 text-xs font-semibold text-emerald-600 transition hover:bg-emerald-500/20"
              title="Compartilhar resumo via WhatsApp"
            >
              <MessageCircle size={15} /> WhatsApp
            </button>
            <button
              type="button"
              onClick={handlePrint}
              className="btn-primary inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold"
              title="Imprimir ou Salvar PDF"
            >
              <Printer size={15} /> Imprimir / PDF
            </button>
            <button
              type="button"
              onClick={onClose}
              className="ml-2 text-text-secondary hover:text-text-primary"
            >
              <X size={18} />
            </button>
          </div>
        </div>

        {/* Document Preview Content */}
        <div className="flex-1 overflow-y-auto p-6 space-y-4 text-xs">
          {/* Card: Dados Fornecedor e Condições */}
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div className="rounded-xl border border-border-primary bg-bg-light p-3 space-y-1">
              <span className="block text-[10px] font-bold uppercase tracking-wider text-text-tertiary">
                Fornecedor
              </span>
              <div className="text-sm font-bold text-text-primary">{order.supplierName}</div>
              {order.supplierCnpj && (
                <div className="text-text-secondary">CNPJ: {order.supplierCnpj}</div>
              )}
            </div>
            <div className="rounded-xl border border-border-primary bg-bg-light p-3 space-y-1">
              <span className="block text-[10px] font-bold uppercase tracking-wider text-text-tertiary">
                Condições Comerciais
              </span>
              <div className="flex justify-between">
                <span className="text-text-secondary">Previsão Entrega:</span>
                <span className="font-semibold text-text-primary">
                  {formatDate(order.previsaoEntrega)}
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-text-secondary">Condição de Pagto:</span>
                <span className="font-semibold text-text-primary">
                  {order.condicaoPagamento || "—"}
                </span>
              </div>
              {order.formaPagamento && (
                <div className="flex justify-between">
                  <span className="text-text-secondary">Forma de Pagto:</span>
                  <span className="font-semibold text-text-primary">{order.formaPagamento}</span>
                </div>
              )}
            </div>
          </div>

          {/* Table: Itens */}
          <div className="rounded-xl border border-border-primary overflow-hidden">
            <table className="w-full text-left text-xs">
              <thead className="border-b border-border-primary bg-bg-light font-semibold uppercase text-text-secondary">
                <tr>
                  <th className="px-3 py-2">Item</th>
                  <th className="px-3 py-2 text-right">Qtd</th>
                  <th className="px-3 py-2 text-right">Custo Unit.</th>
                  <th className="px-3 py-2 text-right">Subtotal</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border-primary">
                {order.itens.map((item) => (
                  <tr key={item.productCode} className="hover:bg-bg-light/50">
                    <td className="px-3 py-2">
                      <div className="font-semibold text-text-primary">{item.productName}</div>
                      <div className="font-mono text-[10px] text-text-tertiary">{item.productCode}</div>
                    </td>
                    <td className="px-3 py-2 text-right font-medium">{item.quantity}</td>
                    <td className="px-3 py-2 text-right">{formatMoney(item.unitCost)}</td>
                    <td className="px-3 py-2 text-right font-bold text-text-primary">
                      {formatMoney(item.quantity * item.unitCost)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Totais */}
          <div className="flex justify-end">
            <div className="w-64 space-y-1 rounded-xl border border-border-primary bg-bg-light p-3">
              <div className="flex justify-between text-text-secondary">
                <span>Subtotal Itens:</span>
                <span>{formatMoney(subtotalItens)}</span>
              </div>
              {valorFrete > 0 && (
                <div className="flex justify-between text-text-secondary">
                  <span>Frete (+):</span>
                  <span>{formatMoney(valorFrete)}</span>
                </div>
              )}
              {valorDesconto > 0 && (
                <div className="flex justify-between text-danger">
                  <span>Desconto (-):</span>
                  <span>-{formatMoney(valorDesconto)}</span>
                </div>
              )}
              <div className="flex justify-between border-t border-border-primary pt-1 text-sm font-bold text-text-primary">
                <span>Total:</span>
                <span className="text-secondary">{formatMoney(totalGeral)}</span>
              </div>
            </div>
          </div>

          {/* Observação */}
          {order.note && (
            <div className="rounded-xl border border-border-primary bg-bg-light p-3">
              <span className="block text-[10px] font-bold uppercase tracking-wider text-text-tertiary">
                Observações
              </span>
              <p className="mt-1 text-text-secondary whitespace-pre-wrap">{order.note}</p>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex justify-end border-t border-border-primary px-6 py-3">
          <button
            type="button"
            onClick={onClose}
            className="btn-cancel px-4 py-2 text-xs"
          >
            Fechar
          </button>
        </div>
      </div>
    </div>
  );
}
