/**
 * Arquivo: src/components/SettingsPage/FiscalEmissionRulesCard.tsx
 * Objetivo: permite ao administrador/gerente configurar quais formas de pagamento SEMPRE emitem NFC-e (ex.: PIX)
 *           e, para todas as demais formas, a cada quantas vendas uma nota será gerada perante a SEFAZ
 *           (ex.: a cada 30 vendas emitir 1 NFC-e).
 */
import { useEffect, useState } from "react";
import {
  Banknote,
  CheckCircle2,
  CreditCard,
  FileCheck2,
  QrCode,
  RotateCcw,
  Sparkles,
  UserCheck,
  Users,
} from "lucide-react";
import { YesNoSegmentedControl } from "@/components/Form";
import { Toast } from "@/hooks/Dialog";
import {
  regrasEmissaoNfceService,
  type RegrasEmissaoNfceDto,
} from "@/services/api/regrasEmissaoNfceService";

type PaymentOption = {
  id: string;
  label: string;
  description: string;
  icon: typeof Banknote;
};

const PAYMENT_OPTIONS: PaymentOption[] = [
  {
    id: "dinheiro",
    label: "Dinheiro",
    description: "Pagamentos em espécie no balcão",
    icon: Banknote,
  },
  {
    id: "credito",
    label: "Cartão de Crédito",
    description: "Transações em maquininha ou TEF crédito",
    icon: CreditCard,
  },
  {
    id: "debito",
    label: "Cartão de Débito",
    description: "Transações em maquininha ou TEF débito",
    icon: CreditCard,
  },
  {
    id: "pix",
    label: "PIX",
    description: "Transferências instantâneas via QR Code",
    icon: QrCode,
  },
  {
    id: "fiado",
    label: "Fiado / A Prazo",
    description: "Contas assinadas por clientes cadastrados",
    icon: Users,
  },
];

const PRESETS = [1, 5, 10, 20, 30, 50];

