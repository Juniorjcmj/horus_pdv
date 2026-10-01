/**
 * Arquivo: src/components/SettingsPage/ThemeColorsCard.tsx
 * Objetivo: escolher as cores de acento da empresa — cor do sistema e cor da frente de caixa (PDV).
 *           Salva na Cloud (herdado por todos os terminais) e aplica na hora neste dispositivo.
 *           Camada aditiva; vazio = usa o padrão do sistema.
 */
import { useEffect, useState } from "react";
import { Palette } from "lucide-react";
import { Toast } from "@/hooks/Dialog";
import { companyThemeService } from "@/services/api/companyThemeService";
import { type CompanyTheme } from "@/utils/companyTheme";

// Padrões visuais (apenas para exibir no seletor quando a empresa ainda não escolheu).
const DEFAULT_SYSTEM = "#0369a1";
const DEFAULT_PDV = "#0369a1";

export default function ThemeColorsCard() {
  const [systemAccent, setSystemAccent] = useState<string | null>(null);
  const [pdvAccent, setPdvAccent] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    companyThemeService
      .get()
      .then((t) => {
        if (t) {
          setSystemAccent(t.systemAccent ?? null);
          setPdvAccent(t.pdvAccent ?? null);
        }
      })
      .catch(() => {
        /* sem tema ainda / offline */
      })
      .finally(() => setLoading(false));
  }, []);

  const handleSave = async () => {
    setSaving(true);
    try {
      const payload = { systemAccent, pdvAccent };
      const saved = await companyThemeService.save(payload);
      const next: CompanyTheme = {
        systemAccent: saved?.systemAccent ?? null,
        pdvAccent: saved?.pdvAccent ?? null,
      };
      // Aplica imediatamente neste dispositivo (App ouve este evento).
      window.dispatchEvent(new CustomEvent<CompanyTheme>("horuspdv-theme-change", { detail: next }));
      Toast.success("Cores salvas. Os terminais da empresa herdam ao recarregar.");
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao salvar as cores.");
    } finally {
      setSaving(false);
    }
  };

  const renderPicker = (
    label: string,
    hint: string,
    value: string | null,
    fallback: string,
    onChange: (v: string | null) => void,
  ) => (
    <div className="flex items-center justify-between gap-3 rounded-lg border border-border-secondary/60 p-3">
      <div>
        <p className="text-sm font-medium text-text-primary">{label}</p>
        <p className="text-xs text-text-tertiary">{hint}</p>
      </div>
      <div className="flex items-center gap-2">
        <input
          type="color"
          className="h-9 w-12 cursor-pointer rounded border border-border-primary bg-transparent"
          value={value ?? fallback}
          onChange={(e) => onChange(e.target.value)}
          disabled={loading || saving}
          aria-label={label}
        />
        <button
          type="button"
          className="text-xs text-text-secondary underline underline-offset-2 hover:text-accent disabled:opacity-50"
          onClick={() => onChange(null)}
          disabled={loading || saving || value === null}
        >
          Padrão
        </button>
      </div>
    </div>
  );

  return (
    <div className="rounded-xl border border-border-primary bg-bg-primary p-4">
      <div className="flex gap-3">
        <span className="mt-0.5 inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-accent/10 text-accent">
          <Palette size={18} />
        </span>
        <div className="flex-1">
          <p className="text-base font-semibold text-text-primary">Cores do sistema</p>
          <p className="mt-1 text-sm text-text-secondary">
            Defina a cor de acento da empresa. A cor da frente de caixa sobrepõe a do sistema apenas na
            tela de venda. Vazio (&quot;Padrão&quot;) usa a cor original do sistema.
          </p>

          <div className="mt-4 space-y-3">
            {renderPicker(
              "Cor do sistema",
              "Aplicada em todo o sistema.",
              systemAccent,
              DEFAULT_SYSTEM,
              setSystemAccent,
            )}
            {renderPicker(
              "Cor da frente de caixa (PDV)",
              "Vale somente na tela de venda.",
              pdvAccent,
              DEFAULT_PDV,
              setPdvAccent,
            )}
          </div>

          <div className="mt-4 flex justify-end">
            <button type="button" className="btn-success" onClick={handleSave} disabled={loading || saving}>
              {saving ? "Salvando..." : "Salvar cores"}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
