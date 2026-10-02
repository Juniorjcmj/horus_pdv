## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).

## Design system
Use sempre `docs/design-system.md` (Quack sobre estrutura Rango). Cores, fontes, espaçamentos e raios só
via tokens CSS: `FRONTEND/src/index.css` (`--color-*`, Tailwind) e `FRONTEND/src/styles/tokens.css`
(nomes Rango `--brand/--surface/--ink…`). Componentes novos usam o prefixo `rg-` (`src/styles/components.css`).

## Gateway — instalador para download (IMPORTANTE)
O sistema oferece, em Configurações → Local Gateway, o download do instalador completo em
`FRONTEND/public/gateway/quack-gateway-completo.zip` (programa publicado + scripts + checklist).
**Sempre que você alterar qualquer coisa em `GATEWAY/src/`, regenere esse instalador** rodando
`GATEWAY/build-installer.sh` e **commite** o `quack-gateway-completo.zip` atualizado — senão o
download fica desatualizado em relação ao código do Gateway. (No ambiente de nuvem sem `dotnet`
nativo, rode o script dentro do container SDK 8.0 — ver cabeçalho do próprio script.)
