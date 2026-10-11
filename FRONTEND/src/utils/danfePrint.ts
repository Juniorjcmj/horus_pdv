/** DANFE NFC-e gerado exclusivamente do XML fiscal original e seu protocolo. */
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { QRCodeSVG } from "qrcode.react";
import type { FiscalDocumentDetailDto } from "@/services/api/fiscalService";
import type { SaleReceipt } from "@/components/Admin/ReceiptPreviewModal";
import { formatFiscalDocument, parseDanfeXml, type DanfeSnapshot } from "./danfeXml";

export function formatChaveAcesso(chave: string | null | undefined): string {
  return chave ? chave.replace(/(\d{4})(?=\d)/g, "$1 ").trim() : "—";
}
export function formatNumeroNf(numero: number | string | null | undefined): string {
  if (numero == null) return "—";
  return String(numero).padStart(9, "0").replace(/(\d{3})(\d{3})(\d{3})/, "$1.$2.$3");
}
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return "—";
  const parsed = new Date(value);
  if (!Number.isFinite(parsed.getTime())) return "—";
  return parsed.toLocaleString("pt-BR", { timeZone: "America/Sao_Paulo", day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit", second: "2-digit" });
}
function escapeHtml(value: string | null | undefined): string {
  return String(value ?? "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#039;");
}
/** Consulta manual por chave; nunca é usada para fabricar o QR Code fiscal. */
export function getSefazConsultaUrl(uf?: string | null): string {
  switch ((uf ?? "RJ").trim().toUpperCase()) {
    case "SP": return "https://www.nfce.fazenda.sp.gov.br/consulta";
    case "MG": return "http://nfce.fazenda.mg.gov.br/portalnfce";
    case "PR": return "http://www.fazenda.pr.gov.br";
    case "RS": return "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx";
    default: return "http://www.fazenda.rj.gov.br/consultaNFCe";
  }
}
export function generateQrCodeSvg(url: string | null | undefined, size = 140): string {
  if (!url) return "";
  return renderToStaticMarkup(React.createElement(QRCodeSVG, { value: url, size, level: "M", boostLevel: false, marginSize: 0, bgColor: "#ffffff", fgColor: "#000000" }));
}

export function buildDanfeSnapshotHtml(d: DanfeSnapshot, formatMoney: (value: number) => string): string {
  const row = (label: string, value: number, bold = false) => `<div class="line${bold ? " bold" : ""}"><span>${label}</span><span>R$ ${formatMoney(value)}</span></div>`;
  const pending = d.contingencia && !d.autorizado;
  const contingencyMark = pending ? `<div class="warning"><strong>EMITIDA EM CONTINGÊNCIA</strong><br />Pendente de autorização</div>` : "";
  const adjustments = d.desconto > 0 || d.frete > 0 || d.seguro > 0 || d.outros > 0;
  const items = d.itens.map((i, index) => `<div class="item"><div class="bold">${String(index + 1).padStart(2, "0")} ${escapeHtml(i.codigo)} — ${escapeHtml(i.descricao)}</div><div class="line"><span>${i.quantidade.toLocaleString("pt-BR", { maximumFractionDigits: 4 })} ${escapeHtml(i.unidade)} × R$ ${formatMoney(i.unitario)}</span><span>R$ ${formatMoney(i.total)}</span></div></div>`).join("");
  return `<!doctype html><html lang="pt-BR"><head><meta charset="utf-8" /><title>DANFE NFC-e ${escapeHtml(d.numero)}</title>
<style>
@page { size: auto; margin: 2mm; }
* { box-sizing: border-box; } body { margin: 0; color: #000; background: #fff; font: 11px/1.3 "Courier New", monospace; print-color-adjust: exact; -webkit-print-color-adjust: exact; }
.receipt { width: 72mm; max-width: 100%; margin: 0 auto; } .center { text-align: center; } .bold { font-weight: bold; } .line { display: flex; justify-content: space-between; gap: 4px; } .line span:last-child { flex-shrink: 0; }
.company { font-size: 13px; font-weight: bold; } .divider { border-top: 1px dashed #000; margin: 6px 0; } .item { margin: 5px 0; break-inside: avoid; } .item div, .message { overflow-wrap: anywhere; }
.warning { border: 1px solid #000; text-align: center; padding: 4px; margin: 6px 0; break-inside: avoid; } .key { overflow-wrap: anywhere; font-size: 10px; } .qr { text-align: center; break-inside: avoid; margin: 6px 0; } .qr-box { display: inline-block; padding: 4mm; background: white; } .qr svg { display: block; width: 30mm; height: 30mm; } .meta { font-size: 10px; overflow-wrap: anywhere; } .caption { font-size: 10px; }
</style></head><body><main class="receipt">
<header class="center"><div class="company">${escapeHtml(d.emitente.nome)}</div>${d.emitente.fantasia && d.emitente.fantasia !== d.emitente.nome ? `<div>${escapeHtml(d.emitente.fantasia)}</div>` : ""}<div>CNPJ: ${escapeHtml(formatFiscalDocument(d.emitente.cnpj, "CNPJ"))}</div><div>${escapeHtml(d.emitente.endereco)}</div><div class="divider"></div><strong>DANFE NFC-e</strong><div>Documento Auxiliar da Nota Fiscal de Consumidor Eletrônica</div></header>
${contingencyMark}
${d.ambiente === "2" ? `<div class="warning bold">EMITIDA EM AMBIENTE DE HOMOLOGAÇÃO<br />SEM VALOR FISCAL</div>` : ""}
<div class="divider"></div>${items}<div class="divider"></div>
<div class="line"><span>Qtd. total de itens</span><span>${d.itens.length}</span></div>
${row("Valor Total", d.total, !adjustments)}${d.frete > 0 ? row("Frete", d.frete) : ""}${d.seguro > 0 ? row("Seguro", d.seguro) : ""}${d.outros > 0 ? row("Outras despesas", d.outros) : ""}${d.desconto > 0 ? row("Desconto", d.desconto) : ""}${adjustments ? row("Valor a Pagar", d.pagar, true) : ""}
<div class="divider"></div><strong>FORMA DE PAGAMENTO / VALOR PAGO</strong>${d.pagamentos.map(p => row(escapeHtml(p.forma), p.valor)).join("")}${d.troco > 0 ? row("Troco", d.troco, true) : ""}
<div class="divider"></div><section class="center meta"><div>Consulte pela Chave de Acesso em:</div><div>${escapeHtml(d.consulta)}</div><div class="key">${escapeHtml(formatChaveAcesso(d.chave))}</div></section>
<div class="divider"></div><section class="center meta">${d.consumidor ? `<div>CONSUMIDOR ${escapeHtml(d.consumidor.tipo)}: ${escapeHtml(formatFiscalDocument(d.consumidor.documento, d.consumidor.tipo))}</div>${d.consumidor.nome ? `<div>${escapeHtml(d.consumidor.nome)}</div>` : ""}${d.consumidor.endereco ? `<div>${escapeHtml(d.consumidor.endereco)}</div>` : ""}` : "CONSUMIDOR NÃO IDENTIFICADO"}</section>
<div class="divider"></div><section class="center meta"><strong>NFC-e nº ${escapeHtml(formatNumeroNf(d.numero))} Série ${escapeHtml(d.serie)}</strong><div>Data de Emissão: ${escapeHtml(formatDateTime(d.emitidoEm))}</div>${d.protocolo ? `<div>Protocolo de autorização: ${escapeHtml(d.protocolo)}</div><div>Data de Autorização: ${escapeHtml(formatDateTime(d.autorizadoEm))}</div>` : ""}</section>
${contingencyMark}<section class="qr"><div class="qr-box">${generateQrCodeSvg(d.qrCode)}</div><div class="caption">Consulta via leitor de QR Code</div></section>
${d.mensagens.length || d.tributos !== null ? `<div class="divider"></div><section class="meta">${d.mensagens.map(m => `<div class="message">${escapeHtml(m)}</div>`).join("")}${d.tributos !== null ? row("Tributos totais (Lei 12.741/2012)", d.tributos) : ""}</section>` : ""}
</main><script>function triggerPrint(){setTimeout(function(){window.focus();window.print();},200);}if(document.readyState==="complete")triggerPrint();else window.addEventListener("load",triggerPrint);</script></body></html>`;
}

export function buildDanfePrintHtml(_receipt: SaleReceipt, fiscal: FiscalDocumentDetailDto, formatMoney: (value: number) => string): string {
  if (fiscal.status !== 3 && fiscal.status !== 8) throw new Error("A impressão ainda está sendo preparada. Aguarde e tente novamente.");
  const snapshot = parseDanfeXml(fiscal.xmlDocumento);
  if (fiscal.status === 3 && !snapshot.autorizado) throw new Error("O XML de autorização ainda não está disponível. Aguarde e tente novamente.");
  if (fiscal.chaveAcesso && snapshot.chave !== fiscal.chaveAcesso) throw new Error("O documento de impressão não corresponde à venda. Consulte a administração.");
  const registeredCnpj = _receipt.company?.cnpj?.replace(/\D/g, "");
  if (registeredCnpj && registeredCnpj !== snapshot.emitente.cnpj) throw new Error("O documento de impressão pertence a outra empresa. Consulte a administração.");
  return buildDanfeSnapshotHtml(snapshot, formatMoney);
}
export function openDanfePrintWindow(receipt: SaleReceipt, fiscal: FiscalDocumentDetailDto, formatMoney: (value: number) => string): void {
  const html = buildDanfePrintHtml(receipt, fiscal, formatMoney);
  const popup = window.open("", "_blank", "width=420,height=720");
  if (!popup) return;
  popup.document.open(); popup.document.write(html); popup.document.close();
}
export type DanfeThermalData = { xmlDocumento: string };
export function buildDanfeFromThermalData(data: DanfeThermalData, formatMoney: (value: number) => string): string {
  return buildDanfeSnapshotHtml(parseDanfeXml(data.xmlDocumento), formatMoney);
}
export function openDanfeThermalWindow(data: DanfeThermalData, formatMoney: (value: number) => string): void {
  const html = buildDanfeFromThermalData(data, formatMoney);
  const popup = window.open("", "_blank", "width=420,height=720");
  if (!popup) return;
  popup.document.open(); popup.document.write(html); popup.document.close();
}
