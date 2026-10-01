/**
 * Arquivo: src/utils/companyTheme.ts
 * Objetivo: tema de cores por empresa (cor de acento do sistema + cor da frente de caixa).
 *           A Cloud é a autoridade; aqui ficam o cache local (para aplicar sem flash) e as funções
 *           que escrevem os tokens CSS (`--color-accent` / `--color-hover-accent`) em runtime.
 *           Camada aditiva — sem cor personalizada, o sistema usa o padrão do index.css.
 */

export type CompanyTheme = {
  /** Cor de acento do sistema inteiro (hex #rrggbb) ou null = padrão. */
  systemAccent: string | null;
  /** Cor de acento só da frente de caixa (hex #rrggbb) ou null = usa a do sistema. */
  pdvAccent: string | null;
};

const KEY = "horuspdv.company-theme";

export const EMPTY_COMPANY_THEME: CompanyTheme = { systemAccent: null, pdvAccent: null };

const HEX = /^#[0-9a-fA-F]{6}$/;

export function isHexColor(value: string | null | undefined): value is string {
  return !!value && HEX.test(value);
}

export function getCachedCompanyTheme(): CompanyTheme {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return { ...EMPTY_COMPANY_THEME };
    const parsed = JSON.parse(raw) as Partial<CompanyTheme>;
    return {
      systemAccent: isHexColor(parsed.systemAccent) ? parsed.systemAccent! : null,
      pdvAccent: isHexColor(parsed.pdvAccent) ? parsed.pdvAccent! : null,
    };
  } catch {
    return { ...EMPTY_COMPANY_THEME };
  }
}

export function cacheCompanyTheme(theme: CompanyTheme): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(theme));
  } catch {
    /* storage indisponível — segue só em memória */
  }
}

/** Escurece um hex #rrggbb por um fator (0..1) — usado para derivar a cor de hover do acento. */
export function darken(hex: string, factor = 0.18): string {
  if (!isHexColor(hex)) return hex;
  const n = parseInt(hex.slice(1), 16);
  const r = Math.max(0, Math.round(((n >> 16) & 0xff) * (1 - factor)));
  const g = Math.max(0, Math.round(((n >> 8) & 0xff) * (1 - factor)));
  const b = Math.max(0, Math.round((n & 0xff) * (1 - factor)));
  return `#${((r << 16) | (g << 8) | b).toString(16).padStart(6, "0")}`;
}

/**
 * Aplica (ou remove) os tokens de acento no <html>. `accent=null` remove as sobreposições,
 * fazendo o sistema voltar ao padrão definido no index.css (respeitando claro/escuro).
 */
export function applyAccent(accent: string | null): void {
  const root = document.documentElement;
  if (isHexColor(accent)) {
    root.style.setProperty("--color-accent", accent);
    root.style.setProperty("--color-hover-accent", darken(accent));
  } else {
    root.style.removeProperty("--color-accent");
    root.style.removeProperty("--color-hover-accent");
  }
}
