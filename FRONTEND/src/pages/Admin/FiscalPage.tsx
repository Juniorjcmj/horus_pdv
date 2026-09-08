/**
 * Arquivo: src/pages/Admin/FiscalPage.tsx
 * Objetivo: lista os documentos fiscais (NFC-e) da empresa, com DANFE em tela, reemissão,
 *           cancelamento e inutilização de numeração.
 * Entradas esperadas: não recebe props; opera com estado local e a API de fiscal/empresa.
 */
import { AlertCircle, AlertTriangle, QrCode, RefreshCw, RotateCcw, Search, XCircle } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import DanfePreviewModal from "@/components/Admin/DanfePreviewModal";
import FiscalErrorModal from "@/components/Admin/FiscalErrorModal";
import PageHeader from "@/components/Admin/PageHeader";
import RowActionsMenu from "@/components/Admin/RowActionsMenu";
import TablePagination from "@/components/Pagination/TablePagination";
import { Toast } from "@/hooks/Dialog";
import { usePromptDialog } from "@/hooks/Dialog/usePromptDialog";
import PageLayout from "@/layout/PageLayout";
import { companyService, type CompanyDto } from "@/services/api/companyService";
import {
  FISCAL_STATUS,
  fiscalService,
  fiscalStatusBadgeClass,
  fiscalStatusLabel,
  type FiscalDocumentDetailDto,
  type FiscalDocumentDto,
} from "@/services/api/fiscalService";

