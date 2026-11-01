# Design System — Quack (estrutura Rango)

Design system principal do Hórus/Quack PDV: **estrutura Rango** (tokens, tipografia Inter + Manrope, componentes
`rg-`, regras) com as **cores da marca Quack** — marinho `#152238`, amarelo `#ffc233` e laranja `#f97316`.

> Fonte da verdade no código: `FRONTEND/src/index.css` (tokens Tailwind `--color-*`, consumidos por todo
> o app) e `FRONTEND/src/styles/tokens.css` (nomes Rango `--brand/--surface/--ink…`, em sincronia). Nunca
> use cor, tamanho ou raio fora dos tokens.

## Princípios
- **Marinho é a base, amarelo/laranja é o acento.** A ação primária é marinho (`--brand`/`--color-accent`)
  com texto branco; amarelo (`--highlight`) marca selos, estrelas e destaques; laranja é o acento quente.
- **Uma ação primária por tela.**
- **Redondo e próximo:** `--radius-md` (12px) em botões e cards, `--radius-pill` em chips e selos.
- **Claro e direto:** metadados em `--ink-muted`.

Voz: português do Brasil, segunda pessoa, frases curtas; botões no imperativo. Valores "R$ 0,00".

## Cores (tokens Rango → Quack)

| Token Rango | `--color-*` equivalente | Claro | Escuro |
| --- | --- | --- | --- |
| `--brand` | `--color-accent` / `--color-secondary` | #152238 | (texto) #ffc233 · (preenchido) #1e3a5f |
| `--brand-pressed` | `--color-hover-accent` | #0c1726 | #284d7e |
| `--on-brand` | `--color-action-on-accent` | #ffffff | #ffffff / #152238 |
| `--surface` | `--color-bg-light` | #ffffff | #16243a |
| `--surface-muted` | `--color-bg-gray-theme` | #eef0f3 | #1e2d44 |
| `--border` | `--color-border-primary` | #e5e7eb | #2a3a54 |
| `--ink` | `--color-text-primary` | #152238 | #f2f4f8 |
| `--ink-muted` | `--color-text-secondary` | #5a6472 | #aeb8c8 |
| `--success` | `--color-success` | #2e7d4f | #5cc98a |
| `--highlight` | `--color-highlight` | #ffc233 | #ffc233 (texto sempre #152238) |
| `--highlight-strong` | `--color-highlight-strong` | #f97316 | #fb8a3c |

O administrador pode sobrepor a cor de acento por empresa (sistema e frente de caixa) em Configurações —
ver `EmpresaTema` / `companyTheme`.

## Tipografia
**Inter** no texto e **Manrope** nos títulos e números de destaque (desde 2026-11). Fallback
`"Segoe UI", system-ui, sans-serif`.

- **Web** (`FRONTEND/src/index.css`): Google Fonts — Inter 400/500/600/700 e Manrope 600/700/800.
  `--font-body` = Inter, `--font-display` = Manrope (h1–h3); `--font-sans` (tokens Rango) = Inter.
- **Apps JavaFX** (Quack Estoque e Caixa): fontes **estáticas** embutidas em `src/main/resources/fonts/`
  (o JavaFX não lê fontes variáveis), carregadas pelo `FontLoader` antes de qualquer tela, e a escala em
  `src/main/resources/css/typography.css` (aplicado antes do tema `quack.css`). Como `-fx-font-weight` só
  distingue normal/bold, os pesos 500/600 vêm pelo nome da família: `"Inter Medium"`, `"Inter SemiBold"`,
  `"Manrope SemiBold"`; `"Inter"` + bold e `"Manrope"` + bold usam os arquivos Bold.

| Estilo (classe JavaFX) | Fonte | Tamanho | Cor |
| --- | --- | --- | --- |
| h1 (`.h1`) | Manrope Bold | 28 | texto primário |
| h2 (`.h2`) | Manrope SemiBold | 22 | texto primário |
| h3 (`.h3`) | Inter SemiBold | 18 | texto primário |
| subtítulo (`.subtitle`) | Inter Medium | 16 | `#374151` |
| corpo (`.body`) | Inter Regular | 14 | texto primário |
| legenda (`.caption`) | Inter Regular | 12 | texto terciário |
| destaque (`.label-strong`) | Inter SemiBold | 14 | texto primário |
| KPI valor (`.kpi-value`) | Manrope Bold | 30 | texto primário |
| KPI rótulo (`.kpi-label`) | Inter Medium, maiúsculas | 12 | texto terciário |
| número em tabela (`.mono-number`) | Inter, alinhado à direita | — | — |

Cores de texto (nunca preto puro): primária `#1F2937`, secundária `#4B5563`, terciária `#6B7280`,
desabilitada `#9CA3AF` (`-text-primary/-secondary/-tertiary/-disabled` no JavaFX; `-ink`/`-ink-muted` do tema
apontam para elas).

**Impressos** (etiquetas, cupom/DANFE, pedido/cotação e relatórios impressos dos apps) continuam em Nunito e
Roboto Mono: os tamanhos estão ajustados ao papel.

Raios: `--radius-sm` 8px, `--radius-md` 12px, `--radius-pill` 999px. Sombra flutuante: `--shadow-card`.

## Componentes
- Existentes: `btn-success`, `btn-cancel`, `card`, `input-field` (compartilham a paleta via `--color-*`).
- Novos (prefixo `rg-`, em `src/styles/components.css`): `rg-btn` (`--primary/--secondary/--text`), `rg-chip`,
  `rg-card`, `rg-badge`, `rg-star`, `rg-free`, `rg-offline-banner` (faixa amarela de aviso de modo offline). Área de toque mínima 44px; foco 2px em `--brand-text`.
- Administração: `rg-admin-surface` mantém campos com altura mínima de 44px, placeholders legíveis e números tabulares;
  `rg-admin-skip-link` permite ir direto ao conteúdo. `TableScrollArea` (`rg-admin-table`) fornece uma região nomeada,
  foco e orientação para rolar tabelas. `ListState` distingue carregamento, erro, lista vazia e ausência de resultados.

## Logos (em `FRONTEND/public/`)
- `quack-logo-v5.png` — wordmark horizontal (texto marinho), para superfícies claras.
- `quack-logo-negativo-v5.png` — wordmark branco, para fundos escuros/marinho.
- `quack-icon-v5.png` — ícone do app (marinho arredondado), para favicon/manifest e placeholders quadrados.

## Regras
1. Tema escuro via `data-theme="dark"` no `<html>`; padrão claro.
2. Toda cor/fonte/espaço/raio vem de token; valor solto é erro.
3. Texto sobre `--highlight` (amarelo) sempre `#152238`, nos dois temas.
4. Apenas uma ação primária por tela; foco visível 2px.
5. Componente novo: reutilize tokens, prefixe com `rg-` e documente aqui.
6. A janela de impressão dos relatórios usa Arial com fallback sans-serif para funcionar sem baixar fontes;
   essa exceção se limita ao documento impresso. A interface usa Inter + Manrope.