export default function FiscalEmissionRulesCard() {
  const [config, setConfig] = useState<RegrasEmissaoNfceDto | null>(null);
  const [habilitado, setHabilitado] = useState(true);
  const [selectedMethods, setSelectedMethods] = useState<string[]>([
    "dinheiro",
    "credito",
    "debito",
    "pix",
    "fiado",
  ]);
  const [intervaloNotas, setIntervaloNotas] = useState(1);
  const [emitirSempreComCpf, setEmitirSempreComCpf] = useState(true);
  const [contadorVendas, setContadorVendas] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [resetting, setResetting] = useState(false);

  useEffect(() => {
    regrasEmissaoNfceService
      .get()
      .then((data) => {
        if (data) {
          setConfig(data);
          setHabilitado(data.habilitado);
          setIntervaloNotas(data.intervaloNotas || 1);
          setEmitirSempreComCpf(data.emitirSempreComCpf);
          setContadorVendas(data.contadorVendas || 0);

          const methods = (data.formasPagamentoHabilitadas || "")
            .split(",")
            .map((s) => s.trim().toLowerCase())
            .filter(Boolean);
          // Lista vazia é válida: nenhuma forma "sempre emite" e todas seguem o intervalo.
          setSelectedMethods(methods);
        }
      })
      .catch(() => {
        // Fallback default
      })
      .finally(() => setLoading(false));
  }, []);

  const toggleMethod = (id: string) => {
    setSelectedMethods((prev) =>
      prev.includes(id) ? prev.filter((m) => m !== id) : [...prev, id],
    );
  };

  const handleSave = async () => {
    if (intervaloNotas < 1) {
      Toast.error("O intervalo de notas deve ser no mínimo 1.");
      return;
    }

    setSaving(true);
    try {
      const saved = await regrasEmissaoNfceService.save({
        habilitado,
        formasPagamentoHabilitadas: selectedMethods.join(","),
        intervaloNotas,
        emitirSempreComCpf,
      });

      if (saved) {
        setConfig(saved);
        setContadorVendas(saved.contadorVendas);
      }
      Toast.success("Regras de emissão de NFC-e salvas com sucesso!");
    } catch (err: any) {
      Toast.error(err?.message || "Erro ao salvar regras de emissão.");
    } finally {
      setSaving(false);
    }
  };

  const handleResetContador = async () => {
    setResetting(true);
    try {
      const res = await regrasEmissaoNfceService.resetContador();
      if (res) {
        setContadorVendas(res.contadorVendas);
      }
      Toast.success("Contador de intervalo de vendas reiniciado para 0.");
    } catch (err: any) {
      Toast.error(err?.message || "Erro ao reiniciar contador.");
    } finally {
      setResetting(false);
    }
  };

  const alwaysLabels = PAYMENT_OPTIONS.filter((opt) => selectedMethods.includes(opt.id)).map((opt) => opt.label);
  const intervalLabels = PAYMENT_OPTIONS.filter((opt) => !selectedMethods.includes(opt.id)).map((opt) => opt.label);
  const joinLabels = (labels: string[]) =>
    labels.length <= 1 ? labels.join("") : `${labels.slice(0, -1).join(", ")} e ${labels[labels.length - 1]}`;

  const progressPercent =
    intervaloNotas > 1 ? Math.min(100, Math.round((contadorVendas / intervaloNotas) * 100)) : 100;

  return (
    <div className="rounded-xl border border-border-primary bg-bg-primary p-5 shadow-xs">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex gap-3">
          <span className="mt-0.5 inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-accent/10 text-accent">
            <FileCheck2 size={20} />
          </span>
          <div>
            <div className="flex items-center gap-2">
              <h3 className="text-base font-semibold text-text-primary">
                Regras de Emissão de NFC-e (Formas de Pagamento e Frequência)
              </h3>
              <span className="rounded-full bg-accent/15 px-2 py-0.5 text-[11px] font-semibold text-accent">
                Fiscal Inteligente
              </span>
            </div>
            <p className="mt-1 text-xs text-text-secondary">
              Defina quais formas de pagamento emitem NFC-e <strong>sempre</strong> (ex.: PIX) e, para todas as
              outras formas, o intervalo de emissão (ex.: a cada 30 vendas emitir 1 nota fiscal).
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2 self-end sm:self-auto">
          <span className="text-xs font-semibold text-text-secondary">Ativar Regras:</span>
          <YesNoSegmentedControl
            value={habilitado}
            onChange={setHabilitado}
            ariaLabel="Ativar controle de regras de emissão de NFC-e"
          />
        </div>
      </div>

      <div className={`mt-5 space-y-6 transition-opacity ${!habilitado ? "opacity-50 pointer-events-none" : ""}`}>
        {/* 1. Formas de Pagamento Habilitadas */}
        <div>
          <div className="mb-2 flex items-center justify-between">
            <label className="text-xs font-bold uppercase tracking-wider text-text-secondary">
              1. Formas de pagamento que SEMPRE emitem NFC-e
            </label>
            <span className="text-[11px] text-text-tertiary">
              {selectedMethods.length} selecionada(s)
            </span>
          </div>

          <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
            {PAYMENT_OPTIONS.map((opt) => {
              const selected = selectedMethods.includes(opt.id);
              const Icon = opt.icon;
              return (
                <button
                  key={opt.id}
                  type="button"
                  onClick={() => toggleMethod(opt.id)}
                  className={`flex items-start gap-3 rounded-xl border p-3 text-left transition-all ${
                    selected
                      ? "border-accent/40 bg-accent/10 shadow-xs"
                      : "border-border-primary bg-bg-light/40 opacity-70 hover:opacity-100 hover:bg-bg-light"
                  }`}
                >
                  <div
                    className={`mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-lg ${
                      selected ? "bg-accent text-white" : "bg-bg-secondary text-text-secondary"
                    }`}
                  >
                    <Icon size={15} />
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center justify-between">
                      <p className={`text-xs font-semibold ${selected ? "text-accent" : "text-text-primary"}`}>
                        {opt.label}
                      </p>
                      {selected && <CheckCircle2 size={13} className="text-accent" />}
                    </div>
                    <p className="mt-0.5 text-[11px] text-text-secondary line-clamp-1">
                      {opt.description}
                    </p>
                  </div>
                </button>
              );
            })}
          </div>
          <p className="mt-1.5 text-[11px] text-text-tertiary">
            * Toda venda que tiver uma forma marcada aqui emite NFC-e, sem entrar na contagem do intervalo. As formas
            desmarcadas seguem o intervalo abaixo (contagem única para todas elas).
          </p>
        </div>

        {/* 2. Intervalo de Vendas */}
        <div className="rounded-xl border border-border-secondary bg-bg-light/50 p-4">
          <label className="block text-xs font-bold uppercase tracking-wider text-text-secondary">
            2. Intervalo de emissão das demais formas (a cada quantas vendas emitir 1 NFC-e?)
          </label>
          <p className="mt-0.5 text-xs text-text-secondary">
            Vale para as formas <strong>desmarcadas</strong> acima (dinheiro, cartão, fiado...), somadas. Por exemplo,
            configurando <strong>30</strong>, a cada 30 vendas dessas formas, 1 sairá como NFC-e e 29 como comprovante
            de venda.
          </p>

          <div className="mt-3 flex flex-wrap items-center gap-3">
            <div className="w-32">
              <input
                type="number"
                min={1}
                max={9999}
                value={intervaloNotas}
                onChange={(e) => setIntervaloNotas(Math.max(1, parseInt(e.target.value) || 1))}
                className="input-field w-full text-center text-lg font-bold text-accent"
              />
            </div>
            <span className="text-xs font-semibold text-text-secondary">
              {intervaloNotas === 1
                ? "Emissão em 100% das vendas (a cada 1 venda)"
                : `A cada ${intervaloNotas} vendas emitirá 1 NFC-e`}
            </span>

            <div className="flex flex-wrap items-center gap-1.5 sm:ml-auto">
              <span className="text-[11px] text-text-tertiary">Atalhos:</span>
              {PRESETS.map((p) => (
                <button
                  key={p}
                  type="button"
                  onClick={() => setIntervaloNotas(p)}
                  className={`rounded-lg px-2.5 py-1 text-xs font-semibold transition ${
                    intervaloNotas === p
                      ? "bg-accent text-white shadow-xs"
                      : "border border-border-primary bg-bg-light text-text-secondary hover:bg-hover-light"
                  }`}
                >
                  {p === 1 ? "Todas (1)" : `${p} em ${p}`}
                </button>
              ))}
            </div>
          </div>

          {/* Explicativo dinâmico */}
          <div className="mt-3 rounded-lg border border-accent/20 bg-accent/5 p-2.5 text-xs text-text-primary">
            <div className="flex items-center gap-1.5 font-semibold text-accent">
              <Sparkles size={14} />
              <span>Como funcionará no PDV:</span>
            </div>
            <p className="mt-1 leading-relaxed text-text-secondary">
              {alwaysLabels.length > 0 ? (
                <>
                  Vendas com <strong>{joinLabels(alwaysLabels)}</strong> emitem <strong>NFC-e sempre</strong>.{" "}
                </>
              ) : null}
              {intervalLabels.length === 0 ? (
                <>Todas as formas estão marcadas: todas as vendas emitem NFC-e e o intervalo não se aplica.</>
              ) : intervaloNotas === 1 ? (
                <>
                  Vendas com <strong>{joinLabels(intervalLabels)}</strong> também emitem NFC-e (intervalo 1 = todas).
                </>
              ) : (
                <>
                  Nas vendas com <strong>{joinLabels(intervalLabels)}</strong>, a cada{" "}
                  <strong>{intervaloNotas} vendas</strong> o sistema transmitirá{" "}
                  <strong>1 NFC-e oficial com QR-Code</strong> para a SEFAZ. As outras{" "}
                  <strong>{intervaloNotas - 1} vendas</strong> sairão como comprovante de venda não fiscal imediato.
                </>
              )}
            </p>
          </div>
        </div>

        {/* 3. Exceção com CPF */}
        <div className="flex items-center justify-between rounded-xl border border-border-primary bg-bg-light/40 p-3.5">
          <div className="flex items-start gap-3">
            <span className="mt-0.5 inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-emerald-500/10 text-emerald-600">
              <UserCheck size={18} />
            </span>
            <div>
              <p className="text-xs font-semibold text-text-primary">
                Sempre emitir quando o cliente solicitar CPF/CNPJ na nota
              </p>
              <p className="mt-0.5 text-[11px] text-text-secondary">
                Se o consumidor final solicitar a inclusão do CPF/CNPJ no momento do pagamento, a NFC-e será
                transmitida obrigatoriamente para a SEFAZ, independente do intervalo de vendas configurado.
              </p>
            </div>
          </div>
          <div className="shrink-0 ml-3">
            <YesNoSegmentedControl
              value={emitirSempreComCpf}
              onChange={setEmitirSempreComCpf}
              ariaLabel="Sempre emitir com CPF na nota"
            />
          </div>
        </div>

        {/* 4. Monitor do Ciclo Atual e Zeramento */}
        {intervaloNotas > 1 && (
          <div className="rounded-xl border border-border-primary bg-bg-light/60 p-4">
            <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
              <div>
                <p className="text-xs font-bold uppercase tracking-wider text-text-secondary">
                  Progresso do Ciclo Atual
                </p>
                <p className="text-xs text-text-primary mt-0.5">
                  Vendas (formas sem emissão automática) contabilizadas no ciclo:{" "}
                  <strong className="text-accent text-sm">{contadorVendas}</strong> de{" "}
                  <strong>{intervaloNotas}</strong>
                  {contadorVendas >= intervaloNotas - 1 && (
                    <span className="ml-2 rounded bg-amber-500/15 px-1.5 py-0.5 text-[10px] font-bold text-amber-600 dark:text-amber-400">
                      ★ A próxima venda emitirá NFC-e!
                    </span>
                  )}
                </p>
              </div>

              <button
                type="button"
                onClick={handleResetContador}
                disabled={resetting}
                className="btn-secondary inline-flex items-center gap-1.5 self-start px-3 py-1.5 text-xs text-text-secondary hover:bg-hover-light disabled:opacity-60 sm:self-auto"
                title="Reinicia a contagem de vendas de volta para 0"
              >
                <RotateCcw size={13} className={resetting ? "animate-spin" : ""} />
                Zerar Contagem
              </button>
            </div>

            <div className="mt-2.5 h-2 w-full overflow-hidden rounded-full bg-border-primary/50">
              <div
                className="h-full rounded-full bg-accent transition-all duration-300"
                style={{ width: `${progressPercent}%` }}
              />
            </div>
          </div>
        )}
      </div>

      {/* Rodapé com botão Salvar */}
      <div className="mt-5 flex items-center justify-between border-t border-border-primary/60 pt-4">
        <p className="text-[11px] text-text-tertiary">
          {config?.updatedAt
            ? `Última alteração: ${new Date(config.updatedAt).toLocaleString("pt-BR")}`
            : "Configurações padrão ativas."}
        </p>

        <button
          type="button"
          onClick={handleSave}
          disabled={loading || saving}
          className="btn-success px-5 py-2 text-xs font-semibold shadow-md"
        >
          {saving ? "Salvando regras..." : "Salvar Regras de Emissão"}
        </button>
      </div>
    </div>
  );
}
