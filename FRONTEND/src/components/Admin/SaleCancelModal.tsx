/**
 * Arquivo: src/components/Admin/SaleCancelModal.tsx
 * Objetivo: modal para cancelamento de vendas com autenticação por senha do supervisor/gerente,
 *           reversão automática de estoque, estorno financeiro/fiado e auditoria.
 * Entradas esperadas: recebe número da venda, dados de resumo, callback de fechamento e sucesso.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import {
  AlertOctagon,
  CheckCircle2,
  KeyRound,
  Loader2,
  Printer,
  ShieldAlert,
  Sparkles,
  UserCheck,
  X,
} from "lucide-react";
import { Toast } from "@/hooks/Dialog";
import { salesHistoryService } from "@/services/api/salesHistoryService";
import { userService, type SupervisorDto } from "@/services/api/userService";

export type SaleCancelModalProps = {
  isOpen: boolean;
  saleNumber: string;
  customerName?: string;
  totalAmount?: string;
  paymentType?: string;
  saleDate?: string;
  itemsCount?: number;
  onClose: () => void;
  onSuccess?: () => void;
};

const MOTIVOS_CANCELAMENTO = [
  "Desistência da compra pelo cliente.",
  "Erro na forma de pagamento registrada.",
  "Erro nas quantidades ou itens lançados.",
  "Lançamento de venda duplicado no sistema.",
  "Cliente sem saldo / desistiu no caixa.",
  "Estorno solicitado pelo consumidor.",
];

export function buildSaleCancellationReceiptHtml(
  saleNumber: string,
  totalAmount: string,
  customerName: string,
  supervisorNome: string,
  operadorNome: string,
  justificativa: string,
  canceladoEm: string,
  itensEstornados: number,
): string {
  return `<!doctype html>
<html lang="pt-BR">
<head>
  <meta charset="utf-8" />
  <title>Comprovante de Cancelamento - Venda #${saleNumber}</title>
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
    .bold { font-weight: 800; }
    .divider { border-top: 1px dashed #475569; margin: 8px 0; }
    .line { display: flex; justify-content: space-between; gap: 6px; }
    .title { font-size: 13px; font-weight: 800; margin: 4px 0 2px; }
    .subtitle { font-size: 10px; color: #475569; margin-bottom: 4px; }
    .warn-box {
      border: 2px solid #000;
      padding: 6px;
      margin: 8px 0;
      text-align: center;
      font-weight: 800;
      font-size: 11px;
      background: #fef2f2;
    }
    .signatures { margin-top: 28px; text-align: center; font-size: 10px; }
    .sig-block { margin-top: 22px; }
    .sig-line { width: 85%; margin: 0 auto 3px auto; border-top: 1px solid #000; }
    .sub { font-size: 9px; color: #475569; }
    .footer { margin-top: 14px; text-align: center; font-size: 9px; color: #64748b; }
  </style>
</head>
<body>
  <main class="receipt">
    <section class="center">
      <div class="bold" style="font-size: 14px; text-transform: uppercase;">HORUS PDV</div>
      <div class="title">COMPROVANTE DE CANCELAMENTO</div>
      <div class="subtitle">ESTORNO OPERACIONAL E DE ESTOQUE</div>
    </section>

    <div class="divider"></div>

    <div class="warn-box">
      *** VENDA CANCELADA ***<br />
      MERCADORIAS REVERTIDAS AO ESTOQUE
    </div>

    <section>
      <div class="line"><span>Venda Cancelada:</span><span class="bold">#${saleNumber}</span></div>
      <div class="line"><span>Data/Hora Cancelamento:</span><span>${canceladoEm}</span></div>
      <div class="line"><span>Valor Estornado:</span><span class="bold">R$ ${totalAmount}</span></div>
      <div class="line"><span>Cliente:</span><span>${customerName || "Consumidor Final"}</span></div>
      <div class="line"><span>Itens Estornados:</span><span>${itensEstornados} item(ns)</span></div>
      <div class="line"><span>Operador do Caixa:</span><span>${operadorNome || "Operador"}</span></div>
      <div class="line"><span>Supervisor Autorizador:</span><span class="bold">${supervisorNome}</span></div>
    </section>

    <div class="divider"></div>

    <section>
      <div class="bold" style="font-size: 11px;">MOTIVO DO CANCELAMENTO:</div>
      <div style="font-size: 11px; margin-top: 2px; word-break: break-word; background: #f8fafc; padding: 4px; border-left: 2px solid #000;">
        ${justificativa}
      </div>
    </section>

    <div class="divider"></div>

    <section class="signatures">
      <div class="sig-block">
        <div class="sig-line"></div>
        <div class="bold">${supervisorNome}</div>
        <div class="sub">Supervisor / Gerente Autorizador</div>
      </div>

      <div class="sig-block">
        <div class="sig-line"></div>
        <div class="bold">${operadorNome || "Operador"}</div>
        <div class="sub">Operador Responsável pelo Caixa</div>
      </div>
    </section>

    <section class="footer">
      <div>Emissão do Comprovante: ${new Date().toLocaleString("pt-BR")}</div>
      <div style="margin-top: 2px;">HORUS PDV - Controle e Auditoria</div>
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

export default function SaleCancelModal({
  isOpen,
  saleNumber,
  customerName = "Consumidor Final",
  totalAmount = "0,00",
  paymentType = "-",
  saleDate = "-",
  itemsCount = 1,
  onClose,
  onSuccess,
}: SaleCancelModalProps) {
  const [supervisores, setSupervisores] = useState<SupervisorDto[]>([]);
  const [loadingSupervisores, setLoadingSupervisores] = useState(false);
  const [selectedSupervisorId, setSelectedSupervisorId] = useState("");
  const [supervisorPassword, setSupervisorPassword] = useState("");
  const [justificativa, setJustificativa] = useState(MOTIVOS_CANCELAMENTO[0]);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [cancelResult, setCancelResult] = useState<{
    saleNumber: string;
    canceladoEm: string;
    supervisorNome: string;
    itensEstornados: number;
  } | null>(null);

  const passwordInputRef = useRef<HTMLInputElement>(null);

  const loadSupervisores = useCallback(async () => {
    setLoadingSupervisores(true);
    try {
      const list = await userService.listSupervisores();
      setSupervisores(list);
      if (list.length === 1) {
        setSelectedSupervisorId(list[0].id);
      }
    } catch {
      Toast.error("Não foi possível carregar a lista de supervisores.");
    } finally {
      setLoadingSupervisores(false);
    }
  }, []);

  useEffect(() => {
    if (isOpen) {
      void loadSupervisores();
      setCancelResult(null);
      setSupervisorPassword("");
      setJustificativa(MOTIVOS_CANCELAMENTO[0]);
    }
  }, [isOpen, loadSupervisores]);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!selectedSupervisorId) {
      Toast.error("Selecione o supervisor para autorizar o cancelamento.");
      return;
    }

    if (!supervisorPassword.trim()) {
      Toast.error("Digite a senha do supervisor.");
      passwordInputRef.current?.focus();
      return;
    }

    if (!justificativa.trim() || justificativa.trim().length < 5) {
      Toast.error("Informe uma justificativa de no mínimo 5 caracteres.");
      return;
    }

    setIsSubmitting(true);
    try {
      const response = await salesHistoryService.cancel(saleNumber, {
        supervisorId: selectedSupervisorId,
        supervisorPassword: supervisorPassword.trim(),
        justificativa: justificativa.trim(),
      });

      if (!response.success) {
        Toast.error(response.message || "Falha ao cancelar venda.");
        return;
      }

      const supervisorObj = supervisores.find((s) => s.id === selectedSupervisorId);
      const resData = response.data || {
        saleNumber,
        canceladoEm: new Date().toLocaleString("pt-BR"),
        supervisorNome: supervisorObj?.name || "Supervisor",
        itensEstornados: itemsCount,
      };

      setCancelResult(resData);
      Toast.success(`Venda #${saleNumber} cancelada com sucesso! Produtos devolvidos ao estoque.`);
      onSuccess?.();
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao cancelar venda.");
    } finally {
      setIsSubmitting(false);
    }
  };

  const handlePrintReceipt = () => {
    if (!cancelResult) return;
    const html = buildSaleCancellationReceiptHtml(
      cancelResult.saleNumber,
      totalAmount,
      customerName,
      cancelResult.supervisorNome,
      "Operador",
      justificativa.trim(),
      cancelResult.canceladoEm,
      cancelResult.itensEstornados,
    );
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

  return (
    <div className="fixed inset-0 z-layer-dialog flex items-end bg-black/60 px-3 backdrop-blur-sm md:items-center md:justify-center">
      <div className="flex max-h-[92vh] w-full max-w-lg flex-col overflow-hidden rounded-t-2xl border border-border-primary bg-bg-light shadow-2xl md:rounded-2xl">
        {/* CABEÇALHO DO MODAL */}
        <div className="flex items-center justify-between border-b border-border-primary px-4 py-3 bg-bg-secondary/40">
          <div className="flex items-center gap-2">
            <span className="inline-flex h-9 w-9 items-center justify-center rounded-xl bg-danger/10 text-danger">
              <AlertOctagon size={18} />
            </span>
            <div>
              <h2 className="text-base font-semibold text-text-primary">
                Cancelamento de Venda
              </h2>
              <p className="text-xs text-text-secondary">
                Exige autorização gerencial e estorna estoque automaticamente
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="inline-flex h-9 w-9 items-center justify-center rounded-lg border border-border-primary text-text-secondary hover:bg-hover-light"
            aria-label="Fechar modal"
          >
            <X size={16} />
          </button>
        </div>

        {/* CONTEÚDO */}
        <div className="flex-1 overflow-y-auto p-5 space-y-4">
          {cancelResult ? (
            /* TELA DE SUCESSO DO CANCELAMENTO */
            <div className="py-4 text-center space-y-4">
              <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-emerald-500/10 text-emerald-500">
                <CheckCircle2 size={32} />
              </div>
              <div>
                <h3 className="text-lg font-bold text-text-primary">
                  Venda #{cancelResult.saleNumber} Cancelada
                </h3>
                <p className="text-xs text-text-secondary mt-1">
                  Autorizado por <strong>{cancelResult.supervisorNome}</strong> em {cancelResult.canceladoEm}.
                </p>
              </div>

              <div className="rounded-xl border border-emerald-500/30 bg-emerald-500/10 p-3.5 text-xs text-left space-y-1.5">
                <div className="flex justify-between">
                  <span className="text-text-secondary">Valor Estornado:</span>
                  <span className="font-bold text-text-primary">R$ {totalAmount}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-text-secondary">Itens Devolvidos ao Estoque:</span>
                  <span className="font-bold text-text-primary">{cancelResult.itensEstornados} item(ns)</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-text-secondary">Motivo:</span>
                  <span className="font-medium text-text-primary truncate max-w-[240px]">{justificativa}</span>
                </div>
              </div>

              <div className="pt-2 flex flex-col sm:flex-row gap-2 justify-center">
                <button
                  type="button"
                  onClick={handlePrintReceipt}
                  className="btn-primary inline-flex items-center justify-center gap-2 text-xs py-2 px-4 font-semibold"
                >
                  <Printer size={15} />
                  Imprimir Comprovante de Cancelamento
                </button>
                <button
                  type="button"
                  onClick={onClose}
                  className="btn-secondary text-xs py-2 px-4"
                >
                  Fechar
                </button>
              </div>
            </div>
          ) : (
            /* FORMULÁRIO DE CANCELAMENTO */
            <form onSubmit={handleSubmit} className="space-y-4">
              {/* CARD RESUMO DA VENDA */}
              <div className="rounded-xl border border-border-primary bg-bg-secondary p-3.5 text-xs space-y-2">
                <div className="flex items-center justify-between border-b border-border-primary/60 pb-2">
                  <span className="font-bold text-text-primary text-sm">
                    Venda #{saleNumber}
                  </span>
                  <span className="rounded-md bg-accent/15 px-2 py-0.5 text-[11px] font-semibold text-accent uppercase">
                    {paymentType}
                  </span>
                </div>
                <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
                  <div>
                    <span className="block text-[11px] text-text-secondary">Valor Total</span>
                    <span className="block font-bold text-text-primary text-base">
                      R$ {totalAmount}
                    </span>
                  </div>
                  <div>
                    <span className="block text-[11px] text-text-secondary">Cliente</span>
                    <span className="block font-medium text-text-primary truncate">
                      {customerName}
                    </span>
                  </div>
                  <div>
                    <span className="block text-[11px] text-text-secondary">Data da Venda</span>
                    <span className="block font-medium text-text-primary truncate">
                      {saleDate}
                    </span>
                  </div>
                </div>
              </div>

              {/* AVISO DE IMPACTO */}
              <div className="flex items-start gap-2.5 rounded-xl border border-amber-500/30 bg-amber-500/10 p-3 text-xs text-amber-700 dark:text-amber-300">
                <ShieldAlert size={16} className="shrink-0 mt-0.5 text-amber-500" />
                <div>
                  <p className="font-semibold">Ação irreversível de estorno:</p>
                  <p className="text-[11px] text-text-secondary mt-0.5">
                    As mercadorias retornarão automaticamente ao saldo de estoque e o valor de R$ {totalAmount} será descontado do fechamento do caixa e faturamento.
                  </p>
                </div>
              </div>

              {/* SELEÇÃO DO SUPERVISOR E SENHA */}
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 pt-1">
                <div className="space-y-1">
                  <label htmlFor="cancel-supervisor" className="flex items-center gap-1.5 text-xs font-semibold text-text-primary">
                    <UserCheck size={13} className="text-accent" />
                    Supervisor / Gerente <span className="text-danger">*</span>
                  </label>
                  <select
                    id="cancel-supervisor"
                    value={selectedSupervisorId}
                    onChange={(e) => setSelectedSupervisorId(e.target.value)}
                    disabled={loadingSupervisores || isSubmitting}
                    className="input-field w-full text-xs font-medium"
                    required
                  >
                    <option value="">Selecione o supervisor...</option>
                    {supervisores.map((s) => (
                      <option key={s.id} value={s.id}>
                        {s.name} ({s.role === "administrador" ? "Administrador" : "Gerente"})
                      </option>
                    ))}
                  </select>
                </div>

                <div className="space-y-1">
                  <label htmlFor="cancel-password" className="flex items-center gap-1.5 text-xs font-semibold text-text-primary">
                    <KeyRound size={13} className="text-accent" />
                    Senha do Supervisor <span className="text-danger">*</span>
                  </label>
                  <input
                    ref={passwordInputRef}
                    id="cancel-password"
                    type="password"
                    autoComplete="off"
                    value={supervisorPassword}
                    onChange={(e) => setSupervisorPassword(e.target.value)}
                    placeholder="Digite a senha..."
                    className="input-field w-full text-xs font-medium"
                    disabled={isSubmitting}
                    required
                  />
                </div>
              </div>

              {/* MOTIVOS SUGERIDOS */}
              <div className="space-y-1.5 pt-1">
                <label className="flex items-center gap-1 text-[11px] font-semibold text-text-secondary">
                  <Sparkles size={12} className="text-accent" />
                  Motivos frequentes:
                </label>
                <div className="flex flex-wrap gap-1.5">
                  {MOTIVOS_CANCELAMENTO.map((motivo, idx) => (
                    <button
                      key={idx}
                      type="button"
                      onClick={() => setJustificativa(motivo)}
                      className="rounded-lg border border-border-secondary bg-bg-secondary px-2.5 py-1 text-[11px] font-medium text-text-secondary hover:border-accent hover:text-accent transition"
                    >
                      {motivo}
                    </button>
                  ))}
                </div>
              </div>

              {/* JUSTIFICATIVA OBRIGATÓRIA */}
              <div className="space-y-1">
                <label htmlFor="cancel-justificativa" className="block text-xs font-semibold text-text-primary">
                  Justificativa do cancelamento <span className="text-danger">*</span>
                </label>
                <textarea
                  id="cancel-justificativa"
                  rows={2}
                  value={justificativa}
                  onChange={(e) => setJustificativa(e.target.value)}
                  placeholder="Descreva o motivo do cancelamento da venda..."
                  className="input-field w-full text-xs"
                  disabled={isSubmitting}
                  required
                />
              </div>

              {/* BOTÕES DE AÇÃO */}
              <div className="flex justify-end gap-2 pt-2 border-t border-border-primary">
                <button
                  type="button"
                  onClick={onClose}
                  disabled={isSubmitting}
                  className="btn-secondary text-xs"
                >
                  Voltar
                </button>
                <button
                  type="submit"
                  disabled={isSubmitting || !selectedSupervisorId || !supervisorPassword.trim()}
                  className="btn-cancel inline-flex items-center justify-center gap-2 text-xs font-semibold py-2 px-4"
                >
                  {isSubmitting ? (
                    <>
                      <Loader2 size={14} className="animate-spin" />
                      <span>Cancelando venda...</span>
                    </>
                  ) : (
                    <>
                      <AlertOctagon size={14} />
                      <span>Confirmar Cancelamento</span>
                    </>
                  )}
                </button>
              </div>
            </form>
          )}
        </div>
      </div>
    </div>
  );
}
