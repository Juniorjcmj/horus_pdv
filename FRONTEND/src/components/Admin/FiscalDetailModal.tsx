/**
 * Arquivo: src/components/Admin/FiscalDetailModal.tsx
 * Objetivo: modal de Raio-X com visão 360° do documento fiscal (NFC-e), incluindo chave,
 *           protocolos SEFAZ, tabela de itens tributados, motivos de erro e ações imediatas.
 * Entradas esperadas: documento fiscal, dados da empresa, callbacks para ações.
 */

import { useEffect, useState } from "react";
import {
  AlertCircle,
  AlertOctagon,
  Check,
  Copy,
  Download,
  ExternalLink,
  FileCode,
  Package,
  Printer,
  Receipt,
  RefreshCw,
  RotateCcw,
  X,
} from "lucide-react";
import {
  FISCAL_STATUS,
  fiscalService,
  fiscalStatusBadgeClass,
  fiscalStatusLabel,
  type FiscalDocumentDto,
  type FiscalDocumentItemDto,
} from "@/services/api/fiscalService";
import { getSefazConsultaUrl } from "@/utils/danfePrint";

type FiscalDetailModalProps = {
  document: FiscalDocumentDto;
  companyName: string;
  onClose: () => void;
  onPrintDanfe?: (doc: FiscalDocumentDto) => void;
  onOpenCancel?: (doc: FiscalDocumentDto) => void;
  onReemitir?: (doc: FiscalDocumentDto) => void;
  onDownloadXml?: (doc: FiscalDocumentDto, tipo?: "autorizado" | "cancelamento") => void;
};

function formatChave(chave: string | null) {
  if (!chave) return "—";
  return chave.replace(/(\d{4})(?=\d)/g, "$1 ").trim();
}

