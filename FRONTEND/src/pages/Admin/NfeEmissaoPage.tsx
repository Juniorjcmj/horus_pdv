/**
 * Arquivo: src/pages/Admin/NfeEmissaoPage.tsx
 * Objetivo: permite emitir NF-e modelo 55 (nota para empresas) selecionando uma venda
 * e preenchendo os dados do destinatário (CNPJ, IE, endereço).
 */
import {
  Building2,
  FileText,
  Loader2,
  MapPin,
  RefreshCw,
  Search,
  Send,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import PageHeader from "@/components/Admin/PageHeader";
import TablePagination from "@/components/Pagination/TablePagination";
import { Toast } from "@/hooks/Dialog";
import PageLayout from "@/layout/PageLayout";
import {
  fiscalStatusBadgeClass,
  fiscalStatusLabel,
  nfeService,
  type FiscalDocumentDto,
  type NfeDestinatario,
} from "@/services/api/fiscalService";
import { salesHistoryService } from "@/services/api/salesHistoryService";
import { lookupAddressByCep } from "@/utils/cepLookup";
import { onlyDigits } from "@/utils/inputMasks";

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

type SaleRow = {
  saleNumber: string;
  totalAmount: string;
  customerName: string;
  customerCpf: string;
  paymentType: string;
  saleDate: string;
};

type NfeTab = "emitir" | "emitidas";

const EMPTY_DEST: NfeDestinatario = {
  cpfCnpj: "",
  nome: "",
  indIeDest: 1,
  inscricaoEstadual: "",
  logradouro: "",
  numero: "",
  complemento: "",
  bairro: "",
  codigoMunicipioIbge: "",
  nomeMunicipio: "",
  uf: "",
  cep: "",
  fone: "",
  email: "",
};

function formatDate(value: string) {
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

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function NfeEmissaoPage() {
  const [activeTab, setActiveTab] = useState<NfeTab>("emitir");

  // Sales list
  const [sales, setSales] = useState<SaleRow[]>([]);
  const [salesLoading, setSalesLoading] = useState(true);
  const [salesSearch, setSalesSearch] = useState("");
  const [salesPage, setSalesPage] = useState(1);
  const [salesPerPage, setSalesPerPage] = useState(10);

  // Emitted NF-e list
  const [nfeList, setNfeList] = useState<FiscalDocumentDto[]>([]);
  const [nfeLoading, setNfeLoading] = useState(false);

  // Emission form
  const [selectedSale, setSelectedSale] = useState<SaleRow | null>(null);
  const [dest, setDest] = useState<NfeDestinatario>({ ...EMPTY_DEST });
  const [natOp, setNatOp] = useState("VENDA DE MERCADORIA");
  const [modFrete, setModFrete] = useState(9);
  const [emitting, setEmitting] = useState(false);
  const [cepLoading, setCepLoading] = useState(false);

  // ---------------------------------------------------------------------------
  // Data loading
  // ---------------------------------------------------------------------------

  const loadSales = useCallback(async () => {
    setSalesLoading(true);
    try {
      const data = await salesHistoryService.list();
      setSales(
        (data ?? []).map((s) => ({
          saleNumber: s.saleNumber ?? "",
          totalAmount: s.totalAmount ?? "0",
          customerName: s.customerName ?? "",
          customerCpf: s.customerCpf ?? "",
          paymentType: s.paymentType ?? "",
          saleDate: s.saleDate ?? "",
        })),
      );
    } catch {
      Toast.error("Erro ao carregar vendas.");
    } finally {
      setSalesLoading(false);
    }
  }, []);

  const loadNfeList = useCallback(async () => {
    setNfeLoading(true);
    try {
      const data = await nfeService.list();
      setNfeList(data);
    } catch {
      Toast.error("Erro ao carregar NF-e emitidas.");
    } finally {
      setNfeLoading(false);
    }
  }, []);

  useEffect(() => {
    loadSales();
  }, [loadSales]);

  useEffect(() => {
    if (activeTab === "emitidas") loadNfeList();
  }, [activeTab, loadNfeList]);

  // ---------------------------------------------------------------------------
  // Filtered sales
  // ---------------------------------------------------------------------------

  const filteredSales = useMemo(() => {
    if (!salesSearch.trim()) return sales;
    const q = salesSearch.trim().toLowerCase();
    return sales.filter(
      (s) =>
        s.saleNumber.toLowerCase().includes(q) ||
        s.customerName.toLowerCase().includes(q) ||
        s.customerCpf.includes(q),
    );
  }, [sales, salesSearch]);

  const totalSalesPages = Math.max(1, Math.ceil(filteredSales.length / salesPerPage));
  const safeSalesPage = Math.min(salesPage, totalSalesPages);
  const paginatedSales = filteredSales.slice(
    (safeSalesPage - 1) * salesPerPage,
    safeSalesPage * salesPerPage,
  );

  useEffect(() => {
    setSalesPage(1);
  }, [salesSearch]);

  // ---------------------------------------------------------------------------
  // CEP lookup
  // ---------------------------------------------------------------------------

  async function handleCepBlur() {
    const digits = onlyDigits(dest.cep);
    if (digits.length !== 8) return;
    setCepLoading(true);
    try {
      const addr = await lookupAddressByCep(digits);
      if (addr) {
        setDest((prev) => ({
          ...prev,
          logradouro: addr.logradouro || prev.logradouro,
          bairro: addr.bairro || prev.bairro,
          nomeMunicipio: addr.localidade || prev.nomeMunicipio,
          uf: addr.uf || prev.uf,
          codigoMunicipioIbge: addr.ibge || prev.codigoMunicipioIbge,
        }));
      }
    } catch {
      /* silent */
    } finally {
      setCepLoading(false);
    }
  }

  // ---------------------------------------------------------------------------
  // Emit NF-e
  // ---------------------------------------------------------------------------

  async function handleEmitir() {
    if (!selectedSale) {
      Toast.error("Selecione uma venda.");
      return;
    }

    const cnpjDigits = onlyDigits(dest.cpfCnpj);
    if (cnpjDigits.length < 11) {
      Toast.error("CNPJ/CPF do destinatário é obrigatório.");
      return;
    }
    if (!dest.nome.trim()) {
      Toast.error("Nome/Razão Social do destinatário é obrigatório.");
      return;
    }
    if (!dest.logradouro.trim() || !dest.bairro.trim() || !dest.nomeMunicipio.trim() || !dest.uf.trim()) {
      Toast.error("Endereço completo do destinatário é obrigatório para NF-e.");
      return;
    }

    setEmitting(true);
    try {
      const response = await nfeService.emitir({
        saleNumber: selectedSale.saleNumber,
        destinatario: {
          ...dest,
          cpfCnpj: cnpjDigits,
          cep: onlyDigits(dest.cep),
        },
        naturezaOperacao: natOp,
        modalidadeFrete: modFrete,
      });

      if (response.success) {
        Toast.success(response.message || "NF-e enfileirada para emissão!");
        setSelectedSale(null);
        setDest({ ...EMPTY_DEST });
        setActiveTab("emitidas");
        loadNfeList();
      } else {
        Toast.error(response.message || "Erro ao emitir NF-e.");
      }
    } catch {
      Toast.error("Erro ao emitir NF-e.");
    } finally {
      setEmitting(false);
    }
  }

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

  return (
    <PageLayout className="space-y-4 py-4 md:space-y-6 md:py-6">
      <PageHeader
        title="NF-e — Nota Fiscal Eletrônica"
        description="Emita NF-e modelo 55 para vendas destinadas a empresas (CNPJ)."
      />

      {/* Tabs */}
      <div className="flex gap-1 rounded-lg border border-border-primary bg-bg-light p-1">
        <button
          type="button"
          onClick={() => setActiveTab("emitir")}
          className={`flex-1 rounded-md px-4 py-2 text-sm font-semibold transition ${
            activeTab === "emitir"
              ? "bg-white text-secondary shadow-sm"
              : "text-text-secondary hover:text-text-primary"
          }`}
        >
          <Send size={14} className="mr-1.5 inline" />
          Emitir NF-e
        </button>
        <button
          type="button"
          onClick={() => setActiveTab("emitidas")}
          className={`flex-1 rounded-md px-4 py-2 text-sm font-semibold transition ${
            activeTab === "emitidas"
              ? "bg-white text-secondary shadow-sm"
              : "text-text-secondary hover:text-text-primary"
          }`}
        >
          <FileText size={14} className="mr-1.5 inline" />
          NF-e Emitidas
        </button>
      </div>

      {activeTab === "emitir" && (
        <>
          {/* Step 1: Select sale */}
          {!selectedSale ? (
            <section className="card overflow-hidden">
              <div className="border-b border-border-primary bg-bg-light px-4 py-3">
                <h2 className="text-sm font-bold text-text-primary">
                  1. Selecione a venda para emitir NF-e
                </h2>
              </div>

              <div className="p-4">
                <div className="relative mb-4">
                  <Search
                    size={16}
                    className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-text-tertiary"
                  />
                  <input
                    type="text"
                    placeholder="Buscar por número, cliente ou CPF/CNPJ..."
                    value={salesSearch}
                    onChange={(e) => setSalesSearch(e.target.value)}
                    className="input-field w-full pl-9 text-sm"
                  />
                </div>

                {salesLoading ? (
                  <div className="flex items-center justify-center gap-2 py-12 text-text-secondary">
                    <Loader2 size={18} className="animate-spin" />
                    <span>Carregando vendas...</span>
                  </div>
                ) : filteredSales.length === 0 ? (
                  <p className="py-8 text-center text-sm text-text-secondary">
                    Nenhuma venda encontrada.
                  </p>
                ) : (
                  <>
                    <div className="overflow-x-auto">
                      <table className="w-full text-sm">
                        <thead>
                          <tr className="border-b border-border-primary text-left text-xs font-semibold uppercase tracking-wider text-text-secondary">
                            <th className="px-3 py-2">Venda</th>
                            <th className="px-3 py-2">Cliente</th>
                            <th className="px-3 py-2 text-right">Total</th>
                            <th className="px-3 py-2">Pagamento</th>
                            <th className="px-3 py-2">Data</th>
                            <th className="px-3 py-2 text-center">Ação</th>
                          </tr>
                        </thead>
                        <tbody className="divide-y divide-border-primary">
                          {paginatedSales.map((s) => (
                            <tr key={s.saleNumber} className="transition-colors hover:bg-bg-light/60">
                              <td className="whitespace-nowrap px-3 py-2.5 font-mono text-xs">
                                #{s.saleNumber}
                              </td>
                              <td className="px-3 py-2.5">
                                <div className="text-sm">{s.customerName || "—"}</div>
                                {s.customerCpf && (
                                  <div className="text-xs text-text-tertiary">{s.customerCpf}</div>
                                )}
                              </td>
                              <td className="whitespace-nowrap px-3 py-2.5 text-right font-semibold">
                                {s.totalAmount}
                              </td>
                              <td className="px-3 py-2.5 text-xs text-text-secondary">
                                {s.paymentType}
                              </td>
                              <td className="whitespace-nowrap px-3 py-2.5 text-xs text-text-secondary">
                                {formatDate(s.saleDate)}
                              </td>
                              <td className="px-3 py-2.5 text-center">
                                <button
                                  type="button"
                                  onClick={() => setSelectedSale(s)}
                                  className="inline-flex items-center gap-1 rounded-lg border border-secondary/30 bg-secondary/10 px-3 py-1.5 text-xs font-semibold text-secondary transition hover:bg-secondary/20"
                                >
                                  <FileText size={13} />
                                  Emitir NF-e
                                </button>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>

                    <div className="px-4 py-3">
                      <TablePagination
                        totalItems={filteredSales.length}
                        currentPage={safeSalesPage}
                        itemsPerPage={salesPerPage}
                        onPageChange={setSalesPage}
                        onItemsPerPageChange={(v) => {
                          setSalesPerPage(v);
                          setSalesPage(1);
                        }}
                      />
                    </div>
                  </>
                )}
              </div>
            </section>
          ) : (
            <>
              {/* Selected sale header */}
              <section className="card p-4">
                <div className="flex items-center justify-between">
                  <div>
                    <p className="text-xs font-medium text-text-secondary">Venda selecionada</p>
                    <p className="text-lg font-bold text-text-primary">
                      #{selectedSale.saleNumber}
                      <span className="ml-3 text-base font-semibold text-secondary">
                        {selectedSale.totalAmount}
                      </span>
                    </p>
                    {selectedSale.customerName && (
                      <p className="text-sm text-text-secondary">
                        {selectedSale.customerName}
                        {selectedSale.customerCpf && ` — ${selectedSale.customerCpf}`}
                      </p>
                    )}
                  </div>
                  <button
                    type="button"
                    onClick={() => {
                      setSelectedSale(null);
                      setDest({ ...EMPTY_DEST });
                    }}
                    className="btn-cancel px-3 py-1.5 text-sm"
                  >
                    Trocar venda
                  </button>
                </div>
              </section>

              {/* Step 2: Destinatário form */}
              <section className="card overflow-hidden">
                <div className="border-b border-border-primary bg-bg-light px-4 py-3">
                  <h2 className="flex items-center gap-2 text-sm font-bold text-text-primary">
                    <Building2 size={16} className="text-secondary" />
                    2. Dados do Destinatário (empresa compradora)
                  </h2>
                </div>

                <div className="grid gap-4 p-4 md:grid-cols-2 lg:grid-cols-3">
                  {/* CNPJ */}
                  <div>
                    <label className="mb-1 block text-xs font-semibold text-text-secondary">
                      CNPJ/CPF *
                    </label>
                    <input
                      type="text"
                      placeholder="00.000.000/0000-00"
                      value={dest.cpfCnpj}
                      onChange={(e) => setDest((p) => ({ ...p, cpfCnpj: e.target.value }))}
                      className="input-field w-full text-sm"
                    />
                  </div>

                  {/* Razão Social */}
                  <div className="md:col-span-2">
                    <label className="mb-1 block text-xs font-semibold text-text-secondary">
                      Razão Social / Nome *
                    </label>
                    <input
                      type="text"
                      placeholder="Razão Social da empresa"
                      value={dest.nome}
                      onChange={(e) => setDest((p) => ({ ...p, nome: e.target.value }))}
                      className="input-field w-full text-sm"
                    />
                  </div>

                  {/* IE */}
                  <div>
                    <label className="mb-1 block text-xs font-semibold text-text-secondary">
                      Inscrição Estadual
                    </label>
                    <input
                      type="text"
                      placeholder="Inscrição Estadual"
                      value={dest.inscricaoEstadual ?? ""}
                      onChange={(e) =>
                        setDest((p) => ({ ...p, inscricaoEstadual: e.target.value }))
                      }
                      className="input-field w-full text-sm"
                    />
                  </div>

                  {/* Ind IE Dest */}
                  <div>
                    <label className="mb-1 block text-xs font-semibold text-text-secondary">
                      Indicador IE
                    </label>
                    <select
                      value={dest.indIeDest}
                      onChange={(e) =>
                        setDest((p) => ({ ...p, indIeDest: Number(e.target.value) }))
                      }
                      className="select-field w-full text-sm"
                    >
                      <option value={1}>1 — Contribuinte ICMS</option>
                      <option value={2}>2 — Isento</option>
                      <option value={9}>9 — Não contribuinte</option>
                    </select>
                  </div>

                  {/* Email */}
                  <div>
                    <label className="mb-1 block text-xs font-semibold text-text-secondary">
                      E-mail
                    </label>
                    <input
                      type="email"
                      placeholder="email@empresa.com.br"
                      value={dest.email ?? ""}
                      onChange={(e) => setDest((p) => ({ ...p, email: e.target.value }))}
                      className="input-field w-full text-sm"
                    />
                  </div>

                  {/* Fone */}
                  <div>
                    <label className="mb-1 block text-xs font-semibold text-text-secondary">
                      Telefone
                    </label>
                    <input
                      type="text"
                      placeholder="(21) 99999-9999"
                      value={dest.fone ?? ""}
                      onChange={(e) => setDest((p) => ({ ...p, fone: e.target.value }))}
                      className="input-field w-full text-sm"
                    />
                  </div>
                </div>

                {/* Address section */}
                <div className="border-t border-border-primary px-4 py-3">
                  <h3 className="mb-3 flex items-center gap-2 text-xs font-bold uppercase tracking-wider text-text-secondary">
                    <MapPin size={14} />
                    Endereço do Destinatário
                  </h3>
                  <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
                    {/* CEP */}
                    <div>
                      <label className="mb-1 block text-xs font-semibold text-text-secondary">
                        CEP *
                      </label>
                      <div className="relative">
                        <input
                          type="text"
                          placeholder="00000-000"
                          value={dest.cep}
                          onChange={(e) => setDest((p) => ({ ...p, cep: e.target.value }))}
                          onBlur={handleCepBlur}
                          className="input-field w-full text-sm"
                        />
                        {cepLoading && (
                          <Loader2
                            size={14}
                            className="absolute right-3 top-1/2 -translate-y-1/2 animate-spin text-text-tertiary"
                          />
                        )}
                      </div>
                    </div>

                    {/* Logradouro */}
                    <div className="lg:col-span-2">
                      <label className="mb-1 block text-xs font-semibold text-text-secondary">
                        Logradouro *
                      </label>
                      <input
                        type="text"
                        placeholder="Rua, Av, etc."
                        value={dest.logradouro}
                        onChange={(e) => setDest((p) => ({ ...p, logradouro: e.target.value }))}
                        className="input-field w-full text-sm"
                      />
                    </div>

                    {/* Número */}
                    <div>
                      <label className="mb-1 block text-xs font-semibold text-text-secondary">
                        Número
                      </label>
                      <input
                        type="text"
                        placeholder="S/N"
                        value={dest.numero}
                        onChange={(e) => setDest((p) => ({ ...p, numero: e.target.value }))}
                        className="input-field w-full text-sm"
                      />
                    </div>

                    {/* Complemento */}
                    <div>
                      <label className="mb-1 block text-xs font-semibold text-text-secondary">
                        Complemento
                      </label>
                      <input
                        type="text"
                        placeholder="Sala, andar..."
                        value={dest.complemento ?? ""}
                        onChange={(e) => setDest((p) => ({ ...p, complemento: e.target.value }))}
                        className="input-field w-full text-sm"
                      />
                    </div>

                    {/* Bairro */}
                    <div>
                      <label className="mb-1 block text-xs font-semibold text-text-secondary">
                        Bairro *
                      </label>
                      <input
                        type="text"
                        placeholder="Bairro"
                        value={dest.bairro}
                        onChange={(e) => setDest((p) => ({ ...p, bairro: e.target.value }))}
                        className="input-field w-full text-sm"
                      />
                    </div>

                    {/* Cidade */}
                    <div>
                      <label className="mb-1 block text-xs font-semibold text-text-secondary">
                        Cidade *
                      </label>
                      <input
                        type="text"
                        placeholder="Cidade"
                        value={dest.nomeMunicipio}
                        onChange={(e) => setDest((p) => ({ ...p, nomeMunicipio: e.target.value }))}
                        className="input-field w-full text-sm"
                      />
                    </div>

                    {/* UF */}
                    <div>
                      <label className="mb-1 block text-xs font-semibold text-text-secondary">
                        UF *
                      </label>
                      <input
                        type="text"
                        placeholder="RJ"
                        maxLength={2}
                        value={dest.uf}
                        onChange={(e) =>
                          setDest((p) => ({ ...p, uf: e.target.value.toUpperCase() }))
                        }
                        className="input-field w-full text-sm"
                      />
                    </div>
                  </div>
                </div>

                {/* NF-e options */}
                <div className="border-t border-border-primary px-4 py-3">
                  <div className="grid gap-4 md:grid-cols-2">
                    <div>
                      <label className="mb-1 block text-xs font-semibold text-text-secondary">
                        Natureza da Operação
                      </label>
                      <input
                        type="text"
                        value={natOp}
                        onChange={(e) => setNatOp(e.target.value)}
                        className="input-field w-full text-sm"
                      />
                    </div>
                    <div>
                      <label className="mb-1 block text-xs font-semibold text-text-secondary">
                        Modalidade de Frete
                      </label>
                      <select
                        value={modFrete}
                        onChange={(e) => setModFrete(Number(e.target.value))}
                        className="select-field w-full text-sm"
                      >
                        <option value={0}>0 — Contratação por conta do remetente (CIF)</option>
                        <option value={1}>1 — Contratação por conta do destinatário (FOB)</option>
                        <option value={2}>2 — Contratação por conta de terceiros</option>
                        <option value={9}>9 — Sem ocorrência de transporte</option>
                      </select>
                    </div>
                  </div>
                </div>

                {/* Submit */}
                <div className="flex items-center justify-end gap-3 border-t border-border-primary px-4 py-4">
                  <button
                    type="button"
                    onClick={() => {
                      setSelectedSale(null);
                      setDest({ ...EMPTY_DEST });
                    }}
                    className="btn-cancel px-4 py-2 text-sm"
                  >
                    Cancelar
                  </button>
                  <button
                    type="button"
                    onClick={handleEmitir}
                    disabled={emitting}
                    className="btn-primary inline-flex items-center gap-2 px-5 py-2 text-sm"
                  >
                    {emitting ? (
                      <Loader2 size={16} className="animate-spin" />
                    ) : (
                      <Send size={16} />
                    )}
                    Emitir NF-e
                  </button>
                </div>
              </section>
            </>
          )}
        </>
      )}

      {activeTab === "emitidas" && (
        <section className="card overflow-hidden">
          <div className="border-b border-border-primary bg-bg-light px-4 py-3">
            <div className="flex items-center justify-between">
              <h2 className="text-sm font-bold text-text-primary">NF-e Emitidas</h2>
              <button
                type="button"
                onClick={loadNfeList}
                disabled={nfeLoading}
                className="inline-flex items-center gap-1.5 text-xs font-semibold text-secondary hover:text-secondary/80"
              >
                <RefreshCw size={14} className={nfeLoading ? "animate-spin" : ""} />
                Atualizar
              </button>
            </div>
          </div>

          {nfeLoading ? (
            <div className="flex items-center justify-center gap-2 py-12 text-text-secondary">
              <Loader2 size={18} className="animate-spin" />
              <span>Carregando...</span>
            </div>
          ) : nfeList.length === 0 ? (
            <p className="py-12 text-center text-sm text-text-secondary">
              Nenhuma NF-e emitida ainda.
            </p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border-primary bg-bg-light text-left text-xs font-semibold uppercase tracking-wider text-text-secondary">
                    <th className="px-4 py-2">Venda</th>
                    <th className="px-4 py-2">Série/Nº</th>
                    <th className="px-4 py-2">Status</th>
                    <th className="px-4 py-2">Chave de Acesso</th>
                    <th className="px-4 py-2">Autorização</th>
                    <th className="px-4 py-2 text-right">Valor</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border-primary">
                  {nfeList.map((doc) => (
                    <tr key={doc.id} className="transition-colors hover:bg-bg-light/60">
                      <td className="whitespace-nowrap px-4 py-2.5 font-mono text-xs">
                        #{doc.saleNumber}
                      </td>
                      <td className="whitespace-nowrap px-4 py-2.5 text-xs">
                        {doc.serie}/{doc.numeroNf}
                      </td>
                      <td className="px-4 py-2.5">
                        <span
                          className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-semibold ${fiscalStatusBadgeClass(doc.status)}`}
                        >
                          {fiscalStatusLabel(doc.status)}
                        </span>
                      </td>
                      <td className="px-4 py-2.5 font-mono text-xs text-text-secondary">
                        {doc.chaveAcesso
                          ? `${doc.chaveAcesso.slice(0, 4)}...${doc.chaveAcesso.slice(-6)}`
                          : "—"}
                      </td>
                      <td className="whitespace-nowrap px-4 py-2.5 text-xs text-text-secondary">
                        {doc.dhAutorizacao ? formatDate(doc.dhAutorizacao) : "—"}
                      </td>
                      <td className="whitespace-nowrap px-4 py-2.5 text-right text-sm font-semibold">
                        {doc.totalAmount ?? "—"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
    </PageLayout>
  );
}
