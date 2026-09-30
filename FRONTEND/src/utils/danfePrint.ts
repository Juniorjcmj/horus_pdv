/**
 * Arquivo: src/utils/danfePrint.ts
 * Objetivo: gerar o HTML e acionar a impressão térmica em bobina de 80mm do DANFE NFC-e
 *           (Documento Auxiliar da Nota Fiscal de Consumidor Eletrônica) em estrita
 *           conformidade com o Manual de Padrões Técnicos da SEFAZ e Lei 12.741/2012.
 */
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { QRCodeSVG } from "qrcode.react";
import type { FiscalDocumentDetailDto } from "@/services/api/fiscalService";
import type { SaleReceipt } from "@/components/Admin/ReceiptPreviewModal";

export function formatChaveAcesso(chave: string | null | undefined): string {
  if (!chave) return "—";
  return chave.replace(/(\d{4})(?=\d)/g, "$1 ").trim();
}

export function formatNumeroNf(numero: number | string | null | undefined): string {
  if (numero == null) return "000.000.000";
  const num = typeof numero === "number" ? numero : parseInt(String(numero), 10) || 0;
  return String(num).padStart(9, "0").replace(/(\d{3})(\d{3})(\d{3})/, "$1.$2.$3");
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return "—";
  const trimmed = String(value).trim();
  if (/^\d{2}\/\d{2}\/\d{4}/.test(trimmed)) return trimmed;

  const date = new Date(trimmed);
  if (Number.isNaN(date.getTime())) return String(value);
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

function escapeHtml(value: string | null | undefined): string {
  if (!value) return "";
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

export function getSefazConsultaUrl(uf?: string | null): string {
  const normalized = (uf ?? "RJ").trim().toUpperCase();
  switch (normalized) {
    case "RJ":
      return "http://www.fazenda.rj.gov.br/consultaDFe";
    case "SP":
      return "https://www.nfce.fazenda.sp.gov.br/consulta";
    case "MG":
      return "http://nfce.fazenda.mg.gov.br/portalnfce";
    case "PR":
      return "http://www.fazenda.pr.gov.br";
    case "RS":
      return "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx";
    default:
      return "http://www.fazenda.rj.gov.br/consultaDFe";
  }
}

export function generateQrCodeSvg(url: string | null | undefined, size = 140): string {
  if (!url) return "";
  try {
    const svg = renderToStaticMarkup(
      React.createElement(QRCodeSVG, {
        value: url,
        size,
        level: "M",
        includeMargin: true,
        bgColor: "#ffffff",
        fgColor: "#000000",
      })
    );
    // Garante que o fundo branco tenha inline style para impressão (browsers ignoram fill de CSS em SVG ao imprimir)
    return svg.replace(
      /(<rect[^>]*fill="#ffffff"[^>]*)(\/?>)/g,
      '$1 style="fill:#ffffff"$2'
    );
  } catch {
    return "";
  }
}

export function buildDanfePrintHtml(
  receipt: SaleReceipt,
  fiscal: FiscalDocumentDetailDto,
  formatMoney: (value: number) => string
): string {
  const company = receipt.company;
  const companyCorporate = company?.corporateName || company?.fantasyName || "QUACK SISTEMAS";
  const companyFantasy = company?.fantasyName;
  const companyAddress = [
    company?.address,
    company?.number,
    company?.neighborhood,
  ]
    .filter(Boolean)
    .join(", ");
  const companyCity = [company?.city, company?.uf].filter(Boolean).join(" - ");

  const itemsRows = receipt.items
    .map(
      (item, index) => `
        <div class="item">
          <div class="line grid-item">
            <span>${String(index + 1).padStart(2, "0")}</span>
            <span class="item-name">${escapeHtml(item.name)}</span>
            <span class="right">${item.quantity}</span>
            <span class="right">${formatMoney(item.total)}</span>
          </div>
          <div class="item-meta">${escapeHtml(item.code)} - UN ${formatMoney(item.unitPrice)}</div>
        </div>
      `
    )
    .join("");

  const effectiveQrCodeUrl =
    fiscal.qrCodeUrl?.trim() ||
    (fiscal.chaveAcesso ? `${getSefazConsultaUrl(company?.uf)}?p=${fiscal.chaveAcesso}` : "");
  const qrSvg = generateQrCodeSvg(effectiveQrCodeUrl, 140);
  const sefazUrl = getSefazConsultaUrl(company?.uf);
  const formattedChave = formatChaveAcesso(fiscal.chaveAcesso);
  const formattedNumero = formatNumeroNf(fiscal.numeroNf);
  const dataEmissao = formatDateTime(receipt.issuedAt);
  const dataAutorizacao = formatDateTime(fiscal.dhAutorizacao ?? receipt.issuedAt);

  // Estimativa aproximada de tributos (Lei 12.741/2012) ~ 31,45% no varejo quando não discriminado item a item
  const totalTributosEstimados = receipt.subtotal * 0.3145;

  const isHomologacao = company?.ambienteFiscal === 2;
  const isContingencia = fiscal.status === 8 || fiscal.tpEmis === 9;

  return `<!doctype html>
<html lang="pt-BR">
  <head>
    <meta charset="utf-8" />
    <title>DANFE NFC-e ${escapeHtml(formattedNumero)}</title>
    <style>
      @page {
        size: 80mm auto;
        margin: 2mm 3mm 4mm 3mm;
      }
      * {
        box-sizing: border-box;
      }
      body {
        margin: 0;
        padding: 0;
        color: #000;
        background: #fff;
        font: 11px/1.25 ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace;
        -webkit-print-color-adjust: exact;
        print-color-adjust: exact;
      }
      .receipt {
        width: 72mm;
        margin: 0 auto;
      }
      .center {
        text-align: center;
      }
      .right {
        text-align: right;
      }
      .bold {
        font-weight: 800;
      }
      .uppercase {
        text-transform: uppercase;
      }
      .company-title {
        font-size: 13px;
        font-weight: 800;
        line-height: 1.2;
      }
      .divider {
        border-top: 1px dashed #000;
        margin: 6px 0;
      }
      .line {
        display: flex;
        justify-content: space-between;
        gap: 4px;
      }
      .grid-item {
        display: grid;
        grid-template-columns: 20px 1fr 32px 52px;
        gap: 4px;
      }
      .item {
        margin-top: 5px;
      }
      .item-name {
        word-break: break-word;
      }
      .item-meta {
        padding-left: 24px;
        font-size: 10px;
        color: #111;
      }
      .danfe-banner {
        text-align: center;
        margin: 4px 0;
      }
      .danfe-title {
        font-size: 12px;
        font-weight: 800;
      }
      .danfe-subtitle {
        font-size: 9.5px;
        line-height: 1.2;
      }
      .warning-box {
        border: 1px solid #000;
        padding: 3px;
        margin: 4px 0;
        font-weight: 800;
        text-align: center;
        font-size: 10.5px;
      }
      .chave-box {
        font-size: 9.5px;
        font-weight: 800;
        letter-spacing: 0.4px;
        word-break: break-all;
        text-align: center;
        margin: 3px 0;
      }
      .qrcode-wrapper {
        text-align: center;
        margin: 8px auto 4px auto;
        width: 100%;
        page-break-inside: avoid;
      }
      .qrcode-box {
        display: inline-block;
        padding: 6px;
        background: #ffffff !important;
        -webkit-print-color-adjust: exact;
        print-color-adjust: exact;
        color-adjust: exact;
      }
      .qrcode-wrapper svg {
        display: inline-block;
        margin: 0 auto;
        width: 140px !important;
        height: 140px !important;
        shape-rendering: crispEdges;
        -webkit-print-color-adjust: exact;
        print-color-adjust: exact;
        color-adjust: exact;
      }
      .qrcode-wrapper svg rect { fill: #ffffff !important; }
      .qrcode-wrapper svg path { fill: #000000 !important; }
      .qrcode-caption {
        font-size: 9px;
        margin-top: 4px;
        text-align: center;
        color: #000;
      }
      .fiscal-meta {
        font-size: 10px;
        line-height: 1.25;
      }
    </style>
  </head>
  <body>
    <main class="receipt">
      <!-- CABEÇALHO EMITENTE -->
      <header class="center">
        <div class="company-title uppercase">${escapeHtml(companyCorporate)}</div>
        ${companyFantasy && companyFantasy !== companyCorporate ? `<div class="uppercase">${escapeHtml(companyFantasy)}</div>` : ""}
        <div>CNPJ: ${escapeHtml(company?.cnpj || "-")}</div>
        ${company?.stateRegistration ? `<div>Inscrição Estadual: ${escapeHtml(company.stateRegistration)}</div>` : ""}
        ${companyAddress ? `<div>${escapeHtml(companyAddress)}</div>` : ""}
        ${companyCity ? `<div>${escapeHtml(companyCity)}</div>` : ""}
        ${company?.phone ? `<div>Tel: ${escapeHtml(company.phone)}</div>` : ""}
      </header>

      <div class="divider"></div>

      <!-- TÍTULO OFICIAL DANFE NFC-e -->
      <section class="danfe-banner">
        <div class="danfe-title">DANFE NFC-e</div>
        <div class="danfe-subtitle">Documento Auxiliar da Nota Fiscal de Consumidor Eletrônica</div>
        <div class="danfe-subtitle bold">Não permite aproveitamento de crédito de ICMS</div>
      </section>

      ${
        isHomologacao
          ? `<div class="warning-box">EMITIDA EM AMBIENTE DE HOMOLOGAÇÃO<br />SEM VALOR FISCAL</div>`
          : ""
      }
      ${
        isContingencia
          ? `<div class="warning-box" style="border: 2px solid #000; padding: 5px; margin: 6px 0; text-align: center;">
              <div class="bold" style="font-size: 13px; letter-spacing: 0.5px;">EMITIDA EM CONTINGÊNCIA</div>
              <div class="bold" style="font-size: 10.5px;">Pendente de autorização</div>
              ${fiscal.dhContingencia ? `<div style="font-size: 9px; margin-top: 2px;">Data/Hora Contingência: ${escapeHtml(formatDateTime(fiscal.dhContingencia))}</div>` : ""}
              ${fiscal.justContingencia ? `<div style="font-size: 8.5px; margin-top: 2px; word-break: break-word;">Motivo: ${escapeHtml(fiscal.justContingencia)}</div>` : ""}
            </div>`
          : ""
      }

      <div class="divider"></div>

      <!-- RELAÇÃO DE ITENS -->
      <section>
        <div class="grid-item bold">
          <span>#</span>
          <span>ITEM</span>
          <span class="right">QTD</span>
          <span class="right">TOTAL</span>
        </div>
        ${itemsRows}
      </section>

      <div class="divider"></div>

      <!-- TOTAIS E PAGAMENTO -->
      <section>
        <div class="line">
          <span>Qtd. total de itens</span>
          <span>${receipt.items.length}</span>
        </div>
        <div class="line bold" style="font-size: 12px;">
          <span>VALOR TOTAL R$</span>
          <span>${formatMoney(receipt.subtotal)}</span>
        </div>

        <div style="margin-top: 4px;">
          <div class="bold">FORMA DE PAGAMENTO</div>
          ${
            receipt.payments && receipt.payments.length > 0
              ? receipt.payments
                  .map(
                    (p) => `<div class="line">
                      <span>${escapeHtml(p.paymentLabel)}</span>
                      <span>R$ ${formatMoney(p.amount)}</span>
                    </div>`
                  )
                  .join("") +
                (receipt.change > 0
                  ? `<div class="line bold"><span>Troco</span><span>R$ ${formatMoney(receipt.change)}</span></div>`
                  : "")
              : `<div class="line">
                  <span>${escapeHtml(receipt.paymentLabel || "Dinheiro")}</span>
                  <span>R$ ${formatMoney(receipt.subtotal)}</span>
                </div>
                ${
                  receipt.paymentType === "dinheiro" && receipt.change > 0
                    ? `<div class="line bold"><span>Troco</span><span>R$ ${formatMoney(receipt.change)}</span></div>`
                    : ""
                }`
          }
        </div>
      </section>

      <div class="divider"></div>

      <!-- TRIBUTOS LEI 12.741/2012 -->
      <section class="center fiscal-meta">
        <div>Tributos Totais Incidentes (Lei Fed. 12.741/2012):</div>
        <div class="bold">R$ ${formatMoney(totalTributosEstimados)}</div>
      </section>

      <div class="divider"></div>

      <!-- DADOS DE EMISSÃO DA NFC-e -->
      <section class="fiscal-meta">
        <div class="center bold">
          NFC-e nº ${escapeHtml(formattedNumero)} &nbsp; Série ${fiscal.serie}
        </div>
        <div class="center">
          Data de Emissão: ${escapeHtml(dataEmissao)}
        </div>
        ${
          fiscal.protocolo
            ? `<div class="center" style="margin-top: 2px;">
                Protocolo de Autorização: <strong>${escapeHtml(fiscal.protocolo)}</strong><br />
                Data de Autorização: ${escapeHtml(dataAutorizacao)}
              </div>`
            : isContingencia
              ? `<div class="center bold" style="margin-top: 2px;">Forma de Emissão: CONTINGÊNCIA OFFLINE</div>
                 <div class="center" style="font-size: 8.5px; margin-top: 1px;">Transmissão obrigatória em até 24 horas</div>`
              : `<div class="center bold" style="margin-top: 2px;">Aguardando autorização SEFAZ</div>`
        }
      </section>

      <div class="divider"></div>

      <!-- CONSULTA E CHAVE DE ACESSO -->
      <section class="fiscal-meta center">
        <div>Consulte pela Chave de Acesso em:</div>
        <div class="bold" style="font-size: 9.5px; word-break: break-all;">${escapeHtml(sefazUrl)}</div>
        <div style="margin-top: 4px; font-size: 9px;">CHAVE DE ACESSO:</div>
        <div class="chave-box">${escapeHtml(formattedChave)}</div>
      </section>

      <div class="divider"></div>

      <!-- CONSUMIDOR -->
      <section class="fiscal-meta center">
        <div>CONSUMIDOR:</div>
        <div class="bold">
          ${
            receipt.customerCpf && receipt.customerCpf !== "-" && receipt.customerCpf.trim().length > 0
              ? `CPF: ${escapeHtml(receipt.customerCpf)}`
              : "CONSUMIDOR NÃO IDENTIFICADO"
          }
        </div>
      </section>

      <!-- QR CODE SEFAZ -->
      ${
        qrSvg
          ? `
            <div class="divider"></div>
            <section class="qrcode-wrapper">
              <div class="qrcode-box">
                ${qrSvg}
              </div>
              <div class="qrcode-caption">Consulta via leitor de QR Code</div>
            </section>
          `
          : ""
      }

      <div class="divider"></div>
      <footer class="center fiscal-meta" style="font-size: 9px; color: #444;">
        <div>Venda: ${escapeHtml(receipt.saleNumber)} · Operador: ${escapeHtml(receipt.operatorName || "-")}</div>
        <div>Quack Sistemas - Soluções para seu Negócio</div>
      </footer>
    </main>

    <script>
      function triggerPrint() {
        setTimeout(function () {
          window.focus();
          window.print();
        }, 200);
      }
      if (document.readyState === "complete") {
        triggerPrint();
      } else {
        window.addEventListener("load", triggerPrint);
      }
    </script>
  </body>
</html>`;
}

export function openDanfePrintWindow(
  receipt: SaleReceipt,
  fiscal: FiscalDocumentDetailDto,
  formatMoney: (value: number) => string
): void {
  const popup = window.open("", "_blank", "width=420,height=720");
  if (!popup) return;
  popup.document.open();
  popup.document.write(buildDanfePrintHtml(receipt, fiscal, formatMoney));
  popup.document.close();
}

export type DanfeThermalData = {
  razaoSocial: string;
  nomeFantasia?: string;
  cnpj: string;
  inscricaoEstadual?: string;
  endereco?: string;
  chaveAcesso: string;
  serie: number;
  numeroNf: number;
  dhEmissao: string;
  emissaoContingencia: boolean;
  mensagemContingencia?: string;
  justificativa?: string;
  subtotal: number;
  desconto: number;
  totalLiquido: number;
  valorTroco: number;
  customerCpf?: string | null;
  customerName?: string | null;
  itens: Array<{
    numero: number;
    codigo: string;
    descricao: string;
    quantidade: number;
    unidade?: string;
    valorUnitario: number;
    valorTotal: number;
  }>;
  pagamentos: Array<{
    forma: string;
    valor: number;
  }>;
  qrCodeUrl: string;
  urlConsultaChave?: string;
};

export function buildDanfeFromThermalData(
  data: DanfeThermalData,
  formatMoney: (value: number) => string
): string {
  const qrSvg = generateQrCodeSvg(data.qrCodeUrl, 140);
  const formattedChave = formatChaveAcesso(data.chaveAcesso);
  const formattedNumero = formatNumeroNf(data.numeroNf);
  const sefazUrl = data.urlConsultaChave || "http://www.fazenda.rj.gov.br/consultaNFCe";
  const totalTributosEstimados = data.totalLiquido * 0.3145;

  const itemsRows = data.itens
    .map(
      (item, index) => `
        <div class="item">
          <div class="line grid-item">
            <span>${String(index + 1).padStart(2, "0")}</span>
            <span class="item-name">${escapeHtml(item.descricao)}</span>
            <span class="right">${item.quantidade}</span>
            <span class="right">${formatMoney(item.valorTotal)}</span>
          </div>
          <div class="item-meta">${escapeHtml(item.codigo)} - ${escapeHtml(item.unidade || "UN")} ${formatMoney(item.valorUnitario)}</div>
        </div>
      `
    )
    .join("");

  return `<!doctype html>
<html lang="pt-BR">
  <head>
    <meta charset="utf-8" />
    <title>DANFE NFC-e ${escapeHtml(formattedNumero)}</title>
    <style>
      @page { size: 80mm auto; margin: 2mm 3mm 4mm 3mm; }
      * { box-sizing: border-box; }
      body {
        margin: 0; padding: 0; color: #000; background: #fff;
        font: 11px/1.25 ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace;
        -webkit-print-color-adjust: exact; print-color-adjust: exact;
      }
      .receipt { width: 72mm; margin: 0 auto; }
      .center { text-align: center; }
      .right { text-align: right; }
      .bold { font-weight: 800; }
      .uppercase { text-transform: uppercase; }
      .company-title { font-size: 13px; font-weight: 800; line-height: 1.2; }
      .divider { border-top: 1px dashed #000; margin: 6px 0; }
      .line { display: flex; justify-content: space-between; gap: 4px; }
      .grid-item { display: grid; grid-template-columns: 20px 1fr 32px 52px; gap: 4px; }
      .item { margin-top: 5px; }
      .item-name { word-break: break-word; }
      .item-meta { padding-left: 24px; font-size: 10px; color: #111; }
      .danfe-banner { text-align: center; margin: 4px 0; }
      .danfe-title { font-size: 12px; font-weight: 800; }
      .danfe-subtitle { font-size: 9.5px; line-height: 1.2; }
      .warning-box { border: 1px solid #000; padding: 3px; margin: 4px 0; font-weight: 800; text-align: center; font-size: 10.5px; }
      .chave-box { font-size: 9.5px; font-weight: 800; letter-spacing: 0.4px; word-break: break-all; text-align: center; margin: 3px 0; }
      .qrcode-wrapper { text-align: center; margin: 8px auto 4px auto; width: 100%; page-break-inside: avoid; }
      .qrcode-box { display: inline-block; padding: 6px; background: #ffffff !important; }
      .qrcode-wrapper svg { display: inline-block; margin: 0 auto; width: 140px !important; height: 140px !important; }
      .qrcode-caption { font-size: 9px; margin-top: 4px; text-align: center; }
      .fiscal-meta { font-size: 10px; line-height: 1.25; }
    </style>
  </head>
  <body>
    <main class="receipt">
      <header class="center">
        <div class="company-title uppercase">${escapeHtml(data.razaoSocial)}</div>
        ${data.nomeFantasia && data.nomeFantasia !== data.razaoSocial ? `<div class="uppercase">${escapeHtml(data.nomeFantasia)}</div>` : ""}
        <div>CNPJ: ${escapeHtml(data.cnpj)}</div>
        ${data.inscricaoEstadual ? `<div>IE: ${escapeHtml(data.inscricaoEstadual)}</div>` : ""}
        ${data.endereco ? `<div>${escapeHtml(data.endereco)}</div>` : ""}
      </header>

      <div class="divider"></div>

      <section class="danfe-banner">
        <div class="danfe-title">DANFE NFC-e</div>
        <div class="danfe-subtitle">Documento Auxiliar da Nota Fiscal de Consumidor Eletrônica</div>
        <div class="danfe-subtitle bold">Não permite aproveitamento de crédito de ICMS</div>
      </section>

      ${
        data.emissaoContingencia
          ? `<div class="warning-box" style="border: 2px solid #000; padding: 5px; margin: 6px 0; text-align: center;">
              <div class="bold" style="font-size: 13px; letter-spacing: 0.5px;">EMITIDA EM CONTINGÊNCIA</div>
              <div class="bold" style="font-size: 10.5px;">Pendente de autorização</div>
              <div style="font-size: 9px; margin-top: 2px;">Data/Hora: ${escapeHtml(data.dhEmissao)}</div>
              ${data.justificativa ? `<div style="font-size: 8.5px; margin-top: 2px; word-break: break-word;">Motivo: ${escapeHtml(data.justificativa)}</div>` : ""}
            </div>`
          : ""
      }

      <div class="divider"></div>

      <section>
        <div class="grid-item bold">
          <span>#</span><span>ITEM</span><span class="right">QTD</span><span class="right">TOTAL</span>
        </div>
        ${itemsRows}
      </section>

      <div class="divider"></div>

      <section>
        <div class="line">
          <span>Qtd. total de itens</span>
          <span>${data.itens.length}</span>
        </div>
        <div class="line bold" style="font-size: 12px;">
          <span>VALOR TOTAL R$</span>
          <span>${formatMoney(data.totalLiquido)}</span>
        </div>

        <div style="margin-top: 4px;">
          <div class="bold">FORMA DE PAGAMENTO</div>
          ${data.pagamentos.map(p => `<div class="line"><span>${escapeHtml(p.forma)}</span><span>R$ ${formatMoney(p.valor)}</span></div>`).join("")}
          ${data.valorTroco > 0 ? `<div class="line bold"><span>Troco</span><span>R$ ${formatMoney(data.valorTroco)}</span></div>` : ""}
        </div>
      </section>

      <div class="divider"></div>

      <section class="center fiscal-meta">
        <div>Tributos Totais Incidentes (Lei Fed. 12.741/2012):</div>
        <div class="bold">R$ ${formatMoney(totalTributosEstimados)}</div>
      </section>

      <div class="divider"></div>

      <section class="fiscal-meta">
        <div class="center bold">
          NFC-e nº ${escapeHtml(formattedNumero)} &nbsp; Série ${data.serie}
        </div>
        <div class="center">
          Data de Emissão: ${escapeHtml(data.dhEmissao)}
        </div>
        <div class="center bold" style="margin-top: 2px;">Forma de Emissão: CONTINGÊNCIA OFFLINE</div>
        <div class="center" style="font-size: 8.5px; margin-top: 1px;">Transmissão obrigatória em até 24 horas</div>
      </section>

      <div class="divider"></div>

      <section class="fiscal-meta center">
        <div>Consulte pela Chave de Acesso em:</div>
        <div class="bold" style="font-size: 9.5px; word-break: break-all;">${escapeHtml(sefazUrl)}</div>
        <div style="margin-top: 4px; font-size: 9px;">CHAVE DE ACESSO:</div>
        <div class="chave-box">${escapeHtml(formattedChave)}</div>
      </section>

      <div class="divider"></div>

      <section class="fiscal-meta center">
        <div>CONSUMIDOR:</div>
        <div class="bold">
          ${
            data.customerCpf && data.customerCpf.trim().length > 0
              ? `CPF: ${escapeHtml(data.customerCpf)}`
              : "CONSUMIDOR NÃO IDENTIFICADO"
          }
        </div>
      </section>

      ${
        qrSvg
          ? `
            <div class="divider"></div>
            <section class="qrcode-wrapper">
              <div class="qrcode-box">${qrSvg}</div>
              <div class="qrcode-caption">Consulta via leitor de QR Code</div>
            </section>
          `
          : ""
      }

      <div class="divider"></div>
      <footer class="center fiscal-meta" style="font-size: 9px; color: #444;">
        <div>Local Gateway Offline - Emissão em Contingência</div>
        <div>Quack Sistemas - Soluções para seu Negócio</div>
      </footer>
    </main>

    <script>
      function triggerPrint() {
        setTimeout(function () {
          window.focus();
          window.print();
        }, 200);
      }
      if (document.readyState === "complete") {
        triggerPrint();
      } else {
        window.addEventListener("load", triggerPrint);
      }
    </script>
  </body>
</html>`;
}

export function openDanfeThermalWindow(
  data: DanfeThermalData,
  formatMoney: (value: number) => string
): void {
  const popup = window.open("", "_blank", "width=420,height=720");
  if (!popup) return;
  popup.document.open();
  popup.document.write(buildDanfeFromThermalData(data, formatMoney));
  popup.document.close();
}
