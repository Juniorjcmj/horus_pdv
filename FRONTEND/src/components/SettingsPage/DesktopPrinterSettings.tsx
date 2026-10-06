/**
 * Arquivo: src/components/SettingsPage/DesktopPrinterSettings.tsx
 * Objetivo: impressora do caixa no programa desktop (1.2.0+): escolher a impressora (ex.: térmica do cupom)
 *           e imprimir direto, sem a janela de impressão do Windows. Configuração gravada no programa,
 *           por computador. No navegador comum não aparece.
 */
import { Printer, RefreshCw } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { YesNoSegmentedControl } from "@/components/Form";
import { Toast } from "@/hooks/Dialog";
import {
  getDesktopBridge,
  type DesktopPrinterInfo,
  type DesktopPrinterSettings as Settings,
} from "@/infrastructure/desktop/bridge";

export default function DesktopPrinterSettings() {
  const printer = getDesktopBridge()?.printer;
  const [printers, setPrinters] = useState<DesktopPrinterInfo[]>([]);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [testing, setTesting] = useState(false);

  const load = useCallback(async () => {
    if (!printer) return;
    try {
      const [list, current] = await Promise.all([printer.list(), printer.getSettings()]);
      setPrinters(list);
      setSettings(current);
    } catch {
      Toast.error("Não foi possível consultar as impressoras deste computador.");
    }
  }, [printer]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load();
  }, [load]);

  if (!printer) return null;

  const save = async (patch: Partial<Settings>) => {
    try {
      setSettings(await printer.saveSettings(patch));
    } catch {
      Toast.error("Não foi possível salvar a impressora.");
    }
  };

  const handleTest = async () => {
    if (!settings) return;
    setTesting(true);
    try {
      const result = await printer.test(settings);
      if (result.printed) Toast.success("Página de teste enviada para a impressora.");
      else Toast.error(result.error || "A impressora não imprimiu.");
    } catch {
      Toast.error("Não foi possível imprimir o teste.");
    } finally {
      setTesting(false);
    }
  };

  const selectedMissing = Boolean(settings?.deviceName) && !printers.some((p) => p.name === settings?.deviceName);

  return (
    <div className="mt-4 border-t border-border-primary pt-4">
      <p className="flex items-center gap-2 text-sm font-semibold text-text-primary">
        <Printer size={15} className="text-accent" />
        Impressora deste computador
      </p>
      <p className="mt-1 text-sm text-text-secondary">
        Cupom, DANFE NFC-e, fechamento de caixa e demais comprovantes. Com "imprimir direto", saem na impressora
        escolhida sem abrir a janela de impressão.
      </p>

      {settings ? (
        <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
          <div className="space-y-1">
            <label htmlFor="desktop-printer" className="text-xs font-semibold text-text-primary">
              Impressora
            </label>
            <div className="flex gap-2">
              <select
                id="desktop-printer"
                value={settings.deviceName}
                onChange={(event) => void save({ deviceName: event.target.value })}
                className="input-field w-full min-w-0 text-sm"
              >
                <option value="">Padrão do Windows</option>
                {selectedMissing ? <option value={settings.deviceName}>{settings.deviceName} (não encontrada)</option> : null}
                {printers.map((p) => (
                  <option key={p.name} value={p.name}>
                    {p.displayName}
                    {p.isDefault ? " (padrão)" : ""}
                  </option>
                ))}
              </select>
              <button
                type="button"
                onClick={() => void load()}
                title="Atualizar lista de impressoras"
                aria-label="Atualizar lista de impressoras"
                className="inline-flex shrink-0 items-center rounded-lg border border-border-secondary px-2.5 text-text-secondary hover:bg-hover-light"
              >
                <RefreshCw size={14} />
              </button>
            </div>
            {selectedMissing ? (
              <p className="text-xs font-semibold text-primary">Impressora não encontrada: confira se está ligada e instalada.</p>
            ) : null}
          </div>

          <div className="space-y-1">
            <label htmlFor="desktop-printer-copies" className="text-xs font-semibold text-text-primary">
              Cópias
            </label>
            <select
              id="desktop-printer-copies"
              value={settings.copies}
              onChange={(event) => void save({ copies: Number(event.target.value) })}
              className="input-field w-full text-sm"
            >
              {[1, 2, 3].map((n) => (
                <option key={n} value={n}>
                  {n}
                </option>
              ))}
            </select>
          </div>

          <div className="flex items-center justify-between gap-3 sm:col-span-2">
            <span className="text-sm text-text-primary">Imprimir direto (sem a janela de impressão)</span>
            <YesNoSegmentedControl
              value={settings.mode === "direct"}
              onChange={(direct) => void save({ mode: direct ? "direct" : "dialog" })}
              ariaLabel="Imprimir direto na impressora"
            />
          </div>

          <div className="sm:col-span-2">
            <button
              type="button"
              onClick={() => void handleTest()}
              disabled={testing}
              className="inline-flex items-center gap-1.5 rounded-lg border border-border-secondary px-3 py-1.5 text-xs font-semibold text-text-secondary hover:bg-hover-light disabled:opacity-60"
            >
              <Printer size={13} />
              {testing ? "Imprimindo..." : "Imprimir página de teste"}
            </button>
          </div>
        </div>
      ) : (
        <p className="mt-3 text-sm text-text-tertiary">Consultando impressoras...</p>
      )}
    </div>
  );
}
