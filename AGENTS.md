## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, use the installed graphify skill or instructions before doing anything else.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).

## Divisão de modelos (Codex / OpenSpec)

- O padrão deste projeto é `gpt-6-astra`, definido em `.codex/config.toml`. Arquitetura, revisão e integração ficam com o modelo principal Astra.
- Propose (`openspec-propose`, incluindo pedidos equivalentes a `/opsx:propose`) deve usar Astra e produzir apenas planejamento. Não escreva código durante propose. Se o principal estiver em outro modelo, delegue ao agente `openspec-propose` com `gpt-6-astra` e contexto novo.
- Prepare proposal.md, design.md e tasks.md com todo o contexto necessário à implementação, respeitando os artefatos exigidos pelo schema do OpenSpec.
- Apply (`openspec-apply-change`, incluindo pedidos equivalentes a `/opsx:apply`) deve ser delegado ao agente `openspec-apply`, modelo `gpt-6.1-sol`, com contexto novo. Passe a change, o store se houver, os caminhos dos artefatos, restrições e critérios de aceite. Aguarde o resultado, revise e integre antes de responder. O principal não executa a implementação do apply.
- Delegue tarefas simples e mecânicas ao agente `rapido`, modelo `gpt-6-luna`: formatação, renomeações, imports, boilerplate, comentários, docs e lint trivial. Não delegue decisões de negócio ou arquitetura a esse agente.
- Quando a ferramenta de delegação oferecer `fork_turns`, use `fork_turns="none"` e informe explicitamente o modelo e as instruções do respectivo arquivo `.codex/agents/*.toml`. Isso evita herdar o modelo e o histórico completos do principal. O executor já delegado não deve redelegar o mesmo fluxo, evitando recursão.
- Contexto separado não significa checkout separado: coordene as edições, sem agentes escrevendo simultaneamente nos mesmos arquivos. Preserve alterações preexistentes.
- Se o modelo exigido estiver indisponível, informe o bloqueio; não substitua silenciosamente por outro modelo.
- Mantenha esta política e os agentes fora das skills geradas pelo OpenSpec. Após `openspec init` ou `openspec update`, preserve `AGENTS.md`, `.codex/config.toml` e `.codex/agents/`. Não adicione `model`, `context: fork` ou `background` ao frontmatter das skills do Codex como mecanismo de seleção de modelo.
