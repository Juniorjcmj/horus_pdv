/**
 * Arquivo: FRONTEND/src/components/Admin/QuickCustomerRegisterModal.tsx
 * Objetivo: modal de cadastro rápido de cliente integrado ao fluxo de venda do PDV (Fiado / CPF).
 * Permite cadastrar e vincular o cliente instantaneamente sem sair do caixa ou perder a venda.
 */
import { useState, useEffect } from "react";
import { UserPlus, X, Loader2, Check, Search, AlertCircle } from "lucide-react";
import { customerService, type CustomerDto, type CustomerPayload } from "@/services/api/customerService";
import { Toast } from "@/hooks/Dialog";
import {
  maskCpfOrCnpj,
  maskCellphoneBr,
  maskMoneyBr,
  maskCep,
  onlyDigits,
} from "@/utils/inputMasks";
import { lookupAddressByCep, sanitizeCep } from "@/utils/cepLookup";

interface QuickCustomerRegisterModalProps {
  open: boolean;
  initialDocument?: string;
  initialName?: string;
  onClose: () => void;
  onSuccess: (customer: CustomerDto) => void;
}

export function QuickCustomerRegisterModal({
  open,
  initialDocument = "",
  initialName = "",
  onClose,
  onSuccess,
}: QuickCustomerRegisterModalProps) {
  const [customerName, setCustomerName] = useState("");
  const [document, setDocument] = useState("");
  const [cellphone, setCellphone] = useState("");
  const [limiteCredito, setLimiteCredito] = useState("0,00");
  const [email, setEmail] = useState("");
  const [cep, setCep] = useState("");
  const [address, setAddress] = useState("");
  const [number, setNumber] = useState("");
  const [neighborhood, setNeighborhood] = useState("");
  const [city, setCity] = useState("");
  const [state, setState] = useState("");

  const [saving, setSaving] = useState(false);
  const [loadingCep, setLoadingCep] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  // Inicializa com dados passados pela busca do PDV
  useEffect(() => {
    if (open) {
      setFormError(null);
      const cleanDoc = onlyDigits(initialDocument);
      if (cleanDoc.length === 11 || cleanDoc.length === 14) {
        setDocument(maskCpfOrCnpj(cleanDoc));
      } else {
        setDocument(maskCpfOrCnpj(initialDocument));
      }

      if (initialName && !cleanDoc) {
        setCustomerName(initialName);
      } else {
        setCustomerName("");
      }

      setCellphone("");
      setLimiteCredito("0,00");
      setEmail("");
      setCep("");
      setAddress("");
      setNumber("");
      setNeighborhood("");
      setCity("");
      setState("");
    }
  }, [open, initialDocument, initialName]);

  if (!open) return null;

  const handleCepSearch = async () => {
    const rawCep = sanitizeCep(cep);
    if (rawCep.length !== 8) {
      Toast.error("Informe um CEP válido com 8 dígitos.");
      return;
    }

    setLoadingCep(true);
    try {
      const result = await lookupAddressByCep(rawCep);
      if (result.success) {
        setAddress(result.data.endereco);
        setNeighborhood(result.data.bairro);
        setCity(result.data.cidade);
        setState(result.data.estado);
        Toast.success("Endereço preenchido pelo CEP.");
      } else {
        Toast.error("CEP não localizado.");
      }
    } catch {
      Toast.error("Erro ao consultar o CEP.");
    } finally {
      setLoadingCep(false);
    }
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError(null);

    const nameClean = customerName.trim();
    if (!nameClean || nameClean.length < 3) {
      setFormError("Informe o nome completo do cliente (mínimo 3 caracteres).");
      return;
    }

    const docDigits = onlyDigits(document);
    if (docDigits.length !== 11 && docDigits.length !== 14) {
      setFormError("Informe um CPF (11 dígitos) ou CNPJ (14 dígitos) válido.");
      return;
    }

    const phoneDigits = onlyDigits(cellphone);
    if (phoneDigits.length < 10) {
      setFormError("Informe um número de celular ou WhatsApp válido com DDD.");
      return;
    }

    if (email && !email.includes("@")) {
      setFormError("Informe um endereço de e-mail válido.");
      return;
    }

    // Parse limite de crédito
    const limiteClean = limiteCredito.replace(/\./g, "").replace(",", ".");
    const limiteNum = parseFloat(limiteClean) || 0;

    const payload: CustomerPayload = {
      customerName: nameClean,
      document: maskCpfOrCnpj(document),
      birthDate: "",
      age: "",
      cep: maskCep(cep),
      city,
      state,
      address,
      neighborhood,
      streetComplement: "",
      number,
      referencePoint: "",
      telephone: "",
      cellphone: maskCellphoneBr(cellphone),
      email: email.trim(),
      limiteCredito: limiteNum,
      saldoDevedor: 0,
    };

    setSaving(true);
    try {
      const created = await customerService.create(payload);
      if (!created) {
        throw new Error("Não foi possível cadastrar o cliente.");
      }
      Toast.success(`Cliente ${created.customerName} cadastrado com sucesso!`);
      onSuccess(created);
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Erro ao cadastrar cliente.";
      setFormError(msg);
      Toast.error(msg);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-[230] flex items-center justify-center bg-black/70 p-4 backdrop-blur-xs animate-fadeIn"
      onClick={onClose}
    >
      <div
        className="card flex max-h-[92vh] w-full max-w-xl flex-col rounded-2xl border border-border-primary bg-bg-light p-5 shadow-2xl overflow-hidden"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Cabeçalho */}
        <div className="flex items-center justify-between border-b border-border-primary pb-3.5">
          <div className="flex items-center gap-2.5">
            <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-accent/15 text-accent">
              <UserPlus size={18} />
            </div>
            <div>
              <h3 className="text-base font-semibold text-text-primary">
                Cadastro Rápido de Cliente
              </h3>
              <p className="text-[11px] text-text-secondary">
                Cadastre o cliente e vincule à venda instantaneamente
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg p-1.5 text-text-secondary transition hover:bg-hover-light hover:text-text-primary"
            aria-label="Fechar"
          >
            <X size={16} />
          </button>
        </div>

        {/* Formulário */}
        <form onSubmit={handleSave} className="mt-4 flex flex-1 flex-col overflow-y-auto pr-1">
          {formError && (
            <div className="mb-3 flex items-center gap-2 rounded-xl border border-danger/30 bg-danger/10 p-3 text-xs text-danger">
              <AlertCircle size={15} className="shrink-0" />
              <span>{formError}</span>
            </div>
          )}

          <div className="space-y-3.5 text-xs">
            {/* Nome Completo */}
            <div>
              <label className="mb-1 block font-medium text-text-primary">
                Nome Completo <span className="text-danger">*</span>
              </label>
              <input
                type="text"
                value={customerName}
                onChange={(e) => setCustomerName(e.target.value)}
                placeholder="Ex: Maria das Graças Silva"
                className="input-field w-full text-xs"
                required
                autoFocus
              />
            </div>

            {/* Documento e Celular */}
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <div>
                <label className="mb-1 block font-medium text-text-primary">
                  CPF ou CNPJ <span className="text-danger">*</span>
                </label>
                <input
                  type="text"
                  value={document}
                  onChange={(e) => setDocument(maskCpfOrCnpj(e.target.value))}
                  placeholder="000.000.000-00"
                  className="input-field w-full text-xs font-mono"
                  required
                />
              </div>
              <div>
                <label className="mb-1 block font-medium text-text-primary">
                  Celular / WhatsApp <span className="text-danger">*</span>
                </label>
                <input
                  type="text"
                  value={cellphone}
                  onChange={(e) => setCellphone(maskCellphoneBr(e.target.value))}
                  placeholder="(00) 00000-0000"
                  className="input-field w-full text-xs"
                  required
                />
              </div>
            </div>

            {/* Limite de Crédito e E-mail */}
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <div>
                <label className="mb-1 flex items-center justify-between font-medium text-text-primary">
                  <span>Limite de Crédito Fiado (R$)</span>
                  <span className="text-[10px] text-text-tertiary">0 = Ilimitado</span>
                </label>
                <input
                  type="text"
                  value={limiteCredito}
                  onChange={(e) => setLimiteCredito(maskMoneyBr(e.target.value))}
                  placeholder="0,00"
                  className="input-field w-full text-xs font-mono"
                />
              </div>
              <div>
                <label className="mb-1 block font-medium text-text-primary">
                  E-mail <span className="text-[10px] text-text-tertiary">(Opcional)</span>
                </label>
                <input
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="cliente@email.com"
                  className="input-field w-full text-xs"
                />
              </div>
            </div>

            {/* Seção Endereço (Compacta / Opcional) */}
            <div className="rounded-xl border border-border-primary bg-bg-primary/40 p-3 space-y-2.5">
              <span className="text-[11px] font-semibold text-text-secondary uppercase tracking-wider">
                Endereço (Opcional)
              </span>

              <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
                <div className="relative">
                  <input
                    type="text"
                    value={cep}
                    onChange={(e) => setCep(maskCep(e.target.value))}
                    placeholder="CEP 00000-000"
                    className="input-field w-full pr-8 text-xs font-mono"
                  />
                  <button
                    type="button"
                    onClick={handleCepSearch}
                    disabled={loadingCep}
                    className="absolute right-2 top-1/2 -translate-y-1/2 text-text-secondary hover:text-accent disabled:opacity-50"
                    title="Buscar CEP"
                  >
                    {loadingCep ? (
                      <Loader2 size={14} className="animate-spin text-accent" />
                    ) : (
                      <Search size={14} />
                    )}
                  </button>
                </div>
                <div className="sm:col-span-2">
                  <input
                    type="text"
                    value={address}
                    onChange={(e) => setAddress(e.target.value)}
                    placeholder="Logradouro / Rua"
                    className="input-field w-full text-xs"
                  />
                </div>
              </div>

              <div className="grid grid-cols-3 gap-2">
                <div>
                  <input
                    type="text"
                    value={number}
                    onChange={(e) => setNumber(e.target.value)}
                    placeholder="Número"
                    className="input-field w-full text-xs"
                  />
                </div>
                <div>
                  <input
                    type="text"
                    value={neighborhood}
                    onChange={(e) => setNeighborhood(e.target.value)}
                    placeholder="Bairro"
                    className="input-field w-full text-xs"
                  />
                </div>
                <div>
                  <input
                    type="text"
                    value={city ? (state ? `${city} - ${state}` : city) : ""}
                    onChange={(e) => setCity(e.target.value)}
                    placeholder="Cidade"
                    className="input-field w-full text-xs"
                  />
                </div>
              </div>
            </div>
          </div>

          {/* Rodapé / Ações */}
          <div className="mt-5 flex items-center justify-end gap-2 border-t border-border-primary pt-3">
            <button
              type="button"
              onClick={onClose}
              disabled={saving}
              className="btn-secondary px-3.5 py-2 text-xs"
            >
              Cancelar
            </button>
            <button
              type="submit"
              disabled={saving}
              className="btn-primary inline-flex items-center gap-1.5 px-4 py-2 text-xs font-semibold shadow-md"
            >
              {saving ? (
                <>
                  <Loader2 size={14} className="animate-spin" />
                  Cadastrando...
                </>
              ) : (
                <>
                  <Check size={14} />
                  Salvar e Selecionar Cliente
                </>
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