function formatDate(value: string) {
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

async function askJustificativa(
  prompt: (title: string, placeholder?: string) => Promise<string | null>,
): Promise<string | null> {
  const value = await prompt(
    "Justificativa (mínimo 15 caracteres)",
    "Explique o motivo para a SEFAZ",
  );
  if (value === null) return null;
  if (value.trim().length < 15) {
    Toast.error("A justificativa deve ter no mínimo 15 caracteres.");
    return null;
  }
  return value.trim();
}

export default function FiscalPage() {
  const { prompt, PromptDialog } = usePromptDialog();
  const [company, setCompany] = useState<CompanyDto | null>(null);
  const [documents, setDocuments] = useState<FiscalDocumentDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState("");
  const [currentPage, setCurrentPage] = useState(1);
  const [itemsPerPage, setItemsPerPage] = useState(10);
  const [busyIds, setBusyIds] = useState<Set<string>>(() => new Set());
  const [danfePreview, setDanfePreview] = useState<FiscalDocumentDetailDto | null>(null);
  const [errorModalDoc, setErrorModalDoc] = useState<FiscalDocumentDto | null>(null);

  const [inutilizarSerie, setInutilizarSerie] = useState("1");
  const [inutilizarInicial, setInutilizarInicial] = useState("");
  const [inutilizarFinal, setInutilizarFinal] = useState("");
  const [inutilizando, setInutilizando] = useState(false);

  const loadDocuments = () => {
    setLoading(true);
    fiscalService
      .list()
      .then(setDocuments)
      .catch(() => Toast.error("Não foi possível carregar os documentos fiscais."))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    loadDocuments();
    companyService.get().then((data) => setCompany(data ?? null)).catch(() => setCompany(null));
  }, []);

  const filteredDocuments = useMemo(() => {
    const normalized = search.trim().toLowerCase();
    if (!normalized) return documents;
    return documents.filter(
      (doc) =>
        doc.saleNumber.toLowerCase().includes(normalized) ||
        (doc.chaveAcesso ?? "").toLowerCase().includes(normalized),
    );
  }, [documents, search]);

  const totalPages = Math.max(1, Math.ceil(filteredDocuments.length / itemsPerPage));
  const safeCurrentPage = Math.min(currentPage, totalPages);
  const paginatedDocuments = useMemo(() => {
    const start = (safeCurrentPage - 1) * itemsPerPage;
    return filteredDocuments.slice(start, start + itemsPerPage);
  }, [filteredDocuments, itemsPerPage, safeCurrentPage]);

  const rejectedCount = useMemo(
    () => documents.filter((doc) => doc.status === FISCAL_STATUS.Rejeitado || doc.motivoStatus).length,
    [documents],
  );

  const setBusy = (id: string, busy: boolean) => {
    setBusyIds((current) => {
      const next = new Set(current);
      if (busy) next.add(id);
      else next.delete(id);
      return next;
    });
  };

  const openDanfe = async (doc: FiscalDocumentDto) => {
    setBusy(doc.id, true);
    try {
      const detail = await fiscalService.getBySaleNumber(doc.saleNumber);
      if (!detail) {
        Toast.error("Não foi possível carregar o documento fiscal.");
        return;
      }
      setDanfePreview(detail);
    } finally {
      setBusy(doc.id, false);
    }
  };

  const reemitir = async (doc: FiscalDocumentDto) => {
    setBusy(doc.id, true);
    try {
      await fiscalService.reemitir(doc.id);
      Toast.success("Documento reenfileirado para nova tentativa de emissão.");
      loadDocuments();
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao reemitir NFC-e.");
    } finally {
      setBusy(doc.id, false);
    }
  };

  const cancelar = async (doc: FiscalDocumentDto) => {
    const justificativa = await askJustificativa(prompt);
    if (!justificativa) return;

    setBusy(doc.id, true);
    try {
      await fiscalService.cancelar(doc.id, justificativa);
      Toast.success("NFC-e cancelada com sucesso.");
      loadDocuments();
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao cancelar NFC-e.");
    } finally {
      setBusy(doc.id, false);
    }
  };

  const inutilizar = async () => {
    const inicial = Number(inutilizarInicial);
    const final = Number(inutilizarFinal);
    if (!Number.isInteger(inicial) || inicial <= 0 || !Number.isInteger(final) || final < inicial) {
      Toast.error("Informe uma faixa de numeração válida.");
      return;
    }

    const justificativa = await askJustificativa(prompt);
    if (!justificativa) return;

    setInutilizando(true);
    try {
      await fiscalService.inutilizar({
        serie: Number(inutilizarSerie) || 1,
        numeroInicial: inicial,
        numeroFinal: final,
        justificativa,
      });
      Toast.success("Faixa de numeração inutilizada com sucesso.");
      setInutilizarInicial("");
      setInutilizarFinal("");
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao inutilizar numeração.");
    } finally {
      setInutilizando(false);
    }
  };

  return (
    <PageLayout className="space-y-4 py-4 md:space-y-6 md:py-6 lg:py-8">
      <PageHeader
        title="Fiscal NFC-e"
        description="Documentos fiscais emitidos em segundo plano após cada venda — status, DANFE em tela, reemissão e cancelamento."
      />

      {!company?.certificadoHasValue ? (
        <section className="card border border-primary/30 bg-primary/5 p-4 text-sm text-primary">
          Nenhum certificado digital configurado. Cadastre o certificado A1 e o CSC em{" "}
          <span className="font-semibold">Minha Empresa</span> antes de vender — sem isso a
          NFC-e fica na fila sem ser transmitida.
        </section>
      ) : null}

      {rejectedCount > 0 ? (
        <section className="card flex items-center gap-3 border border-primary/30 bg-primary/10 p-4 text-sm text-primary">
          <AlertTriangle size={20} className="shrink-0 text-primary" />
          <div>
            <p className="font-semibold text-primary">
              Atenção: Existem {rejectedCount} documento(s) fiscal(is) com rejeição ou erro.
            </p>
            <p className="text-xs text-text-secondary mt-0.5">
              Clique no botão &quot;Rejeitado&quot; ou no menu de ações da linha para visualizar o motivo detalhado retornado pela SEFAZ e reenviar.
            </p>
          </div>
        </section>
      ) : null}

      <section className="card p-4 md:p-5">
        <label className="relative mx-auto block w-full max-w-xl">
          <Search
            size={16}
            className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-text-tertiary"
          />
          <input
            value={search}
            onChange={(event) => {
              setSearch(event.target.value);
              setCurrentPage(1);
            }}
            className="input-field w-full pl-9"
            placeholder="Pesquise pelo número da venda ou chave de acesso"
          />
        </label>
      </section>

      <section className="card overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[900px] text-sm">
            <thead className="bg-bg-primary text-left text-text-secondary">
              <tr>
                <th className="px-3 py-3">Venda</th>
                <th className="px-3 py-3">Série/Número</th>
                <th className="px-3 py-3">Chave de acesso</th>
                <th className="px-3 py-3">Status</th>
                <th className="px-3 py-3">Emitido em</th>
                <th className="px-3 py-3 text-center">Ações</th>
              </tr>
            </thead>
            <tbody>
              {!loading && paginatedDocuments.length === 0 ? (
                <tr>
                  <td colSpan={6} className="px-3 py-6 text-center text-text-secondary">
                    Nenhum documento fiscal encontrado.
                  </td>
                </tr>
              ) : null}
              {paginatedDocuments.map((doc) => (
                <tr key={doc.id} className="border-t border-border-primary">
                  <td className="px-3 py-3 font-semibold text-text-primary">{doc.saleNumber}</td>
                  <td className="px-3 py-3 tabular-nums">
                    {doc.serie}/{doc.numeroNf}
                  </td>
                  <td className="px-3 py-3">
                    <span className="block max-w-[220px] truncate font-mono text-xs" title={doc.chaveAcesso ?? ""}>
                      {doc.chaveAcesso ?? "—"}
                    </span>
                  </td>
                  <td className="px-3 py-3">
                    {doc.status === FISCAL_STATUS.Rejeitado || doc.motivoStatus ? (
                      <button
                        type="button"
                        onClick={() => setErrorModalDoc(doc)}
                        className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold cursor-pointer transition-all hover:scale-105 ${fiscalStatusBadgeClass(doc.status)}`}
                        title="Clique para ver detalhes do erro da SEFAZ"
                      >
                        <AlertCircle size={12} className="shrink-0" />
                        {fiscalStatusLabel(doc.status)}
                      </button>
                    ) : (
                      <span
                        className={`inline-flex rounded-full px-2.5 py-1 text-xs font-semibold ${fiscalStatusBadgeClass(doc.status)}`}
                      >
                        {fiscalStatusLabel(doc.status)}
                      </span>
                    )}
                  </td>
                  <td className="px-3 py-3 whitespace-nowrap">{formatDate(doc.criadoEm)}</td>
                  <td className="px-3 py-3 text-center">
                    <RowActionsMenu
                      items={[
                        {
                          key: "danfe",
                          label: "Ver DANFE",
                          icon: <QrCode size={13} />,
                          disabled: doc.status !== FISCAL_STATUS.Autorizado,
                          loading: busyIds.has(doc.id),
                          loadingLabel: "Carregando...",
                          onClick: () => openDanfe(doc),
                        },
                        ...(doc.status === FISCAL_STATUS.Rejeitado || doc.motivoStatus
                          ? [
                              {
                                key: "error",
                                label: "Ver motivo do erro",
                                icon: <AlertTriangle size={13} />,
                                onClick: () => setErrorModalDoc(doc),
                              },
                            ]
                          : []),
                        ...(doc.status === FISCAL_STATUS.Rejeitado
                          ? [
                              {
                                key: "reemitir",
                                label: "Reemitir",
                                icon: <RefreshCw size={13} />,
                                loading: busyIds.has(doc.id),
                                loadingLabel: "Reenfileirando...",
                                onClick: () => reemitir(doc),
                              },
                            ]
                          : []),
                        ...(doc.status === FISCAL_STATUS.Autorizado
                          ? [
                              {
                                key: "cancelar",
                                label: "Cancelar NFC-e",
                                icon: <XCircle size={13} />,
                                loading: busyIds.has(doc.id),
                                loadingLabel: "Cancelando...",
                                danger: true,
                                onClick: () => cancelar(doc),
                              },
                            ]
                          : []),
                      ]}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="px-4 py-4">
          <TablePagination
            totalItems={filteredDocuments.length}
            currentPage={safeCurrentPage}
            itemsPerPage={itemsPerPage}
            onPageChange={setCurrentPage}
            onItemsPerPageChange={(value) => {
              setItemsPerPage(value);
              setCurrentPage(1);
            }}
          />
        </div>
      </section>

      <section className="card space-y-3 p-4 md:p-5">
        <div>
          <h2 className="text-base font-semibold text-text-primary">Inutilizar numeração</h2>
          <p className="mt-1 text-sm text-text-secondary">
            Use quando uma faixa de números fiscais nunca chegou a ser usada (ex.: falha antes de
            transmitir) e precisa ser formalmente inutilizada perante a SEFAZ.
          </p>
        </div>
        <div className="grid gap-3 md:grid-cols-4">
          <label className="block">
            <span className="mb-1.5 block text-sm text-text-secondary">Série</span>
            <input
              className="input-field w-full"
              inputMode="numeric"
              value={inutilizarSerie}
              onChange={(event) => setInutilizarSerie(event.target.value.replace(/\D/g, "").slice(0, 3))}
            />
          </label>
          <label className="block">
            <span className="mb-1.5 block text-sm text-text-secondary">Número inicial</span>
            <input
              className="input-field w-full"
              inputMode="numeric"
              value={inutilizarInicial}
              onChange={(event) => setInutilizarInicial(event.target.value.replace(/\D/g, "").slice(0, 9))}
            />
          </label>
          <label className="block">
            <span className="mb-1.5 block text-sm text-text-secondary">Número final</span>
            <input
              className="input-field w-full"
              inputMode="numeric"
              value={inutilizarFinal}
              onChange={(event) => setInutilizarFinal(event.target.value.replace(/\D/g, "").slice(0, 9))}
            />
          </label>
          <div className="flex items-end">
            <button
              type="button"
              onClick={inutilizar}
              disabled={inutilizando}
              className="btn-cancel inline-flex w-full items-center justify-center gap-2"
            >
              <RotateCcw size={15} />
              {inutilizando ? "Inutilizando..." : "Inutilizar faixa"}
            </button>
          </div>
        </div>
      </section>

      {danfePreview ? (
        <DanfePreviewModal
          detail={danfePreview}
          companyName={company?.fantasyName || company?.corporateName || "Hórus PDV"}
          onClose={() => setDanfePreview(null)}
        />
      ) : null}

      {errorModalDoc ? (
        <FiscalErrorModal
          document={errorModalDoc}
          onClose={() => setErrorModalDoc(null)}
          onReemitir={async (doc) => {
            await reemitir(doc);
            setErrorModalDoc(null);
          }}
          isReemitindo={busyIds.has(errorModalDoc.id)}
        />
      ) : null}

      {PromptDialog}
    </PageLayout>
  );
}
