/**
 * Arquivo: src/components/Admin/PdvNfceCancelModal.tsx
 * Objetivo: modal para cancelamento ágil de NFC-e na frente de caixa (PDV).
 *           Permite buscar por código da venda, número da nota ou chave de 44 dígitos com Enter,
 *           solicita a seleção de um gerente em dropdown e senha do supervisor, exige justificativa
 *           mínima de 15 caracteres (com botões de motivos sugeridos) e oferece impressão do comprovante.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import {
  AlertOctagon,
  AlertTriangle,
  CheckCircle2,
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
  type FiscalDocumentDetailDto,
} from "@/services/api/fiscalService";
import { userService, type SupervisorDto } from "@/services/api/userService";
import { formatChaveAcesso, formatNumeroNf } from "@/utils/danfePrint";

type PdvNfceCancelModalProps = {
  isOpen: boolean;
  onClose: () => void;
  onSuccess?: () => void;
};

const MOTIVOS_SUGERIDOS = [
  "Desistência da compra pelo cliente.",
  "Erro na forma de pagamento registrada.",
  "Erro nas quantidades ou itens lançados.",
  "Emissão duplicada de documento fiscal.",
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
    .row { display: flex; justify-content: space-between; margin: 2px 0; }
  </style>
</head>
<body>
  <div class="text-center bold header">HORUS PDV</div>
  <div class="text-center bold">COMPROVANTE DE CANCELAMENTO</div>
  <div class="text-center small">DOCUMENTO FISCAL ELETRÔNICO (NFC-e)</div>
  <div class="divider"></div>

  <div class="row">
    <span>Nº Venda:</span>
    <span class="bold">${doc.saleNumber || "—"}</span>
  </div>
  <div class="row">
    <span>NFC-e Nº:</span>
    <span class="bold">${formatNumeroNf(doc.numeroNf)}</span>
  </div>
  <div class="row">
    <span>Série:</span>
    <span>${doc.serie}</span>
  </div>
  <div class="row">
    <span>Valor Total:</span>
    <span class="bold">${doc.totalAmount ? `R$ ${doc.totalAmount}` : "—"}</span>
  </div>
  <div class="row">
    <span>Emissão:</span>
    <span>${formatDate(doc.dhAutorizacao || doc.criadoEm)}</span>
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

export default function PdvNfceCancelModal({
  isOpen,
  onClose,
  onSuccess,
}: PdvNfceCancelModalProps) {
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

  const [cancelResult, setCancelResult] = useState<CancelarComSupervisorResult | null>(null);

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

  // Carrega supervisores ao abrir o modal
  useEffect(() => {
    if (!isOpen) {
      setCodigoInput("");
      setDocumento(null);
      setSearchError(null);
      setSelectedSupervisorId("");
      setSupervisorPassword("");
      setJustificativa("");
      setCancelResult(null);
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
    try {
      const doc = await fiscalService.buscarPorCodigo(termo);
      if (!doc) {
        setSearchError("Nenhum documento fiscal encontrado com os dados informados.");
        return;
      }
      setDocumento(doc);
      if (doc.status === FISCAL_STATUS.Autorizado) {
        window.setTimeout(() => passwordInputRef.current?.focus(), 150);
      }
    } catch (error) {
      setSearchError(error instanceof Error ? error.message : "Erro ao buscar documento fiscal.");
    } finally {
      setIsSearching(false);
    }
  }, [codigoInput]);

  const handleCancelSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!documento || !canConfirm) return;

    setIsSubmitting(true);
    try {
      const response = await fiscalService.cancelarComSupervisor(documento.id, {
        supervisorId: selectedSupervisorId,
        supervisorPassword,
        justificativa: trimmedJustificativa,
      });

      if (!response.success) {
        Toast.error(response.message || "Falha ao cancelar NFC-e.");
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
      Toast.error(error instanceof Error ? error.message : "Erro ao processar cancelamento.");
    } finally {
      setIsSubmitting(false);
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
        <div className="flex items-center justify-between border-b border-border-primary bg-danger/10 px-5 py-3.5">
          <div className="flex items-center gap-2.5">
            <span className="inline-flex h-9 w-9 items-center justify-center rounded-xl bg-danger/20 text-danger">
              <AlertOctagon size={20} />
            </span>
            <div>
              <h2 className="text-sm font-bold text-text-primary">Cancelar NFC-e no Caixa</h2>
              <p className="text-[11px] text-text-secondary">
                Cancelamento oficial perante a SEFAZ com autorização gerencial
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

        {/* Estado 1: Tela de Sucesso com opção de Impressão */}
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
                <span className="font-semibold text-text-primary">{documento?.saleNumber}</span>
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
                Imprimir Comprovante de Cancelamento
              </button>
            </div>
          </div>
        ) : (
          <form onSubmit={handleCancelSubmit} className="p-5 space-y-4">
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
                    placeholder="Digite o número e tecle Enter (ou bipe o código de barras)..."
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
                      Venda {documento.saleNumber} · NFC-e {formatNumeroNf(documento.numeroNf)} (Série {documento.serie})
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
                      Esta NFC-e está com status <strong>{fiscalStatusLabel(documento.status)}</strong> e não pode ser cancelada. Apenas notas com status <strong>Autorizado</strong> podem ser canceladas perante a SEFAZ.
                    </span>
                  </div>
                )}
              </div>
            )}

            {/* Seção de Autorização Gerencial e Justificativa (só aparece se a nota estiver autorizada) */}
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
                    Motivos sugeridos (clique para preencher):
                  </label>
                  <div className="flex flex-wrap gap-1.5">
                    {MOTIVOS_SUGERIDOS.map((motivo, idx) => (
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
                    <label htmlFor="cancel-pdv-justificativa" className="flex items-center gap-1 text-xs font-semibold text-text-primary">
                      <FileText size={13} />
                      Justificativa SEFAZ <span className="text-primary">*</span>
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
                    id="cancel-pdv-justificativa"
                    rows={2}
                    value={justificativa}
                    onChange={(e) => setJustificativa(e.target.value)}
                    placeholder="Descreva o motivo do cancelamento para homologação perante a SEFAZ..."
                    className="input-field w-full text-xs font-medium resize-none"
                    disabled={isSubmitting}
                  />
                </div>

                {/* Alerta de Prazo SEFAZ */}
                <div className="flex gap-2 rounded-xl border border-amber-500/20 bg-amber-500/10 p-2.5 text-[11px] text-amber-600 dark:text-amber-400">
                  <Clock size={15} className="shrink-0 mt-0.5" />
                  <p>
                    O cancelamento fiscal é <strong>definitivo e irreversível</strong>. Deve ser realizado dentro do prazo regulamentar do estado (geralmente até 30 minutos após a autorização).
                  </p>
                </div>
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
                Voltar / Não Cancelar
              </button>
              {documento && isAuthorized && (
                <button
                  type="submit"
                  disabled={!canConfirm}
                  className="btn-danger inline-flex items-center justify-center gap-2 text-xs font-semibold"
                >
                  {isSubmitting ? (
                    <>
                      <Loader2 size={14} className="animate-spin" />
                      Homologando na SEFAZ...
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
