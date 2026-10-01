# Design System — Quack (estrutura Rango)

Design system principal do Hórus/Quack PDV: **estrutura Rango** (tokens, tipografia Nunito, componentes
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
Fonte única **Nunito** (Google Fonts), pesos 400/600/700/800, fallback `"Segoe UI", system-ui, sans-serif`.
`--font-display` e `--font-body` já apontam para Nunito.

| Estilo | Tamanho/linha | Peso |
| --- | --- | --- |
| display | 32/38 | 800 |
| title | 24/30 | 800 |
| heading | 18/24 | 700 |
| body | 16/24 | 400 |
| body-sm | 14/20 | 400 |
| label | 14/16 | 700 |
| caption | 12/16 | 600 |

Raios: `--radius-sm` 8px, `--radius-md` 12px, `--radius-pill` 999px. Sombra flutuante: `--shadow-card`.

## Componentes
- Existentes: `btn-success`, `btn-cancel`, `card`, `input-field` (compartilham a paleta via `--color-*`).
- Novos (prefixo `rg-`, em `src/styles/components.css`): `rg-btn` (`--primary/--secondary/--text`), `rg-chip`,
  `rg-card`, `rg-badge`, `rg-star`, `rg-free`. Área de toque mínima 44px; foco 2px em `--brand-text`.

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