function formatDate(value: string | null) {
  if (!value) return "—";
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

export default function FiscalDetailModal({
  document,
  companyName,
  onClose,
  onPrintDanfe,
  onOpenCancel,
  onReemitir,
  onDownloadXml,
}: FiscalDetailModalProps) {
  const [copiedChave, setCopiedChave] = useState(false);
  const [items, setItems] = useState<FiscalDocumentItemDto[]>([]);
  const [loadingItems, setLoadingItems] = useState(true);

  useEffect(() => {
    let isMounted = true;
    setLoadingItems(true);
    fiscalService
      .getItens(document.id)
      .then((data) => {
        if (isMounted) setItems(data);
      })
      .catch(() => {
        if (isMounted) setItems([]);
      })
      .finally(() => {
        if (isMounted) setLoadingItems(false);
      });

    return () => {
      isMounted = false;
    };
  }, [document.id]);

  const handleCopyChave = () => {
    if (!document.chaveAcesso) return;
    navigator.clipboard.writeText(document.chaveAcesso);
    setCopiedChave(true);
    setTimeout(() => setCopiedChave(false), 2000);
  };

  const sefazUrl = document.chaveAcesso
    ? `${getSefazConsultaUrl()}?p=${document.chaveAcesso}`
    : null;

  const isDevolvido = document.status === FISCAL_STATUS.Devolvido || Boolean(document.devolvida);
  const isDevolucaoNfe = document.modelo === 55 && Boolean(document.chaveReferenciada);
  const isCancelado = document.status === FISCAL_STATUS.Cancelado;
  const isAutorizado =
    (document.status === FISCAL_STATUS.Autorizado ||
    document.status === FISCAL_STATUS.ContingenciaPendente) && !isDevolvido;
  const isRejeitado = document.status === FISCAL_STATUS.Rejeitado;

  return (
    <div className="fixed inset-0 z-layer-dialog flex items-end bg-black/60 px-3 backdrop-blur-sm md:items-center md:justify-center">
      <div className="w-full max-w-3xl overflow-hidden rounded-t-2xl border border-border-primary bg-bg-light shadow-2xl md:rounded-2xl max-h-[90vh] flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-border-primary px-5 py-4 shrink-0 bg-bg-secondary/40">
          <div className="flex items-center gap-3">
            <span className="inline-flex h-10 w-10 items-center justify-center rounded-xl bg-accent/10 text-accent">
              <Receipt size={22} />
            </span>
            <div>
              <div className="flex items-center gap-2">
                <h2 className="text-base font-bold text-text-primary">
                  {document.modelo === 55 ? "Nota Fiscal NF-e (Mod. 55)" : "Nota Fiscal NFC-e (Mod. 65)"} Nº {document.numeroNf}
                </h2>
                <span
                  className={`inline-flex rounded-full px-2.5 py-0.5 text-xs font-semibold ${fiscalStatusBadgeClass(
                    document.status,
                    document.modelo,
                    isDevolucaoNfe,
                  )}`}
                >
                  {fiscalStatusLabel(document.status, document.modelo, isDevolucaoNfe)}
                </span>
              </div>
              <p className="text-xs text-text-secondary">
                Venda vinculada: <span className="font-semibold text-text-primary">{document.saleNumber}</span> · Série {document.serie}
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg p-1 text-text-secondary hover:bg-hover-light hover:text-text-primary"
            aria-label="Fechar"
          >
            <X size={18} />
          </button>
        </div>

        {/* Corpo rolável */}
        <div className="overflow-y-auto p-5 space-y-4 flex-1">
          {/* Alerta de Status / SEFAZ */}
          {isDevolvido && (
            <div className="rounded-xl border border-purple-500/30 bg-purple-500/10 p-3.5 text-xs space-y-1">
              <div className="flex items-center gap-2 font-bold text-purple-400">
                <RotateCcw size={16} />
                Nota Fiscal Devolvida (Estorno Homologado)
              </div>
              <p className="text-text-secondary leading-relaxed">
                {document.numeroNfeDevolucao
                  ? `Esta nota fiscal foi devolvida perante a SEFAZ através da NF-e Modelo 55 Nº ${document.numeroNfeDevolucao}. O estoque físico foi devolvido e a venda cancelada.`
                  : (document.motivoStatus || "Esta nota foi estornada através da emissão de uma NF-e de Devolução (Modelo 55).")}
              </p>
            </div>
          )}

          {isDevolucaoNfe && (
            <div className="rounded-xl border border-indigo-500/30 bg-indigo-500/10 p-3.5 text-xs space-y-1">
              <div className="flex items-center gap-2 font-bold text-indigo-300">
                <FileCode size={16} />
                NF-e de Devolução de Entrada (Modelo 55)
              </div>
              <p className="text-text-secondary leading-relaxed">
                Documento fiscal emitido para formalizar a devolução da mercadoria ao estoque e regularização fiscal.
              </p>
              {document.chaveReferenciada && (
                <p className="font-mono text-[11px] text-text-secondary mt-1">
                  Chave Referenciada: {document.chaveReferenciada}
                </p>
              )}
            </div>
          )}

          {isCancelado && (
            <div className="rounded-xl border border-rose-500/30 bg-rose-500/10 p-3.5 text-xs space-y-1">
              <div className="flex items-center gap-2 font-bold text-rose-400">
                <AlertOctagon size={16} />
                Nota Fiscal Cancelada na SEFAZ
              </div>
              <p className="text-text-secondary leading-relaxed">
                {document.motivoStatus || "Documento fiscal homologado como cancelado perante a SEFAZ."}
              </p>
            </div>
          )}

          {isRejeitado && (
            <div className="rounded-xl border border-primary/30 bg-primary/10 p-3.5 text-xs space-y-1 text-primary">
              <div className="flex items-center gap-2 font-bold">
                <AlertCircle size={16} />
                Rejeição da SEFAZ ({document.tentativas} tentativas)
              </div>
              <p className="leading-relaxed font-mono">
                {document.motivoStatus || "A SEFAZ rejeitou o lote de emissão deste documento fiscal."}
              </p>
            </div>
          )}

          {/* Card Chave de Acesso */}
          {document.chaveAcesso ? (
            <div className="rounded-xl border border-border-secondary bg-bg-primary p-3.5 space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-xs font-semibold text-text-secondary uppercase tracking-wider">
                  Chave de Acesso (44 dígitos)
                </span>
                <div className="flex items-center gap-2">
                  {sefazUrl && (
                    <a
                      href={sefazUrl}
                      target="_blank"
                      rel="noreferrer"
                      className="inline-flex items-center gap-1 text-xs font-medium text-accent hover:underline"
                    >
                      Consultar no Portal SEFAZ <ExternalLink size={12} />
                    </a>
                  )}
                  <button
                    type="button"
                    onClick={handleCopyChave}
                    className="inline-flex items-center gap-1 rounded-md border border-border-primary bg-bg-secondary px-2 py-1 text-xs font-medium text-text-secondary hover:text-text-primary"
                  >
                    {copiedChave ? (
                      <>
                        <Check size={12} className="text-success" /> Copiado!
                      </>
                    ) : (
                      <>
                        <Copy size={12} /> Copiar Chave
                      </>
                    )}
                  </button>
                </div>
              </div>
              <div className="font-mono text-xs font-semibold text-text-primary break-all bg-bg-light p-2.5 rounded-lg border border-border-primary select-all">
                {formatChave(document.chaveAcesso)}
              </div>
            </div>
          ) : null}

          {/* Grid de Metadados Fiscais */}
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4 rounded-xl border border-border-secondary bg-bg-primary p-3.5 text-xs">
            <div>
              <span className="block text-text-secondary font-medium">Protocolo SEFAZ</span>
              <span className="block font-mono font-semibold text-text-primary mt-0.5 truncate" title={document.protocolo || ""}>
                {document.protocolo || "—"}
              </span>
            </div>
            <div>
              <span className="block text-text-secondary font-medium">Data de Autorização</span>
              <span className="block font-semibold text-text-primary mt-0.5">
                {formatDate(document.dhAutorizacao || document.criadoEm)}
              </span>
            </div>
            <div>
              <span className="block text-text-secondary font-medium">Valor Total da Venda</span>
              <span className="block font-bold text-accent text-sm mt-0.5">
                {document.totalAmount ? `R$ ${document.totalAmount}` : "—"}
              </span>
            </div>
            <div>
              <span className="block text-text-secondary font-medium">Forma de Pagamento</span>
              <span className="block font-semibold text-text-primary mt-0.5 capitalize truncate">
                {document.paymentType || "Dinheiro"}
              </span>
            </div>

            <div className="col-span-2 pt-2 border-t border-border-primary/60">
              <span className="block text-text-secondary font-medium">Emitente</span>
              <span className="block font-semibold text-text-primary mt-0.5 truncate">
                {companyName}
              </span>
            </div>
            <div className="col-span-2 pt-2 border-t border-border-primary/60">
              <span className="block text-text-secondary font-medium">Destinatário (Consumidor)</span>
              <span className="block font-semibold text-text-primary mt-0.5 truncate">
                {document.customerName || "Consumidor Final"} {document.customerCpf && document.customerCpf !== "-" ? `(${document.customerCpf})` : ""}
              </span>
            </div>
          </div>

          {/* Tabela de Itens Vendidos */}
          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <h3 className="text-xs font-bold text-text-primary uppercase tracking-wider flex items-center gap-1.5">
                <Package size={14} className="text-accent" />
                Itens e Tributação da Nota ({items.length})
              </h3>
            </div>

            <div className="rounded-xl border border-border-primary overflow-hidden">
              <div className="overflow-x-auto max-h-56">
                <table className="w-full text-left text-xs">
                  <thead className="bg-bg-primary text-text-secondary sticky top-0">
                    <tr>
                      <th className="px-3 py-2 font-semibold">Código</th>
                      <th className="px-3 py-2 font-semibold">Descrição</th>
                      <th className="px-3 py-2 font-semibold">NCM</th>
                      <th className="px-3 py-2 font-semibold">CFOP</th>
                      <th className="px-3 py-2 font-semibold text-right">Qtd</th>
                      <th className="px-3 py-2 font-semibold text-right">Unitário</th>
                      <th className="px-3 py-2 font-semibold text-right">Total</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border-primary">
                    {loadingItems ? (
                      <tr>
                        <td colSpan={7} className="p-4 text-center text-text-secondary">
                          Carregando itens da nota fiscal...
                        </td>
                      </tr>
                    ) : items.length === 0 ? (
                      <tr>
                        <td colSpan={7} className="p-4 text-center text-text-secondary">
                          Nenhum item registrado para este documento.
                        </td>
                      </tr>
                    ) : (
                      items.map((item, idx) => (
                        <tr key={idx} className="hover:bg-accent/5">
                          <td className="px-3 py-2 font-mono text-text-secondary">{item.productCode}</td>
                          <td className="px-3 py-2 font-medium text-text-primary max-w-[200px] truncate" title={item.productName}>
                            {item.productName}
                          </td>
                          <td className="px-3 py-2 font-mono text-text-secondary">{item.ncm || "—"}</td>
                          <td className="px-3 py-2 font-mono text-text-secondary">{item.cfop || "—"}</td>
                          <td className="px-3 py-2 text-right font-medium">
                            {item.quantity} {item.unidadeComercial || "UN"}
                          </td>
                          <td className="px-3 py-2 text-right text-text-secondary">R$ {item.unitPrice}</td>
                          <td className="px-3 py-2 text-right font-semibold text-text-primary">R$ {item.itemTotal}</td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>

        {/* Rodapé de Ações Rápidas */}
        <div className="flex flex-wrap items-center justify-between gap-2 border-t border-border-primary bg-bg-secondary/40 px-5 py-3 shrink-0">
          <div className="flex flex-wrap items-center gap-2">
            {/* Download XML Autorizado */}
            {document.hasXml && onDownloadXml && (
              <button
                type="button"
                onClick={() => onDownloadXml(document, "autorizado")}
                className="btn-secondary text-xs inline-flex items-center gap-1.5"
                title="Baixar XML oficial homologado pela SEFAZ"
              >
                <FileCode size={14} />
                Baixar XML
              </button>
            )}

            {/* Download XML Cancelamento */}
            {isCancelado && document.hasCancelXml && onDownloadXml && (
              <button
                type="button"
                onClick={() => onDownloadXml(document, "cancelamento")}
                className="btn-secondary text-xs inline-flex items-center gap-1.5"
                title="Baixar XML do evento de cancelamento homologado"
              >
                <Download size={14} />
                XML Cancelamento
              </button>
            )}

            {/* Imprimir DANFE 80mm */}
            {isAutorizado && onPrintDanfe && (
              <button
                type="button"
                onClick={() => onPrintDanfe(document)}
                className="btn-primary text-xs inline-flex items-center gap-1.5"
              >
                <Printer size={14} />
                Imprimir DANFE
              </button>
            )}
          </div>

          <div className="flex items-center gap-2">
            {/* Cancelar Nota (se autorizada) */}
            {isAutorizado && onOpenCancel && (
              <button
                type="button"
                onClick={() => onOpenCancel(document)}
                className="btn-danger text-xs inline-flex items-center gap-1.5"
              >
                <AlertOctagon size={14} />
                Cancelar NFC-e
              </button>
            )}

            {/* Reemitir (se rejeitada) */}
            {isRejeitado && onReemitir && (
              <button
                type="button"
                onClick={() => onReemitir(document)}
                className="btn-primary text-xs inline-flex items-center gap-1.5"
              >
                <RefreshCw size={14} />
                Reemitir NFC-e
              </button>
            )}

            <button type="button" onClick={onClose} className="btn-secondary text-xs">
              Fechar
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
