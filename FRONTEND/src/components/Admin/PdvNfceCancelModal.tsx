/**
 * Arquivo: src/components/Admin/PdvNfceCancelModal.tsx
 * Objetivo: modal para cancelamento ágil de NFC-e ou emissão de NF-e de Devolução (Modelo 55)
 *           referenciada na frente de caixa (PDV).
 *           Permite buscar por código da venda, número da nota ou chave de 44 dígitos com Enter,
 *           solicita a seleção de um gerente em dropdown e senha do supervisor, exige justificativa
 *           mínima de 15 caracteres (com botões de motivos sugeridos), detecta expiração do prazo de 30min
 *           e oferece impressão térmica em bobina e DANFE A4.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import {
  AlertOctagon,
  AlertTriangle,
  ArrowLeftRight,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  Clock,
  FileText,
  KeyRound,
  Loader2,
  Printer,
  Search,
  ShieldCheck,
  Sparkles,
  UserCheck,
  X,
} from "lucide-react";
import { Toast } from "@/hooks/Dialog";
import {
  FISCAL_STATUS,
  fiscalService,
  fiscalStatusBadgeClass,
  fiscalStatusLabel,
  type CancelarComSupervisorResult,
  type DevolverNfceResult,
  type FiscalDocumentDetailDto,
} from "@/services/api/fiscalService";
import { userService, type SupervisorDto } from "@/services/api/userService";
import { formatChaveAcesso, formatNumeroNf } from "@/utils/danfePrint";

type PdvNfceCancelModalProps = {
  isOpen: boolean;
  onClose: () => void;
  onSuccess?: () => void;
};

type ModalOperationMode = "cancelar" | "devolver";

const MOTIVOS_CANCELAMENTO = [
  "Desistência da compra pelo cliente.",
  "Erro na forma de pagamento registrada.",
  "Erro nas quantidades ou itens lançados.",
  "Emissão duplicada de documento fiscal.",
];

const MOTIVOS_DEVOLUCAO = [
  "Devolução de mercadoria pelo cliente.",
  "Estorno de venda com prazo de NFC-e expirado.",
  "Devolução por desistência do consumidor.",
  "Troca e estorno integral de produtos.",
];

function formatDate(value?: string | null) {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("pt-BR", {
    timeZone: "America/Sao_Paulo",
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function getMinutesElapsed(dhAutorizacao?: string | null, criadoEm?: string | null): number {
  const ts = dhAutorizacao || criadoEm;
  if (!ts) return 0;
  const date = new Date(ts);
  if (Number.isNaN(date.getTime())) return 0;
  return Math.max(0, Math.floor((Date.now() - date.getTime()) / (1000 * 60)));
}

function isOver30Minutes(dhAutorizacao?: string | null, criadoEm?: string | null): boolean {
  return getMinutesElapsed(dhAutorizacao, criadoEm) > 30;
}

function buildCancellationPrintHtml(
  doc: FiscalDocumentDetailDto,
  result: CancelarComSupervisorResult,
  justificativa: string
): string {
  const cancelDate = new Date().toLocaleString("pt-BR", {
    timeZone: "America/Sao_Paulo",
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });

  return `<!DOCTYPE html>
<html lang="pt-BR">
<head>
  <meta charset="utf-8" />
  <title>Comprovante de Cancelamento NFC-e</title>
  <style>
    @page { margin: 0; size: 80mm auto; }
    body {
      font-family: 'Courier New', Courier, monospace;
      font-size: 12px;
      color: #000;
      background: #fff;
      margin: 0;
      padding: 10px;
      width: 72mm;
      max-width: 100%;
    }
    .text-center { text-align: center; }
    .text-right { text-align: right; }
    .bold { font-weight: bold; }
    .divider { border-top: 1px dashed #000; margin: 8px 0; }
    .header { font-size: 14px; margin-bottom: 4px; }
    .small { font-size: 10px; word-break: break-all; }
    .row { display: flex; justify-content: space-between; margin: 3px 0; }
  </style>
</head>
<body>
  <div class="text-center bold header">COMPROVANTE DE CANCELAMENTO</div>
  <div class="text-center small">NFC-e (MODELO 65)</div>
  <div class="divider"></div>

  <div class="row">
    <span>NFC-e Cancelada:</span>
    <span class="bold">Nº ${formatNumeroNf(doc.numeroNf)}</span>
  </div>
  <div class="row">
    <span>Série:</span>
    <span>${doc.serie}</span>
  </div>
  <div class="row">
    <span>Venda:</span>
    <span class="bold">#${doc.saleNumber}</span>
  </div>
  <div class="row">
    <span>Valor:</span>
    <span class="bold">R$ ${doc.totalAmount}</span>
  </div>
  <div class="divider"></div>

  <div class="bold">CHAVE DE ACESSO:</div>
  <div class="small">${formatChaveAcesso(doc.chaveAcesso) || "—"}</div>
  <div class="divider"></div>

  <div class="row">
    <span>Prot. Cancelamento:</span>
    <span class="bold">${result.protocoloCancelamento || "—"}</span>
  </div>
  <div class="row">
    <span>Data Cancelamento:</span>
    <span>${cancelDate}</span>
  </div>
  <div class="row">
    <span>Autorizado por:</span>
    <span class="bold">${result.supervisorNome || "Gerente"}</span>
  </div>
  <div class="divider"></div>

  <div class="bold">JUSTIFICATIVA:</div>
  <div class="small">${justificativa}</div>
  <div class="divider"></div>

  <div class="text-center bold small" style="margin-top: 6px;">
    CANCELAMENTO HOMOLOGADO NA SEFAZ<br />
    MERCADORIAS ESTORNADAS AO ESTOQUE
  </div>
</body>
</html>`;
}

function buildDevolucaoPrintHtml(
  doc: FiscalDocumentDetailDto,
  result: DevolverNfceResult,
  justificativa: string
): string {
  const dataDevolucao = new Date().toLocaleString("pt-BR", {
    timeZone: "America/Sao_Paulo",
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });

  return `<!DOCTYPE html>
<html lang="pt-BR">
<head>
  <meta charset="utf-8" />
  <title>Comprovante de Devolução Fiscal (NF-e 55)</title>
  <style>
    @page { margin: 0; size: 80mm auto; }
    body {
      font-family: 'Courier New', Courier, monospace;
      font-size: 12px;
      color: #000;
      background: #fff;
      margin: 0;
      padding: 10px;
      width: 72mm;
      max-width: 100%;
    }
    .text-center { text-align: center; }
    .text-right { text-align: right; }
    .bold { font-weight: bold; }
    .divider { border-top: 1px dashed #000; margin: 8px 0; }
    .header { font-size: 13px; margin-bottom: 4px; }
    .small { font-size: 10px; word-break: break-all; }
    .row { display: flex; justify-content: space-between; margin: 3px 0; }
    .signature { margin-top: 25px; border-top: 1px solid #000; text-align: center; padding-top: 4px; }
  </style>
</head>
<body>
  <div class="text-center bold header">COMPROVANTE DE DEVOLUÇÃO</div>
  <div class="text-center small">NF-e DE ENTRADA (MODELO 55)</div>
  <div class="divider"></div>

  <div class="row">
    <span>NF-e Entrada:</span>
    <span class="bold">Nº ${formatNumeroNf(result.numeroNfe)} / Série ${result.serieNfe}</span>
  </div>
  <div class="row">
    <span>NFC-e Referenciada:</span>
    <span class="bold">Nº ${formatNumeroNf(doc.numeroNf)}</span>
  </div>
  <div class="row">
    <span>Venda Origem:</span>
    <span class="bold">#${doc.saleNumber}</span>
  </div>
  <div class="row">
    <span>Valor Total:</span>
    <span class="bold">R$ ${doc.totalAmount}</span>
  </div>
  <div class="divider"></div>

  <div class="bold">CHAVE NF-e DEVOLUÇÃO:</div>
  <div class="small">${formatChaveAcesso(result.chaveAcesso) || "—"}</div>
  <div class="divider"></div>

  <div class="row">
    <span>Prot. Autorização:</span>
    <span class="bold">${result.protocolo || "—"}</span>
  </div>
  <div class="row">
    <span>Data/Hora:</span>
    <span>${dataDevolucao}</span>
  </div>
  <div class="row">
    <span>Autorizado por:</span>
    <span class="bold">${result.supervisorNome || "Gerente"}</span>
  </div>
  <div class="row">
    <span>Destinatário/Remetente:</span>
    <span class="bold">${result.destinatarioNome || "Entrada Própria"}</span>
  </div>
  <div class="divider"></div>

  <div class="bold">JUSTIFICATIVA:</div>
  <div class="small">${justificativa}</div>
  <div class="divider"></div>

  <div class="signature">
    Assinatura do Cliente / Recebedor<br />
    <span class="small">Atesto a devolução das mercadorias e estorno financeiro</span>
  </div>

  <div class="text-center bold small" style="margin-top: 12px;">
    DEVOLUÇÃO FISCAL HOMOLOGADA NA SEFAZ<br />
    MERCADORIAS ESTORNADAS AO ESTOQUE
  </div>
</body>
</html>`;
}

function buildDevolucaoDanfeA4Html(
  doc: FiscalDocumentDetailDto,
  result: DevolverNfceResult,
  justificativa: string
): string {
  const dataDevolucao = new Date().toLocaleString("pt-BR", {
    timeZone: "America/Sao_Paulo",
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });

  return `<!DOCTYPE html>
<html lang="pt-BR">
<head>
  <meta charset="utf-8" />
  <title>DANFE NF-e Nº ${result.numeroNfe} - Devolução de Entrada</title>
  <style>
    @page { size: A4 portrait; margin: 10mm; }
    body {
      font-family: Arial, sans-serif;
      font-size: 11px;
      color: #000;
      background: #fff;
      margin: 0;
      padding: 0;
    }
    .box { border: 1px solid #000; margin-bottom: 6px; padding: 6px; }
    .text-center { text-align: center; }
    .text-right { text-align: right; }
    .bold { font-weight: bold; }
    .title { font-size: 14px; font-weight: bold; }
    .row { display: flex; justify-content: space-between; }
    .barcode { letter-spacing: 2px; font-family: monospace; font-size: 13px; font-weight: bold; }
  </style>
</head>
<body>
  <div class="box">
    <div style="display: flex; justify-content: space-between; align-items: center;">
      <div style="width: 50%;">
        <div class="title">DOCUMENTO AUXILIAR DA NOTA FISCAL ELETRÔNICA</div>
        <div class="bold">0 - ENTRADA &nbsp;&nbsp; [ 0 ]</div>
        <div>Nº ${formatNumeroNf(result.numeroNfe)} &nbsp;&nbsp; SÉRIE: ${result.serieNfe}</div>
        <div>NATUREZA DA OPERAÇÃO: <strong>DEVOLUÇÃO DE VENDA</strong></div>
      </div>
      <div style="width: 50%; text-align: right;">
        <div class="bold">CHAVE DE ACESSO</div>
        <div class="barcode">${formatChaveAcesso(result.chaveAcesso)}</div>
        <div style="margin-top: 4px;">PROTOCOLO SEFAZ: <strong>${result.protocolo}</strong> - ${dataDevolucao}</div>
      </div>
    </div>
  </div>

  <div class="box">
    <div class="bold" style="margin-bottom: 2px;">DESTINATÁRIO / REMETENTE</div>
    <div class="row">
      <span>NOME / RAZÃO SOCIAL: <strong>${result.destinatarioNome || "ENTRADA PRÓPRIA"}</strong></span>
      <span>DATA DA EMISSÃO: <strong>${dataDevolucao}</strong></span>
    </div>
  </div>

  <div class="box">
    <div class="bold" style="margin-bottom: 2px;">DOCUMENTOS FISCAIS REFERENCIADOS</div>
    <div>NFC-e Referenciada (Modelo 65): <strong>${formatChaveAcesso(doc.chaveAcesso)}</strong></div>
    <div>Venda de Origem: <strong>#${doc.saleNumber}</strong> &nbsp;|&nbsp; NFC-e Original Nº: <strong>${formatNumeroNf(doc.numeroNf)}</strong></div>
  </div>

  <div class="box">
    <div class="bold" style="margin-bottom: 2px;">CÁLCULO DO IMPOSTO</div>
    <div class="row">
      <span>VALOR TOTAL DOS PRODUTOS: <strong>R$ ${doc.totalAmount}</strong></span>
      <span>VALOR DO FRETE: <strong>R$ 0,00</strong></span>
      <span>VALOR TOTAL DA NOTA: <strong>R$ ${doc.totalAmount}</strong></span>
    </div>
  </div>

  <div class="box">
    <div class="bold" style="margin-bottom: 4px;">DADOS ADICIONAIS / INFORMAÇÕES COMPLEMENTARES</div>
    <div style="font-size: 10px;">
      Devolução de venda de mercadorias referente à NFC-e chave ${doc.chaveAcesso || ""}.<br />
      Justificativa: ${justificativa}.<br />
      Supervisor Responsável: ${result.supervisorNome}.
    </div>
  </div>

  <div style="margin-top: 40px; display: flex; justify-content: space-around;">
    <div style="border-top: 1px solid #000; width: 45%; text-align: center; padding-top: 4px;">
      Assinatura do Recebedor / Cliente
    </div>
    <div style="border-top: 1px solid #000; width: 45%; text-align: center; padding-top: 4px;">
      Visto do Supervisor / Gerente
    </div>
  </div>
</body>
</html>`;
}

export default function PdvNfceCancelModal({
  isOpen,
  onClose,
  onSuccess,
}: PdvNfceCancelModalProps) {
  const [mode, setMode] = useState<ModalOperationMode>("cancelar");
  const [codigoInput, setCodigoInput] = useState("");
  const [isSearching, setIsSearching] = useState(false);
  const [documento, setDocumento] = useState<FiscalDocumentDetailDto | null>(null);
  const [searchError, setSearchError] = useState<string | null>(null);

  const [supervisores, setSupervisores] = useState<SupervisorDto[]>([]);
  const [loadingSupervisores, setLoadingSupervisores] = useState(false);
  const [selectedSupervisorId, setSelectedSupervisorId] = useState("");
  const [supervisorPassword, setSupervisorPassword] = useState("");
  const [justificativa, setJustificativa] = useState("");
  const [isSubmitting, setIsSubmitting] = useState(false);

  // Dados opcionais do consumidor para Devolução
  const [showClienteInputs, setShowClienteInputs] = useState(false);
  const [clienteCpf, setClienteCpf] = useState("");
  const [clienteNome, setClienteNome] = useState("");

  const [cancelResult, setCancelResult] = useState<CancelarComSupervisorResult | null>(null);
  const [devolucaoResult, setDevolucaoResult] = useState<DevolverNfceResult | null>(null);
  const [sefazRejeicaoPrazo, setSefazRejeicaoPrazo] = useState<string | null>(null);

  const codigoInputRef = useRef<HTMLInputElement>(null);
  const passwordInputRef = useRef<HTMLInputElement>(null);

  const trimmedJustificativa = justificativa.trim();
  const charCount = trimmedJustificativa.length;
  const isJustificativaValid = charCount >= 15;
  const isAuthorized = documento?.status === FISCAL_STATUS.Autorizado;
  const canConfirm =
    isAuthorized &&
    selectedSupervisorId.trim() !== "" &&
    supervisorPassword.trim() !== "" &&
    isJustificativaValid &&
    !isSubmitting;

  const expired30Min = documento ? isOver30Minutes(documento.dhAutorizacao, documento.criadoEm) : false;
  const minutosDecorridos = documento ? getMinutesElapsed(documento.dhAutorizacao, documento.criadoEm) : 0;

  // Carrega supervisores ao abrir o modal
  useEffect(() => {
    if (!isOpen) {
      setMode("cancelar");
      setCodigoInput("");
      setDocumento(null);
      setSearchError(null);
      setSelectedSupervisorId("");
      setSupervisorPassword("");
      setJustificativa("");
      setClienteCpf("");
      setClienteNome("");
      setShowClienteInputs(false);
      setCancelResult(null);
      setDevolucaoResult(null);
      setSefazRejeicaoPrazo(null);
      setIsSubmitting(false);
      return;
    }

    setLoadingSupervisores(true);
    userService
      .listSupervisores()
      .then((list) => {
        setSupervisores(list);
        if (list.length === 1) {
          setSelectedSupervisorId(list[0].id);
        }
      })
      .catch(() => {
        Toast.error("Não foi possível carregar a lista de gerentes.");
      })
      .finally(() => {
        setLoadingSupervisores(false);
      });

    const timer = setTimeout(() => {
      codigoInputRef.current?.focus();
    }, 100);
    return () => clearTimeout(timer);
  }, [isOpen]);

  const handleSearch = useCallback(async () => {
    const termo = codigoInput.trim();
    if (!termo) {
      Toast.error("Informe o código da venda, número da nota ou chave de acesso.");
      return;
    }

    setIsSearching(true);
    setSearchError(null);
    setDocumento(null);
    setSefazRejeicaoPrazo(null);
    try {
      const doc = await fiscalService.buscarPorCodigo(termo);
      if (!doc) {
        setSearchError("Nenhum documento fiscal encontrado com os dados informados.");
        return;
      }
      setDocumento(doc);

      // Preenche dados do cliente se já existirem
      if (doc.customerCpf) setClienteCpf(doc.customerCpf);
      if (doc.customerName) setClienteNome(doc.customerName);

      // Se passou de 30 minutos, sugere Devolução automaticamente
      if (isOver30Minutes(doc.dhAutorizacao, doc.criadoEm)) {
        setMode("devolver");
        setJustificativa(MOTIVOS_DEVOLUCAO[1]);
      } else {
        setMode("cancelar");
        setJustificativa(MOTIVOS_CANCELAMENTO[0]);
      }

      if (doc.status === FISCAL_STATUS.Autorizado) {
        window.setTimeout(() => passwordInputRef.current?.focus(), 150);
      }
    } catch (error) {
      setSearchError(error instanceof Error ? error.message : "Erro ao buscar documento fiscal.");
    } finally {
      setIsSearching(false);
    }
  }, [codigoInput]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!documento || !canConfirm) return;

    setIsSubmitting(true);
    setSefazRejeicaoPrazo(null);

    if (mode === "cancelar") {
      try {
        const response = await fiscalService.cancelarComSupervisor(documento.id, {
          supervisorId: selectedSupervisorId,
          supervisorPassword,
          justificativa: trimmedJustificativa,
        });

        if (!response.success) {
          const msg = response.message || "Falha ao cancelar NFC-e.";
          // Detecta rejeição por prazo expirado
          if (
            msg.toLowerCase().includes("prazo") ||
            msg.toLowerCase().includes("30") ||
            msg.toLowerCase().includes("502") ||
            msg.toLowerCase().includes("218") ||
            msg.toLowerCase().includes("superior ao previsto")
          ) {
            setSefazRejeicaoPrazo(msg);
            setMode("devolver");
            setJustificativa(MOTIVOS_DEVOLUCAO[1]);
            Toast.info("O prazo de 30min expirou. Alterne para Devolução (NF-e 55).");
            return;
          }
          Toast.error(msg);
          return;
        }

        setCancelResult(
          response.data || {
            documentoId: documento.id,
            protocoloCancelamento: "Homologado",
            supervisorNome: supervisores.find((s) => s.id === selectedSupervisorId)?.name,
          }
        );
        Toast.success("NFC-e cancelada e mercadorias estornadas ao estoque!");
        onSuccess?.();
      } catch (error) {
        const msg = error instanceof Error ? error.message : "Erro ao processar cancelamento.";
        if (
          msg.toLowerCase().includes("prazo") ||
          msg.toLowerCase().includes("30") ||
          msg.toLowerCase().includes("502") ||
          msg.toLowerCase().includes("218") ||
          msg.toLowerCase().includes("superior ao previsto")
        ) {
          setSefazRejeicaoPrazo(msg);
          setMode("devolver");
          setJustificativa(MOTIVOS_DEVOLUCAO[1]);
          Toast.info("Prazo legal de cancelamento expirado na SEFAZ. Prossiga com a Devolução.");
        } else {
          Toast.error(msg);
        }
      } finally {
        setIsSubmitting(false);
      }
    } else {
      // Modo Devolução (NF-e 55)
      try {
        const destPayload =
          clienteCpf.trim() || clienteNome.trim()
            ? {
                cpfCnpj: clienteCpf.trim(),
                nome: clienteNome.trim() || "Consumidor Final",
                indIeDest: 9,
              }
            : undefined;

        const response = await fiscalService.devolverNfce(documento.id, {
          supervisorId: selectedSupervisorId,
          supervisorPassword,
          justificativa: trimmedJustificativa,
          destinatario: destPayload,
        });

        if (!response.success) {
          Toast.error(response.message || "Falha ao emitir NF-e de Devolução.");
          return;
        }

        if (response.data) {
          setDevolucaoResult(response.data);
          Toast.success("NF-e de Devolução autorizada na SEFAZ e estoque estornado!");
          onSuccess?.();
        }
      } catch (error) {
        Toast.error(error instanceof Error ? error.message : "Erro ao emitir NF-e de devolução.");
      } finally {
        setIsSubmitting(false);
      }
    }
  };

  const handlePrintCancellation = () => {
    if (!documento || !cancelResult) return;
    const html = buildCancellationPrintHtml(documento, cancelResult, trimmedJustificativa);
    const blob = new Blob([html], { type: "text/html;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const popup = window.open(url, "_blank", "width=420,height=680");
    if (popup) {
      popup.addEventListener("afterprint", () => {
        popup.close();
        URL.revokeObjectURL(url);
      });
    } else {
      URL.revokeObjectURL(url);
    }
  };

  const handlePrintDevolucaoTermica = () => {
    if (!documento || !devolucaoResult) return;
    const html = buildDevolucaoPrintHtml(documento, devolucaoResult, trimmedJustificativa);
    const blob = new Blob([html], { type: "text/html;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const popup = window.open(url, "_blank", "width=420,height=680");
    if (popup) {
      popup.addEventListener("afterprint", () => {
        popup.close();
        URL.revokeObjectURL(url);
      });
    } else {
      URL.revokeObjectURL(url);
    }
  };

  const handlePrintDevolucaoA4 = () => {
    if (!documento || !devolucaoResult) return;
    const html = buildDevolucaoDanfeA4Html(documento, devolucaoResult, trimmedJustificativa);
    const blob = new Blob([html], { type: "text/html;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const popup = window.open(url, "_blank", "width=850,height=900");
    if (popup) {
      popup.addEventListener("afterprint", () => {
        popup.close();
        URL.revokeObjectURL(url);
      });
    } else {
      URL.revokeObjectURL(url);
    }
  };

  if (!isOpen) return null;

  return (
    <div
      className="fixed inset-0 z-layer-dialog flex items-center justify-center bg-black/60 px-3 backdrop-blur-sm"
      onKeyDown={(e) => {
        if (e.key === "Escape" && !isSubmitting) {
          e.preventDefault();
          onClose();
        }
      }}
    >
      <div className="w-full max-w-xl overflow-hidden rounded-2xl border border-border-primary bg-bg-light shadow-2xl">
        {/* Cabeçalho */}
        <div
          className={`flex items-center justify-between border-b border-border-primary px-5 py-3.5 ${
            mode === "devolver" ? "bg-accent/10" : "bg-danger/10"
          }`}
        >
          <div className="flex items-center gap-2.5">
            <span
              className={`inline-flex h-9 w-9 items-center justify-center rounded-xl ${
                mode === "devolver" ? "bg-accent/20 text-accent" : "bg-danger/20 text-danger"
              }`}
            >
              {mode === "devolver" ? <ArrowLeftRight size={20} /> : <AlertOctagon size={20} />}
            </span>
            <div>
              <h2 className="text-sm font-bold text-text-primary">
                {mode === "devolver"
                  ? "Devolução Fiscal (NF-e 55 Entrada)"
                  : "Cancelar NFC-e no Caixa"}
              </h2>
              <p className="text-[11px] text-text-secondary">
                {mode === "devolver"
                  ? "Emissão de NF-e Modelo 55 de entrada referenciando a NFC-e"
                  : "Cancelamento oficial perante a SEFAZ com autorização gerencial"}
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={isSubmitting}
            className="rounded-lg p-1.5 text-text-secondary hover:bg-hover-light hover:text-text-primary"
            aria-label="Fechar"
          >
            <X size={18} />
          </button>
        </div>

        {/* Estado 1: Tela de Sucesso de Cancelamento Direto */}
        {cancelResult ? (
          <div className="p-6 text-center space-y-4">
            <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-success/15 text-success">
              <CheckCircle2 size={32} />
            </div>
            <div>
              <h3 className="text-base font-bold text-text-primary">NFC-e Cancelada com Sucesso!</h3>
              <p className="mt-1 text-xs text-text-secondary">
                O evento de cancelamento foi homologado pela SEFAZ. O estoque dos produtos foi estornado
                e a venda marcada como cancelada.
              </p>
            </div>

            <div className="rounded-xl border border-border-secondary bg-bg-primary p-3 text-xs text-left space-y-1">
              <div className="flex justify-between">
                <span className="text-text-secondary">Venda:</span>
                <span className="font-semibold text-text-primary">#{documento?.saleNumber}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-text-secondary">NFC-e Nº:</span>
                <span className="font-semibold text-text-primary">{formatNumeroNf(documento?.numeroNf ?? 0)}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-text-secondary">Protocolo SEFAZ:</span>
                <span className="font-mono font-semibold text-text-primary">
                  {cancelResult.protocoloCancelamento || "Homologado"}
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-text-secondary">Autorizado por:</span>
                <span className="font-semibold text-text-primary">
                  {cancelResult.supervisorNome || "Gerente"}
                </span>
              </div>
            </div>

            <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-center pt-2">
              <button
                type="button"
                onClick={onClose}
                className="btn-secondary text-xs py-2 px-4"
              >
                Concluir e Voltar ao Caixa
              </button>
              <button
                type="button"
                onClick={handlePrintCancellation}
                className="btn-primary inline-flex items-center justify-center gap-2 text-xs py-2 px-4 font-semibold"
              >
                <Printer size={15} />
                Imprimir Comprovante
              </button>
            </div>
          </div>
        ) : devolucaoResult ? (
          /* Estado 2: Tela de Sucesso de Devolução (NF-e 55) */
          <div className="p-6 text-center space-y-4">
            <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-success/15 text-success">
              <CheckCircle2 size={32} />
            </div>
            <div>
              <h3 className="text-base font-bold text-text-primary">NF-e de Devolução Homologada!</h3>
              <p className="mt-1 text-xs text-text-secondary">
                A NF-e Modelo 55 de Entrada foi autorizada pela SEFAZ. O débito fiscal foi anulado e as
                mercadorias foram devolvidas ao estoque da loja.
              </p>
            </div>

            <div className="rounded-xl border border-border-secondary bg-bg-primary p-3 text-xs text-left space-y-1.5">
              <div className="flex justify-between">
                <span className="text-text-secondary">NF-e Devolução:</span>
                <span className="font-bold text-text-primary">
                  Nº {formatNumeroNf(devolucaoResult.numeroNfe)} (Série {devolucaoResult.serieNfe})
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-text-secondary">NFC-e Referenciada:</span>
                <span className="font-semibold text-text-primary">{formatNumeroNf(documento?.numeroNf ?? 0)}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-text-secondary">Protocolo SEFAZ:</span>
                <span className="font-mono font-semibold text-text-primary">{devolucaoResult.protocolo}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-text-secondary">Destinatário/Remetente:</span>
                <span className="font-semibold text-text-primary">{devolucaoResult.destinatarioNome}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-text-secondary">Autorizado por:</span>
                <span className="font-semibold text-text-primary">{devolucaoResult.supervisorNome}</span>
              </div>
            </div>

            <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-center pt-2">
              <button
                type="button"
                onClick={onClose}
                className="btn-secondary text-xs py-2 px-3"
              >
                Concluir e Voltar ao Caixa
              </button>
              <button
                type="button"
                onClick={handlePrintDevolucaoTermica}
                className="btn-primary inline-flex items-center justify-center gap-1.5 text-xs py-2 px-3 font-semibold"
              >
                <Printer size={14} />
                Comprovante Térmico (Assinatura)
              </button>
              <button
                type="button"
                onClick={handlePrintDevolucaoA4}
                className="btn-secondary inline-flex items-center justify-center gap-1.5 text-xs py-2 px-3 font-semibold"
              >
                <FileText size={14} />
                Visualizar DANFE A4
              </button>
            </div>
          </div>
        ) : (
          /* Estado Normal: Formulário */
          <form onSubmit={handleSubmit} className="p-5 space-y-4">
            {/* Campo de Busca do Código da NFC-e */}
            <div className="space-y-1">
              <label htmlFor="codigo-nfce" className="block text-xs font-semibold text-text-primary">
                Código da NFC-e, Número da Venda ou Chave de Acesso <span className="text-primary">*</span>
              </label>
              <div className="flex gap-2">
                <div className="relative flex-1">
                  <input
                    ref={codigoInputRef}
                    id="codigo-nfce"
                    type="text"
                    value={codigoInput}
                    onChange={(e) => setCodigoInput(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") {
                        e.preventDefault();
                        void handleSearch();
                      }
                    }}
                    placeholder="Digite o número e tecle Enter (ou bipe a chave de acesso)..."
                    className="input-field w-full text-xs pr-8"
                    disabled={isSearching || isSubmitting}
                  />
                  {codigoInput && (
                    <button
                      type="button"
                      onClick={() => {
                        setCodigoInput("");
                        setDocumento(null);
                        setSearchError(null);
                        setSefazRejeicaoPrazo(null);
                        codigoInputRef.current?.focus();
                      }}
                      className="absolute right-2.5 top-1/2 -translate-y-1/2 text-text-secondary hover:text-text-primary"
                    >
                      <X size={14} />
                    </button>
                  )}
                </div>
                <button
                  type="button"
                  onClick={() => void handleSearch()}
                  disabled={isSearching || isSubmitting || !codigoInput.trim()}
                  className="btn-primary inline-flex items-center gap-1.5 px-3.5 text-xs font-semibold"
                >
                  {isSearching ? <Loader2 size={14} className="animate-spin" /> : <Search size={14} />}
                  <span>Buscar</span>
                </button>
              </div>
              <p className="text-[11px] text-text-secondary">
                Dica: Digite o número da venda (ex.: 1024), número da NFC-e ou bipe a chave de 44 dígitos e aperte <strong>Enter</strong>.
              </p>
            </div>

            {/* Alerta de erro na busca */}
            {searchError && (
              <div className="flex items-center gap-2 rounded-xl border border-rose-500/30 bg-rose-500/10 p-2.5 text-xs text-rose-500">
                <AlertTriangle size={15} className="shrink-0" />
                <span>{searchError}</span>
              </div>
            )}

            {/* Card com Detalhes da Nota Localizada */}
            {documento && (
              <div className="space-y-3">
                <div className="rounded-xl border border-border-secondary bg-bg-primary p-3.5 text-xs space-y-2.5">
                  <div className="flex items-center justify-between border-b border-border-primary/60 pb-2">
                    <span className="font-bold text-text-primary">
                      Venda #{documento.saleNumber} · NFC-e {formatNumeroNf(documento.numeroNf)} (Série {documento.serie})
                    </span>
                    <span className={`rounded-lg px-2 py-0.5 text-[10px] font-bold ${fiscalStatusBadgeClass(documento.status)}`}>
                      {fiscalStatusLabel(documento.status)}
                    </span>
                  </div>

                  <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                    <div>
                      <span className="block text-[11px] text-text-secondary">Valor Total</span>
                      <span className="block font-bold text-text-primary text-sm">
                        {documento.totalAmount ? `R$ ${documento.totalAmount}` : "—"}
                      </span>
                    </div>
                    <div>
                      <span className="block text-[11px] text-text-secondary">Emitido em</span>
                      <span className="block font-semibold text-text-primary">
                        {formatDate(documento.dhAutorizacao || documento.criadoEm)}
                      </span>
                    </div>
                    <div>
                      <span className="block text-[11px] text-text-secondary">Protocolo SEFAZ</span>
                      <span className="block font-mono font-semibold text-text-primary truncate" title={documento.protocolo || ""}>
                        {documento.protocolo || "—"}
                      </span>
                    </div>
                    <div>
                      <span className="block text-[11px] text-text-secondary">Cliente</span>
                      <span className="block font-semibold text-text-primary truncate" title={documento.customerName || "Consumidor"}>
                        {documento.customerName || "Consumidor"}
                      </span>
                    </div>
                  </div>

                  {documento.chaveAcesso && (
                    <div className="border-t border-border-primary/60 pt-2 text-[11px] text-text-secondary">
                      <span className="block font-medium">Chave de Acesso:</span>
                      <span className="block font-mono break-all">{formatChaveAcesso(documento.chaveAcesso)}</span>
                    </div>
                  )}
                </div>

                {!isAuthorized && (
                  <div className="flex items-center gap-2 rounded-xl border border-amber-500/30 bg-amber-500/10 p-3 text-xs text-amber-600 dark:text-amber-400">
                    <AlertTriangle size={16} className="shrink-0" />
                    <span>
                      Esta NFC-e está com status <strong>{fiscalStatusLabel(documento.status)}</strong> e não pode ser estornada. Apenas notas com status <strong>Autorizado</strong> são passíveis de cancelamento ou devolução perante a SEFAZ.
                    </span>
                  </div>
                )}

                {/* Banner de Aviso de Prazo de 30 minutos Expirado */}
                {isAuthorized && expired30Min && (
                  <div className="rounded-xl border border-amber-500/30 bg-amber-500/10 p-3 text-xs space-y-2">
                    <div className="flex items-start gap-2 text-amber-700 dark:text-amber-300 font-semibold">
                      <Clock size={16} className="shrink-0 mt-0.5" />
                      <div>
                        <span>Prazo legal de 30 minutos expirado ({minutosDecorridos} min desde a autorização).</span>
                        <p className="font-normal text-[11px] text-text-secondary mt-0.5">
                          A SEFAZ rejeitará o cancelamento direto. Para anular os tributos e reverter o estoque conforme a legislação, emita uma <strong>NF-e de Devolução (Modelo 55)</strong>.
                        </p>
                      </div>
                    </div>
                  </div>
                )}

                {/* Mensagem de rejeição SEFAZ com botão de transição para devolução */}
                {sefazRejeicaoPrazo && (
                  <div className="rounded-xl border border-rose-500/30 bg-rose-500/10 p-3 text-xs space-y-2">
                    <div className="flex items-start gap-2 text-rose-600 dark:text-rose-400 font-semibold">
                      <AlertOctagon size={16} className="shrink-0 mt-0.5" />
                      <div>
                        <span>Rejeição da SEFAZ: Prazo expirado.</span>
                        <p className="font-normal text-[11px] text-text-secondary mt-0.5">
                          {sefazRejeicaoPrazo}
                        </p>
                      </div>
                    </div>
                  </div>
                )}

                {/* Abas de Alternância de Modo (se nota autorizada) */}
                {isAuthorized && (
                  <div className="flex rounded-xl border border-border-primary bg-bg-secondary p-1 text-xs">
                    <button
                      type="button"
                      onClick={() => {
                        setMode("cancelar");
                        setJustificativa(MOTIVOS_CANCELAMENTO[0]);
                      }}
                      className={`flex-1 py-1.5 px-3 rounded-lg font-semibold transition flex items-center justify-center gap-1.5 ${
                        mode === "cancelar"
                          ? "bg-bg-light text-danger shadow-sm border border-border-primary/50"
                          : "text-text-secondary hover:text-text-primary"
                      }`}
                    >
                      <AlertOctagon size={13} />
                      Cancelar NFC-e {expired30Min ? "(Prazo Expirado)" : "(Até 30 min)"}
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setMode("devolver");
                        setJustificativa(MOTIVOS_DEVOLUCAO[0]);
                      }}
                      className={`flex-1 py-1.5 px-3 rounded-lg font-semibold transition flex items-center justify-center gap-1.5 ${
                        mode === "devolver"
                          ? "bg-bg-light text-accent shadow-sm border border-border-primary/50"
                          : "text-text-secondary hover:text-text-primary"
                      }`}
                    >
                      <ArrowLeftRight size={13} />
                      Emitir Devolução (NF-e 55) {expired30Min ? "★ Recomendado" : ""}
                    </button>
                  </div>
                )}
              </div>
            )}

            {/* Seção de Autorização Gerencial e Justificativa */}
            {documento && isAuthorized && (
              <div className="space-y-3.5 border-t border-border-primary pt-3.5">
                {/* Seleção do Gerente e Senha */}
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                  <div className="space-y-1">
                    <label htmlFor="select-gerente" className="flex items-center gap-1.5 text-xs font-semibold text-text-primary">
                      <UserCheck size={13} className="text-accent" />
                      Gerente / Supervisor <span className="text-primary">*</span>
                    </label>
                    <select
                      id="select-gerente"
                      value={selectedSupervisorId}
                      onChange={(e) => setSelectedSupervisorId(e.target.value)}
                      disabled={loadingSupervisores || isSubmitting}
                      className="input-field w-full text-xs font-medium"
                    >
                      <option value="">Selecione o gerente...</option>
                      {supervisores.map((s) => (
                        <option key={s.id} value={s.id}>
                          {s.name} ({s.role === "administrador" ? "Administrador" : "Gerente"})
                        </option>
                      ))}
                    </select>
                  </div>

                  <div className="space-y-1">
                    <label htmlFor="senha-gerente" className="flex items-center gap-1.5 text-xs font-semibold text-text-primary">
                      <KeyRound size={13} className="text-accent" />
                      Senha do Gerente <span className="text-primary">*</span>
                    </label>
                    <input
                      ref={passwordInputRef}
                      id="senha-gerente"
                      type="password"
                      autoComplete="off"
                      value={supervisorPassword}
                      onChange={(e) => setSupervisorPassword(e.target.value)}
                      placeholder="Senha do gerente selecionado..."
                      className="input-field w-full text-xs font-medium"
                      disabled={isSubmitting}
                    />
                  </div>
                </div>

                {/* Motivos Rápidos */}
                <div className="space-y-1">
                  <label className="flex items-center gap-1 text-[11px] font-semibold text-text-secondary">
                    <Sparkles size={12} className="text-accent" />
                    Motivos sugeridos para {mode === "devolver" ? "devolução" : "cancelamento"}:
                  </label>
                  <div className="flex flex-wrap gap-1.5">
                    {(mode === "devolver" ? MOTIVOS_DEVOLUCAO : MOTIVOS_CANCELAMENTO).map((motivo, idx) => (
                      <button
                        key={idx}
                        type="button"
                        onClick={() => setJustificativa(motivo)}
                        className="rounded-lg border border-border-secondary bg-bg-secondary px-2 py-0.5 text-[11px] font-medium text-text-secondary hover:border-accent hover:text-accent transition"
                      >
                        {motivo}
                      </button>
                    ))}
                  </div>
                </div>

                {/* Justificativa Livre com Validação SEFAZ */}
                <div className="space-y-1">
                  <div className="flex items-center justify-between">
                    <label htmlFor="pdv-justificativa" className="flex items-center gap-1 text-xs font-semibold text-text-primary">
                      <FileText size={13} />
                      Justificativa da Operação <span className="text-primary">*</span>
                    </label>
                    <span
                      className={`text-[11px] font-mono font-medium ${
                        isJustificativaValid ? "text-success" : "text-amber-500"
                      }`}
                    >
                      {charCount} / 15 caracteres mínimos
                    </span>
                  </div>
                  <textarea
                    id="pdv-justificativa"
                    rows={2}
                    value={justificativa}
                    onChange={(e) => setJustificativa(e.target.value)}
                    placeholder={
                      mode === "devolver"
                        ? "Descreva o motivo da devolução fiscal perante a SEFAZ..."
                        : "Descreva o motivo do cancelamento perante a SEFAZ..."
                    }
                    className="input-field w-full text-xs font-medium resize-none"
                    disabled={isSubmitting}
                  />
                </div>

                {/* Opção de Identificação do Consumidor (apenas no modo Devolução) */}
                {mode === "devolver" && (
                  <div className="rounded-xl border border-border-secondary bg-bg-secondary/40 p-2.5 text-xs space-y-2">
                    <button
                      type="button"
                      onClick={() => setShowClienteInputs(!showClienteInputs)}
                      className="flex w-full items-center justify-between text-left font-semibold text-text-secondary hover:text-text-primary"
                    >
                      <span>Identificação do Consumidor (Opcional)</span>
                      {showClienteInputs ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
                    </button>

                    {showClienteInputs && (
                      <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 pt-1 border-t border-border-primary/50">
                        <div>
                          <label className="block text-[11px] text-text-secondary mb-0.5">CPF / CNPJ do Cliente</label>
                          <input
                            type="text"
                            value={clienteCpf}
                            onChange={(e) => setClienteCpf(e.target.value)}
                            placeholder="000.000.000-00 (opcional)"
                            className="input-field w-full text-xs"
                            disabled={isSubmitting}
                          />
                        </div>
                        <div>
                          <label className="block text-[11px] text-text-secondary mb-0.5">Nome do Cliente</label>
                          <input
                            type="text"
                            value={clienteNome}
                            onChange={(e) => setClienteNome(e.target.value)}
                            placeholder="Nome Completo (opcional)"
                            className="input-field w-full text-xs"
                            disabled={isSubmitting}
                          />
                        </div>
                        <p className="col-span-full text-[10px] text-text-secondary">
                          * Se mantido em branco, a NF-e será emitida como <strong>Entrada Própria</strong> com os dados da loja, amparada pela SEFAZ.
                        </p>
                      </div>
                    )}
                  </div>
                )}
              </div>
            )}

            {/* Rodapé e Botões */}
            <div className="flex flex-col-reverse gap-2 border-t border-border-primary pt-3.5 sm:flex-row sm:justify-end">
              <button
                type="button"
                onClick={onClose}
                disabled={isSubmitting}
                className="btn-secondary text-xs"
              >
                Voltar / Fechar
              </button>
              {documento && isAuthorized && (
                <button
                  type="submit"
                  disabled={!canConfirm}
                  className={`inline-flex items-center justify-center gap-2 text-xs font-semibold ${
                    mode === "devolver" ? "btn-primary" : "btn-danger"
                  }`}
                >
                  {isSubmitting ? (
                    <>
                      <Loader2 size={14} className="animate-spin" />
                      Homologando na SEFAZ...
                    </>
                  ) : mode === "devolver" ? (
                    <>
                      <ArrowLeftRight size={14} />
                      Autorizar e Emitir NF-e Devolução
                    </>
                  ) : (
                    <>
                      <ShieldCheck size={14} />
                      Autorizar e Cancelar NFC-e
                    </>
                  )}
                </button>
              )}
            </div>
          </form>
        )}
      </div>
    </div>
  );
}
