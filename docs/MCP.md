# Servidor MCP do Quack PDV

Servidor [MCP (Model Context Protocol)](https://modelcontextprotocol.io) embutido na API do PDV. Ele permite que
um agente de IA — como o **Hermes** — consulte **relatórios, produtos, controle de validade e promoções** da loja
em linguagem natural.

| | |
|---|---|
| **Endereço (produção)** | `https://api-pdv.quacksistemas.com.br/mcp` |
| **Endereço (desenvolvimento)** | `http://localhost:5260/mcp` |
| **Transporte** | Streamable HTTP, sem sessão (*stateless*) — só `POST` |
| **Autenticação** | `Authorization: Bearer qmcp_...` (token de integração) |
| **Acesso** | **Somente leitura** — nenhuma ferramenta altera dados |
| **Escopo** | Uma empresa por token (a empresa de quem gerou o token) |
| **Ferramentas** | 13 (lista completa [abaixo](#4-ferramentas)) |

## Sumário

1. [Como funciona](#1-como-funciona)
2. [Início rápido](#2-início-rápido)
3. [Conectando o agente](#3-conectando-o-agente)
4. [Ferramentas](#4-ferramentas)
5. [Relatórios disponíveis](#5-relatórios-disponíveis)
6. [Convenções das respostas](#6-convenções-das-respostas)
7. [Exemplos de perguntas](#7-exemplos-de-perguntas)
8. [Segurança](#8-segurança)
9. [Solução de problemas](#9-solução-de-problemas)
10. [Para desenvolvedores](#10-para-desenvolvedores)

---

## 1. Como funciona

```
┌──────────┐   HTTPS POST /mcp            ┌───────────────────────────────┐      ┌────────────┐
│  Hermes  │ ───────────────────────────▶ │  API do PDV (.NET)            │ ───▶ │ SQL Server │
│ (agente) │   Authorization: Bearer      │  HorusAuthMiddleware          │      └────────────┘
└──────────┘   qmcp_...                   │   └─ valida o token           │
                                          │  PdvMcpTools (13 ferramentas) │
                                          │   └─ mesmos serviços das telas│
                                          └───────────────────────────────┘
```

- O servidor MCP **roda dentro da própria API**: não há processo, contêiner nem domínio novo para manter.
- As ferramentas chamam **os mesmos serviços** usados pelas telas do sistema (Relatórios, Produtos, Validade,
  Promoções). O número que o agente responde é o mesmo que aparece na tela.
- O agente se identifica com um **token de integração** gerado em **Configurações → Agente de IA (MCP)**. O token
  define de qual empresa são os dados; o agente nunca escolhe a empresa.

---

## 2. Início rápido

1. **Gere o token.** Entre no PDV como administrador ou gerente, abra **Configurações → Agente de IA (MCP)**,
   digite um nome (ex.: `Agente Hermes`) e clique em **Gerar token**.
2. **Copie o trecho de configuração** que aparece logo abaixo do token. O token é mostrado **uma única vez**.
3. **Cole no Hermes**, em `~/.hermes/config.yaml`, e rode `/reload-mcp` (ou reinicie o Hermes).

```yaml
mcp_servers:
  quack_pdv:
    url: "https://api-pdv.quacksistemas.com.br/mcp"
    headers:
      Authorization: "Bearer qmcp_COLE_SEU_TOKEN_AQUI"
```

Pronto. Pergunte ao Hermes, por exemplo: *"Quais produtos vencem nos próximos 7 dias?"*

---

## 3. Conectando o agente

### 3.1 Gerar, listar e revogar tokens

Tudo é feito em **Configurações → Agente de IA (MCP)** (visível para administrador e gerente):

- **Gerar**: dê um nome que identifique o agente. O token (`qmcp_` + 43 caracteres) aparece **só na hora**; o
  sistema guarda apenas o hash (SHA-256), então não há como recuperá-lo depois. Perdeu? Gere outro.
- **Listar**: mostra nome, início do token, quem criou, quando e o **último uso**.
- **Revogar**: o agente perde o acesso na requisição seguinte. Não há como desfazer; gere um token novo.

Recomendação: **um token por agente**. Assim dá para revogar um sem derrubar os outros e saber, pelo "último
uso", qual está ativo.

A mesma operação existe na API REST (sessão de administrador/gerente):

| Método | Rota | O que faz |
|---|---|---|
| `POST` | `/api/IntegracaoToken` | Gera um token. Corpo: `{ "nome": "Agente Hermes" }`. Devolve `data.token` (uma vez). |
| `GET` | `/api/IntegracaoToken` | Lista os tokens da empresa (sem o segredo). |
| `POST` | `/api/IntegracaoToken/{id}/revogar` | Revoga o token. |

### 3.2 Hermes

Arquivo `~/.hermes/config.yaml`:

```yaml
mcp_servers:
  quack_pdv:
    url: "https://api-pdv.quacksistemas.com.br/mcp"
    headers:
      Authorization: "Bearer qmcp_COLE_SEU_TOKEN_AQUI"
    timeout: 60          # segundos por chamada de ferramenta (relatórios grandes podem demorar)
    connect_timeout: 15  # segundos para conectar
```

- **Recarregar**: rode `/reload-mcp` dentro do Hermes depois de editar o arquivo.
- **Nome das ferramentas no Hermes**: `mcp_<servidor>_<ferramenta>`. Com o nome `quack_pdv` acima, a ferramenta
  `gerar_relatorio` aparece como `mcp_quack_pdv_gerar_relatorio`.
- **Limitar ferramentas** (opcional): se quiser expor só uma parte ao agente:

  ```yaml
      tools:
        include: [listar_relatorios, gerar_relatorio, buscar_produtos, resumo_validade, consultar_lotes]
  ```

- **Desligar sem apagar**: `enabled: false`.
- **Várias lojas**: cada empresa tem seu token. Declare um servidor por loja:

  ```yaml
  mcp_servers:
    pdv_loja_centro:
      url: "https://api-pdv.quacksistemas.com.br/mcp"
      headers: { Authorization: "Bearer qmcp_TOKEN_DA_LOJA_CENTRO" }
    pdv_loja_norte:
      url: "https://api-pdv.quacksistemas.com.br/mcp"
      headers: { Authorization: "Bearer qmcp_TOKEN_DA_LOJA_NORTE" }
  ```

> O token é uma senha. Mantenha o `config.yaml` com permissão restrita e nunca o envie para repositórios.

### 3.3 Outros clientes MCP

Qualquer cliente que fale MCP por HTTP funciona. Exemplos:

**Claude Code**

```bash
claude mcp add --transport http quack-pdv https://api-pdv.quacksistemas.com.br/mcp --header "Authorization: Bearer qmcp_COLE_SEU_TOKEN_AQUI"
```

**Formato JSON genérico** (Cursor, Claude Desktop com ponte HTTP, etc.)

```json
{
  "mcpServers": {
    "quack-pdv": {
      "type": "http",
      "url": "https://api-pdv.quacksistemas.com.br/mcp",
      "headers": { "Authorization": "Bearer qmcp_COLE_SEU_TOKEN_AQUI" }
    }
  }
}
```

### 3.4 Testar a conexão sem agente

Lista as ferramentas (deve devolver as 13):

```bash
curl -s https://api-pdv.quacksistemas.com.br/mcp -H "Authorization: Bearer qmcp_COLE_SEU_TOKEN_AQUI" -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

Chama uma ferramenta:

```bash
curl -s https://api-pdv.quacksistemas.com.br/mcp -H "Authorization: Bearer qmcp_COLE_SEU_TOKEN_AQUI" -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"resumo_validade","arguments":{}}}'
```

A resposta vem no formato de eventos (`event: message` / `data: {...}`); o JSON-RPC está na linha `data:`. O
cabeçalho `Accept` com os dois tipos é obrigatório pelo protocolo.

---

## 4. Ferramentas

Todas são **somente leitura** (`readOnlyHint: true`). Parâmetros sem "obrigatório" são opcionais.

| Grupo | Ferramenta | Para quê |
|---|---|---|
| Relatórios | [`listar_relatorios`](#listar_relatorios) | Catálogo dos 20 relatórios e seus filtros |
| Relatórios | [`gerar_relatorio`](#gerar_relatorio) | Gera um relatório |
| Produtos | [`buscar_produtos`](#buscar_produtos) | Busca/lista produtos (resumo) |
| Produtos | [`obter_produto`](#obter_produto) | Cadastro completo de um produto |
| Produtos | [`listar_categorias`](#listar_categorias) | Departamentos e subcategorias |
| Validade | [`resumo_validade`](#resumo_validade) | Panorama: vencidos, críticos, valor em risco |
| Validade | [`consultar_lotes`](#consultar_lotes) | Lotes de validade, com filtros |
| Validade | [`lotes_do_produto`](#lotes_do_produto) | Lotes de um produto |
| Validade | [`produtos_a_vencer`](#produtos_a_vencer) | Produtos que vencem em N dias |
| Validade | [`status_baixa_por_lote`](#status_baixa_por_lote) | Situação do FEFO e divergências |
| Validade | [`prazos_validade_por_categoria`](#prazos_validade_por_categoria) | Prazo padrão e alerta por categoria |
| Promoções | [`listar_promocoes`](#listar_promocoes) | Promoções (todas ou só as em vigor) |
| Promoções | [`obter_promocao`](#obter_promocao) | Detalhe, produtos e resultado de uma promoção |

### Relatórios

#### `listar_relatorios`

Sem parâmetros. Devolve, para cada relatório, `id`, `nome`, `descricao` e `filtros` (os parâmetros de
`gerar_relatorio` que ele aceita). O agente deve chamá-la antes de `gerar_relatorio` quando não souber o id.

#### `gerar_relatorio`

| Parâmetro | Tipo | Descrição |
|---|---|---|
| `relatorio` | texto, **obrigatório** | Id do relatório (ver [seção 5](#5-relatórios-disponíveis)). |
| `dataInicio` | `AAAA-MM-DD` | Início do período. Padrão: 30 dias atrás. |
| `dataFim` | `AAAA-MM-DD` | Fim do período, inclusive. Padrão: hoje. |
| `agruparPor` | texto | `vendas-periodo`: `diario` (padrão), `semanal` ou `mensal`. |
| `formaPagamento` | texto | Relatórios de vendas: `pix`, `debito`, `credito` ou `dinheiro`. |
| `categoriaId` | texto | Id de departamento ou subcategoria (de `listar_categorias`). Um departamento inclui as subcategorias. |
| `faixa` | texto | `vencimentos`: `todos`, `vencidos`, `7d`, `15d`, `30d`. `inadimplencia`: `todos`, `em_dia`, `atraso_30`, `atraso_60`. |
| `somenteSemEstoque` | booleano | `estoque-critico`: só produtos zerados (padrão: estoque até 5). |
| `buscaProduto` | texto | `lucro-markup-produtos`: trecho do código ou nome. |
| `horaInicio`, `horaFim` | `HH:mm` | `clientes-frequentes`: janela de horário. |
| `tipoEvento` | texto | `log-atividades`: tipo exato do evento. |
| `pagina` | inteiro | Página das linhas, a partir de 1. |
| `tamanhoPagina` | inteiro | Linhas por página, 1 a 200. Padrão 100. |

Filtros que não se aplicam ao relatório escolhido são ignorados.

Resposta:

```json
{
  "columns": [
    { "key": "periodo", "label": "Período" },
    { "key": "vendas", "label": "Vendas" },
    { "key": "faturamento", "label": "Faturamento" },
    { "key": "ticketMedio", "label": "Ticket médio" }
  ],
  "rows": [
    { "periodo": "10/2026", "vendas": 412, "faturamento": "R$ 38.540,10", "ticketMedio": "R$ 93,54" }
  ],
  "relatorio": "vendas-periodo",
  "totalLinhas": 1,
  "pagina": 1,
  "tamanhoPagina": 100,
  "temMais": false
}
```

`columns` dá o rótulo de cada campo de `rows`. O relatório `lucro-markup-produtos` traz ainda `totalRow`
(linha de totais), `summary` (resumo) e `notes` (avisos, ex.: custo estimado).

### Produtos

#### `buscar_produtos`

| Parâmetro | Tipo | Descrição |
|---|---|---|
| `busca` | texto | Trecho do nome, código, código de barras (GTIN), marca ou fornecedor. Ignora acentos e maiúsculas (`acucar` acha `Açúcar`). |
| `categoriaId` | texto | Departamento ou subcategoria. |
| `somenteEstoqueBaixo` | booleano | Estoque menor ou igual ao estoque mínimo cadastrado. |
| `somenteSemEstoque` | booleano | Estoque zero ou negativo. |
| `pagina` | inteiro | A partir de 1. |
| `tamanhoPagina` | inteiro | 1 a 200. Padrão 50. |

Sem `busca`, lista todo o cadastro em ordem alfabética. Resposta:

```json
{
  "total": 1,
  "pagina": 1,
  "tamanhoPagina": 50,
  "temMais": false,
  "itens": [
    {
      "id": "prd-...", "codigo": "1002", "nome": "Iogurte Natural",
      "categoria": "Laticínios", "categoriaId": "6638d121-...",
      "fornecedor": "", "marca": null, "gtin": "SEM GTIN",
      "estoque": "40", "estoqueMinimo": "5",
      "custo": "2,00", "precoVenda": "4,00", "lucro": "2,00",
      "controlaValidade": true, "dataValidade": "2026-10-23", "diasParaVencer": 10
    }
  ]
}
```

#### `obter_produto`

| Parâmetro | Tipo | Descrição |
|---|---|---|
| `idOuCodigo` | texto, **obrigatório** | Id do produto, código interno ou GTIN **exato**. |

Devolve o cadastro completo: estoque (atual, mínimo, máximo, localização), custos (unitário, médio, com e sem
imposto), preço de venda, lucro, margem desejada, markup, desconto máximo, comissão, unidades e conversão, marca
e fabricante, peso e dimensões, validade e dados fiscais (NCM, CEST, CFOP, CST/CSOSN, alíquotas). A imagem do
produto não é enviada.

#### `listar_categorias`

| Parâmetro | Tipo | Descrição |
|---|---|---|
| `apenasAtivas` | booleano | Só categorias ativas. |

Devolve a árvore: cada departamento com `id`, `nome`, `ativa`, `quantidadeProdutos` e `subcategorias`. Os `id`
servem no filtro `categoriaId` das outras ferramentas.

### Controle de validade

O sistema controla validade **por lote** (cada entrada de mercadoria com sua data) e mantém também uma data de
validade no cadastro do produto. Para respostas precisas prefira as ferramentas de lote.

Faixas de um lote:

| Faixa | Significado |
|---|---|
| `esgotado` | Lote sem saldo (tem prioridade sobre as demais faixas). |
| `vencido` | Data de validade já passou. |
| `critico` | No terço final da janela de alerta (no mínimo 2 dias antes do vencimento). |
| `atencao` | Dentro da janela de alerta, ainda fora do trecho crítico. |
| `ok` | Fora da janela de alerta. |

A janela de alerta (`janelaAlertaDias`) vem do alerta configurado na categoria; na falta dele, do alerta do
produto; e, por fim, de 15 dias (ver `prazos_validade_por_categoria`). Exemplo: com janela de 15 dias, o lote
fica em `atencao` de 15 a 6 dias do vencimento e em `critico` de 5 a 0.

#### `resumo_validade`

Sem parâmetros. Resposta:

```json
{
  "lotes": {
    "vencidos": 0, "criticos": 0, "atencao": 1,
    "valorEmRisco": 80.00,
    "produtosSemLote": 0,
    "modoBaixaPorLote": "sombra"
  },
  "produtos": {
    "vencidos": 0, "venceEm7Dias": 0, "venceEm15Dias": 1, "venceEm30Dias": 1,
    "totalControlados": 1, "semDataInformada": 0
  }
}
```

- `valorEmRisco`: saldo estimado × custo unitário dos lotes em alerta.
- `produtosSemLote`: produtos que controlam validade mas não têm lote com saldo.

#### `consultar_lotes`

| Parâmetro | Tipo | Descrição |
|---|---|---|
| `busca` | texto | Nome do produto, código do produto ou número do lote. |
| `validadeDe` | `AAAA-MM-DD` | Validade a partir de (inclusive). |
| `validadeAte` | `AAAA-MM-DD` | Validade até (inclusive). |
| `categoriaId` | texto | Departamento ou subcategoria. |
| `faixa` | texto | `vencido`, `critico`, `atencao`, `ok` ou `esgotado`. Vazio = todas. |
| `somenteComSaldo` | booleano | Só lotes com saldo estimado maior que zero. |
| `origem` | texto | `nfe`, `compra`, `ajuste`, `cadastro`, `manual` ou `inicial`. |
| `pagina` | inteiro | A partir de 1. |
| `tamanhoPagina` | inteiro | 1 a 200. Padrão 50. |

Resposta: `total`, `pagina`, `tamanhoPagina`, `temMais`, `valorEmRisco` (de todos os lotes filtrados),
`modoBaixaPorLote` e `itens`. Cada item:

| Campo | Descrição |
|---|---|
| `id`, `produtoId` | Identificadores do lote e do produto. |
| `productCode`, `productName` | Código e nome do produto. |
| `categoriaNome`, `categoriaId`, `categoriaPaiId` | Categoria do produto. |
| `numeroLote` | Número do lote. |
| `dataValidade` | `AAAA-MM-DD`. |
| `diasParaVencer` | Negativo quando já venceu. |
| `qtdInicial`, `qtdEstimada` | Quantidade na entrada e saldo estimado atual. |
| `faixa`, `janelaAlertaDias` | Faixa de alerta e a janela usada. |
| `custoUnitario`, `precoVenda`, `valorEmRisco` | Valores numéricos (não formatados). |
| `origem` | De onde veio o lote. |
| `validadePadrao` | `true` se a data foi sugerida pelo prazo da categoria, não digitada. |
| `criadoEm` | Data de registro do lote. |

#### `lotes_do_produto`

| Parâmetro | Tipo | Descrição |
|---|---|---|
| `produtoId` | texto, **obrigatório** | Campo `id` devolvido por `buscar_produtos`. |

Lista os lotes do produto (número, validade, dias para vencer, quantidade inicial, saldo estimado, origem).

#### `produtos_a_vencer`

| Parâmetro | Tipo | Descrição |
|---|---|---|
| `dias` | inteiro | Janela a partir de hoje, 1 a 365. Padrão 15. |

Usa a data de validade **do cadastro do produto** (inclui os já vencidos). Devolve `dias`, `total` e `itens`
(`id`, `codigo`, `nome`, `categoria`, `estoque`, `custo`, `precoVenda`, `dataValidade`, `diasParaVencer`).

#### `status_baixa_por_lote`

Sem parâmetros. Situação do FEFO (*first expired, first out* — vence primeiro, sai primeiro):

- `modo`: `desligado`, `sombra` (saldo dos lotes é estimado) ou `ativo` (a venda baixa o lote de verdade).
- `lotesComSaldo`, `lotesComDivergencia`, `produtosComDivergencia`, `produtosComEstoqueSemLote`.
- `itens`: até 100 maiores divergências entre saldo real e estimado.

#### `prazos_validade_por_categoria`

Sem parâmetros. Para cada categoria: `categoriaId`, `nome`, `categoriaPaiId`, `prazoPadraoDias` (validade
sugerida na entrada) e `diasAlerta` (antecedência do alerta). `null` = não configurado.

### Promoções

Tipos de promoção: `desconto_percentual`, `desconto_valor`, `preco_fixo`, `leve_x_pague_y`, `combo_quantidade`
e `preco_atacado`.

#### `listar_promocoes`

| Parâmetro | Tipo | Descrição |
|---|---|---|
| `somenteAtivas` | booleano | Só as em vigor agora (ativas e dentro da vigência). |

Devolve `total` e `itens` com `id`, `nome`, `tipo`, `valorDesconto`, `precoFixo`, `quantidadeLeva`,
`quantidadePaga`, `quantidadeMinima`, `inicioVigencia`, `fimVigencia`, `ativa`, `categoriaId`, `categoria`,
`quantidadeProdutos`, `criadoPor` e `criadoEm`.

#### `obter_promocao`

| Parâmetro | Tipo | Descrição |
|---|---|---|
| `id` | texto, **obrigatório** | Campo `id` devolvido por `listar_promocoes`. |

Devolve as regras da promoção e mais:

- `produtos`: lista com `id`, `codigo`, `nome` e `precoVenda` de cada produto participante.
- `resultado`: `quantidadeVendas`, `receitaBruta`, `receitaLiquida`, `descontoTotal` e `margemLiquida`.

---

## 5. Relatórios disponíveis

Período padrão quando `dataInicio`/`dataFim` não são informados: **últimos 30 dias**. Vendas canceladas não
entram nos relatórios de vendas.

"Filtros de vendas" = `dataInicio`, `dataFim`, `formaPagamento`, `categoriaId`.

### Vendas

| Id | O que mostra | Filtros | Colunas |
|---|---|---|---|
| `vendas-periodo` | Vendas por dia, semana ou mês | filtros de vendas, `agruparPor` | período, vendas, faturamento, ticket médio |
| `historico-vendas` | Cada item vendido | filtros de vendas | venda, data, cliente, pagamento, produto, qtd., total |
| `vendas-por-horario` | Vendas por hora do dia | filtros de vendas | horário, vendas, faturamento, ticket médio |
| `vendas-por-pagamento` | Participação de cada forma de pagamento | filtros de vendas | forma de pagamento, vendas, faturamento, ticket médio, % do total |
| `clientes-frequentes` | Ranking de clientes por nº de compras | filtros de vendas, `horaInicio`, `horaFim` | cliente, compras, gasto total, ticket médio |
| `cancelamentos` | Vendas canceladas | `dataInicio`, `dataFim` | venda, data da venda, data do cancelamento, valor, cliente, pagamento, operador, supervisor, motivo, itens |

### Produtos e lucratividade

| Id | O que mostra | Filtros | Colunas |
|---|---|---|---|
| `produtos-mais-vendidos` | Ranking por quantidade | filtros de vendas | código, produto, qtd. vendida, faturamento |
| `produtos-por-valor` | Ranking por faturamento | filtros de vendas | código, produto, qtd., faturamento, custo (CMV), lucro (R$), lucro (%) |
| `produtos-por-quantidade` | Ranking por quantidade, com lucro | filtros de vendas | idem |
| `produtos-por-lucro` | Ranking por lucro em reais | filtros de vendas | idem |
| `produtos-por-margem` | Ranking por margem % | filtros de vendas | idem |
| `lucro-markup-produtos` | Compra e venda médias, lucro bruto e markup | `dataInicio`, `dataFim`, `categoriaId`, `buscaProduto` | código, descrição, compra média, venda média, lucro bruto, markup (%), quantidade, custo total, total vendido, base do custo |
| `margem-por-categoria` | Margem por departamento | filtros de vendas | departamento, qtd. vendida, receita, CMV, margem (R$), margem (%) |

### Estoque e validade

| Id | O que mostra | Filtros | Colunas |
|---|---|---|---|
| `estoque-critico` | Produtos com estoque até 5 (ou zerados) | `somenteSemEstoque`, `categoriaId` | código, produto, categoria, estoque, preço venda, status |
| `movimento-estoque` | Posição de estoque valorizada | `categoriaId` | código, produto, categoria, estoque atual, custo unitário, valor em estoque |
| `compras-fornecedor` | Estoque agrupado por fornecedor | — | fornecedor, produtos, unidades, custo em estoque |
| `vencimentos` | Produtos com validade controlada | `faixa`, `categoriaId` | código, produto, categoria, validade, dias restantes, status, qtd. estoque, custo em estoque |

### Financeiro e auditoria

| Id | O que mostra | Filtros | Colunas |
|---|---|---|---|
| `inadimplencia` | Clientes devedores (fiado) | `faixa` | cliente, CPF/CNPJ, contato, limite, saldo devedor, última compra, dias sem pgto, status |
| `desempenho-caixa` | Sessões de caixa | `dataInicio`, `dataFim` | abertura, fechamento, operador, valor abertura, valor fechamento, status |
| `log-atividades` | Auditoria de ações | `dataInicio`, `dataFim`, `tipoEvento` | data/hora, usuário, evento, entidade, descrição, IP |

> A lista oficial é sempre a devolvida por `listar_relatorios`.

---

## 6. Convenções das respostas

- **Formato**: toda ferramenta devolve um texto JSON.
- **Datas**: entrada sempre `AAAA-MM-DD`; horas `HH:mm`. Formato diferente gera erro explicando o esperado.
- **Dinheiro**: nos relatórios vem formatado (`"R$ 1.234,56"`); em `buscar_produtos`/`obter_produto` vem como
  texto no padrão brasileiro (`"1.234,56"`); nos lotes e promoções vem numérico (`1234.56`).
- **Paginação**: listas grandes devolvem `total`/`totalLinhas`, `pagina`, `tamanhoPagina` e `temMais`. Com
  `temMais: true`, chame de novo com `pagina + 1`. O limite é 200 itens por página.
- **Erros**: vêm como resultado de ferramenta com `isError: true` e uma mensagem em português dizendo o que
  corrigir (ex.: *"Relatório 'x' não existe. Use listar_relatorios para ver os ids válidos."*).
- **Empresa**: nenhuma ferramenta recebe a empresa por parâmetro; ela vem do token.

---

## 7. Exemplos de perguntas

| Pergunta ao agente | Ferramentas que ele tende a usar |
|---|---|
| "Quanto vendi este mês, dia a dia?" | `gerar_relatorio` (`vendas-periodo`) |
| "Compare o faturamento de setembro e outubro." | `gerar_relatorio` (`vendas-periodo`, `agruparPor: mensal`) |
| "Quais os 10 produtos que mais dão lucro?" | `gerar_relatorio` (`produtos-por-lucro`, `tamanhoPagina: 10`) |
| "Qual a margem do departamento de bebidas?" | `listar_categorias` → `gerar_relatorio` (`margem-por-categoria`) |
| "Qual horário vende mais?" | `gerar_relatorio` (`vendas-por-horario`) |
| "Quanto entrou em Pix na semana passada?" | `gerar_relatorio` (`vendas-por-pagamento`) |
| "O que está acabando no estoque?" | `gerar_relatorio` (`estoque-critico`) ou `buscar_produtos` (`somenteEstoqueBaixo`) |
| "Qual o preço e o estoque do arroz 5kg?" | `buscar_produtos` → `obter_produto` |
| "O que vence nos próximos 7 dias e quanto vale?" | `resumo_validade` → `consultar_lotes` |
| "Tem algum lote vencido de laticínios?" | `listar_categorias` → `consultar_lotes` (`faixa: vencido`) |
| "Quais promoções estão valendo hoje?" | `listar_promocoes` (`somenteAtivas`) |
| "A promoção do iogurte deu resultado?" | `listar_promocoes` → `obter_promocao` |
| "Quem está devendo há mais de 60 dias?" | `gerar_relatorio` (`inadimplencia`, `faixa: atraso_60`) |
| "Teve cancelamento de venda ontem? Por quê?" | `gerar_relatorio` (`cancelamentos`) |

Dica de *prompt* para o agente: *"Use as ferramentas do Quack PDV. Antes de afirmar que não há dados, confira
`temMais` e o período consultado."*

---

## 8. Segurança

- **Somente leitura.** O servidor não tem ferramenta que crie, altere ou apague dados. Além disso, o token de
  integração **só é aceito em `/mcp`**: usado em qualquer rota REST (`/api/...`), a API responde `403`. Um token
  vazado não consegue alterar preço, estoque, promoção nem nada.
- **Isolamento por empresa.** A empresa vem do token validado no servidor; um token nunca enxerga dados de outra
  empresa.
- **Segredo não armazenado.** O banco guarda apenas o SHA-256 do token (tabela `IntegracaoTokens`). O texto do
  token existe uma única vez, na tela de geração.
- **Revogação imediata.** Revogar em Configurações corta o acesso na requisição seguinte.
- **Sem cookie.** O `/mcp` ignora o cookie de sessão do navegador e só lê o cabeçalho `Authorization`. Sessão de
  usuário (JWT) também não entra no `/mcp`.
- **Empresa bloqueada.** Se a empresa não estiver aprovada (pendente, rejeitada ou bloqueada), o `/mcp`
  responde `403`.
- **Limite de requisições.** O `/mcp` passa pelo mesmo limite da API: por padrão 240 requisições por minuto por
  IP (`Security:RateLimit`). Acima disso, `429` com `Retry-After`.
- **O que o agente enxerga.** Dados comerciais e de gestão: vendas, clientes (nome, CPF/CNPJ e contato nos
  relatórios de clientes e inadimplência), custos, margens e o log de auditoria (com usuário e IP). Dê o token
  apenas a agentes e pessoas que podem ver esses dados, e considere que o conteúdo das respostas é enviado ao
  modelo de IA usado pelo agente.
- **HTTPS.** Em produção use sempre o endereço `https://`; o token trafega no cabeçalho.

---

## 9. Solução de problemas

| Sintoma | Causa provável | O que fazer |
|---|---|---|
| `401` — *"Use um token de integração (Authorization: Bearer qmcp_...)"* | Cabeçalho ausente, ou foi enviado um JWT/token do Gateway. | Confira o `headers.Authorization` do agente. O valor é `Bearer ` + token começando com `qmcp_`. |
| `401` — *"Token de integração inválido ou revogado."* | Token digitado errado, cortado ou revogado. | Gere um token novo em Configurações e atualize o agente. |
| `403` — *"O token de integração só é aceito no servidor MCP (/mcp)."* | O token foi usado numa rota `/api/...`. | Use o endereço terminado em `/mcp`. |
| `403` — mensagem sobre a empresa (aguarda aprovação, bloqueada...) | Situação cadastral da empresa. | Regularize a empresa com a administração do sistema. |
| `404` em `/mcp` | API em versão anterior à do MCP. | Atualize a API (imagem/deploy). |
| `405` ou `406` | Requisição `GET`, ou sem `Accept: application/json, text/event-stream`. | Clientes MCP fazem isso sozinhos; no `curl`, use `POST` e o cabeçalho `Accept`. |
| `429` | Limite de requisições excedido. | Aguarde o tempo do `Retry-After`; se for rotina, aumente `Security:RateLimit:MaxRequests`. |
| Ferramentas não aparecem no Hermes | Configuração não recarregada ou YAML mal indentado. | Rode `/reload-mcp`; confira a indentação (`mcp_servers` → nome → `url`/`headers`). |
| Relatório vem vazio | Período padrão (30 dias) sem movimento, ou filtro restritivo. | Informe `dataInicio`/`dataFim`; remova `categoriaId`/`formaPagamento`. |
| Agente diz que "só existem N itens" | Viu apenas a primeira página. | Verifique `temMais` e peça as próximas páginas. |
| Chamada demora ou estoura o tempo | Relatório com muito histórico. | Reduza o período; aumente `timeout` no `config.yaml`. |
| O card não aparece em Configurações | Usuário não é administrador nem gerente. | Entre com um desses perfis. |

---

## 10. Para desenvolvedores

### Arquivos

| Arquivo | Papel |
|---|---|
| `API/NETCORE/Services/Mcp/PdvMcpTools.cs` | As 13 ferramentas. |
| `API/NETCORE/Services/Mcp/McpReportCatalog.cs` | Catálogo dos relatórios (ids, descrições, filtros). |
| `API/NETCORE/Program.cs` | Registro do servidor (`AddMcpServer`) e da rota (`MapMcp("/mcp")`). |
| `API/NETCORE/Middlewares/HorusAuthMiddleware.cs` | Autenticação do `/mcp` pelo token de integração. |
| `API/NETCORE/Repositories/DatabaseAccess/IntegracaoTokenAB.cs` | Gerar, listar, revogar e validar tokens. |
| `API/NETCORE/Controllers/Integracao/IntegracaoTokenController.cs` | REST de gestão dos tokens. |
| `API/NETCORE/DataBase/Migrations/48_integracao_tokens.sql` | Tabela `IntegracaoTokens`. |
| `FRONTEND/src/components/SettingsPage/IntegracaoTokensCard.tsx` | Card "Agente de IA (MCP)" em Configurações. |
| `FRONTEND/src/services/api/integracaoTokenService.ts` | Cliente REST dos tokens. |

Pacote usado: [`ModelContextProtocol.AspNetCore`](https://www.nuget.org/packages/ModelContextProtocol.AspNetCore)
(SDK oficial C#).

### Decisões de projeto

- **Embutido na API**, não em serviço separado: reaproveita serviços, isolamento por empresa, limite de
  requisições e telemetria; sem imagem ou domínio extra.
- **Stateless** (`options.Stateless = true`): cada chamada é uma requisição HTTP independente, autenticada pelo
  middleware. Funciona com mais de uma réplica da API sem afinidade de sessão.
- **Token próprio** (`qmcp_`), separado do token do Gateway (`qgw_`) e do JWT de usuário: escopos diferentes não
  se misturam. O perfil técnico `integracao` (`HorusRoles.Integracao`) não é aceito por nenhum
  `[HorusAuthorizeRoles]`.
- **Respostas enxutas**: `buscar_produtos` devolve um resumo e pagina, porque o cadastro completo (milhares de
  produtos × dezenas de campos) não cabe no contexto de um agente.
- **Retorno em texto JSON** serializado pela própria ferramenta: controle total do formato e acentos legíveis.

### Deploy

- **Banco**: a migration `48_integracao_tokens.sql` roda sozinha no boot da API (idempotente).
- **Infra**: nada a configurar. O `/mcp` sai pelo mesmo host e roteador do Traefik da API.
- **Frontend**: nova variável `VITE_INTEGRACAO_TOKEN_API_URL` (já incluída em todos os `.env*`). O endereço do
  MCP mostrado na tela é derivado dela.

### Adicionar uma ferramenta

1. Em `PdvMcpTools.cs`, crie um método público com `[McpServerTool(Name = "...", ReadOnly = true, ...)]` e
   `[Description]` no método e em cada parâmetro. As descrições são o que o agente lê para decidir usar a
   ferramenta — escreva-as para ele.
2. Pegue a empresa com `CurrentCompanyId()`. **Nunca** aceite empresa como parâmetro.
3. Mantenha **somente leitura**. Uma ferramenta de escrita muda o modelo de segurança deste servidor (hoje o
   token é seguro por não conseguir alterar nada) e exige rever token, permissões e auditoria antes.
4. Pagine listas que possam crescer e devolva `total`/`temMais`.
5. Para erro que o agente deve entender, lance `McpException` com mensagem clara; `InvalidOperationException`
   dos serviços deve ser convertida.
6. Atualize este documento.

Relatório novo em `RelatorioAB.GerarAsync`? Inclua-o em `McpReportCatalog.cs` (senão `gerar_relatorio` o recusa).

### Testar localmente

1. Suba a API (`dotnet run` em `API/NETCORE`, porta 5260) e o frontend.
2. Gere um token em Configurações → Agente de IA (MCP).
3. Use os comandos `curl` da [seção 3.4](#34-testar-a-conexão-sem-agente) trocando o endereço por
   `http://localhost:5260/mcp`, ou o MCP Inspector:

   ```bash
   npx @modelcontextprotocol/inspector
   ```

   Escolha transporte *Streamable HTTP*, URL `http://localhost:5260/mcp` e adicione o cabeçalho `Authorization`.

Casos que devem continuar valendo ao mexer no código:

| Caso | Esperado |
|---|---|
| `/mcp` sem token, com JWT de usuário, com cookie de sessão ou com token inexistente | `401` |
| Token de integração em `/api/Produto` ou `/api/IntegracaoToken` | `403` |
| Token revogado | `401` |
| `tools/list` | 13 ferramentas, todas com `readOnlyHint: true` |
| Token de outra empresa | Não enxerga os dados |
| Os 20 relatórios do catálogo | Geram sem erro |

### Limitações conhecidas

- `buscar_produtos` e `obter_produto` carregam o cadastro inteiro da empresa a cada chamada (mesmo custo do
  `GET /api/Produto` usado pelas telas) e filtram em memória.
- Os relatórios de vendas leem todo o histórico de vendas da empresa e filtram o período em memória (é assim
  também na tela de Relatórios). Em bases muito grandes, prefira períodos curtos e aumente o `timeout` do agente.
- Não há ferramentas de clientes, fornecedores, caixa em tempo real ou notas fiscais; os dados dessas áreas só
  aparecem pelos relatórios listados.
