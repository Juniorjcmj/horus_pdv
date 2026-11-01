# Quack Estoque (JavaFX)

Aplicativo desktop de estoque do Hórus PDV: Java 21 + JavaFX 21, falando com a mesma API .NET do caixa
(`/api/Auth`, `/api/Produto`, `/api/NfeImport`, `/api/Inventario`, `/api/Estoque/relatorios`, `/api/OrdemCompra`...).
É **online** (sem modo offline): só guarda localmente a configuração, rascunhos e a fila de etiquetas.

Funções: entrada de mercadoria (NF-e por XML ou chave, e manual), ajustes avulsos (entrada, saída, uso interno, perda) com
histórico, **inventário** (contagem com leitor de código de barras, divergências e acerto com senha do gerente), cadastro de
produtos/categorias/fornecedores, **etiquetas de gôndola** na térmica do Windows e **compras** (sugestão de reposição por giro
que vira pedido por fornecedor), com relatórios (curva ABC, crítico, parado, validade, perdas) em CSV e impressão.

## Requisitos

- Java 21 (JDK para compilar, JRE para rodar) e Maven 3.9+ (sempre offline: `mvn -o`).
- API do Hórus PDV acessível. Padrão: produção `https://api-pdv.quacksistemas.com.br`; para testes locais use
  `http://localhost:5260` no link "Servidor" da tela de login. **Publique a API antes** (a migração 56 cria as tabelas do inventário).

## Build e execução

```
mvn -o -f ESTOQUE_JAVAFX/pom.xml package        # testes + fat-jar em target/quack-estoque.jar
java -jar ESTOQUE_JAVAFX/target/quack-estoque.jar  # ou run.bat
mvn -o -f ESTOQUE_JAVAFX/pom.xml test            # só os testes (os E2E ficam de fora sem -Dquack.e2e.url)
```

Testes ponta a ponta contra o Docker de teste (`pdv-teste-api` em 5260 e `pdv-teste-sql`; usuários `caixa@teste.com` /
`Teste@12345` e `operadora@teste.com` / `Caixa@12345`; o usuário estoquista é criado pelos próprios testes):

```
mvn -o -f ESTOQUE_JAVAFX/pom.xml test -Dquack.e2e.url=http://localhost:5260
mvn -o -f ESTOQUE_JAVAFX/pom.xml test -Dquack.e2e.url=http://localhost:5260 -Dtest=TelasE2ETest -Dquack.test.png=<pasta>   # PNG das telas
```

`-Dquack.home=<pasta>` troca a pasta de dados. O aviso "Unsupported JavaFX configuration" ao iniciar o fat-jar é inofensivo.

## Executável Windows (QuackEstoque.exe)

`powershell -ExecutionPolicy Bypass -File build-exe.ps1 [-Dest <pasta>]` compila, testa e gera com o `jpackage`:

- `dist\QuackEstoque\QuackEstoque.exe`: o programa, com o Java embutido (a loja **não** precisa ter Java);
- `dist\QuackEstoque-1.0.0-windows.zip` (~55 MB): para levar ao depósito, descompactar e abrir o `.exe`.

O script não roda com o `.exe` aberto a partir de `dist\` (feche o programa ou use `-Dest`).

## Tipografia (Inter + Manrope)

- Fontes **estáticas** em `src/main/resources/fonts/` (Inter Regular/Medium/SemiBold/Bold e Manrope
  SemiBold/Bold, com as licenças OFL). O JavaFX não lê fontes variáveis.
- `ui/FontLoader` carrega todas no início do `start()` (via `Fx.loadFonts()`), antes de qualquer Scene. Se uma
  falhar, o app segue com a fonte do sistema e avisa no console. Com `-Dquack.debug.fontes=true` (ou
  `-Dquack.dev=true`) ele imprime a família e o nome reais de cada fonte.
- Famílias registradas no Windows: `"Inter"` (Regular e Bold), `"Inter Medium"`, `"Inter SemiBold"`,
  `"Manrope SemiBold"` e `"Manrope"` (Bold). Pesos 500/600 vêm pelo nome da família no CSS, porque
  `-fx-font-weight` só distingue normal/bold.
- `src/main/resources/css/typography.css`: cores de texto (`-text-primary/-secondary/-tertiary/-disabled`), a
  escala (`.h1 .h2 .h3 .subtitle .body .caption .label-strong .kpi-value .kpi-label .mono-number`) e os
  componentes padrão. Entra em todas as telas por `Fx.stylesheets()`, **antes** do tema `quack.css`, que ajusta
  tamanhos de componentes específicos.
- Impressos (etiquetas, cupom/DANFE, documentos impressos) continuam em Nunito/Roboto Mono, ajustados ao papel.
- Vitrine para conferência: rode com `-Dquack.dev=true` e aperte **Ctrl + Shift + T** (`ui/TypographyPreview`):
  escala, botões, campos, tabela, gráfico, acentos e o status do carregamento das fontes.

### Renderização do texto
- Padrão mantido: **LCD (ClearType)**, que o tema Modena do JavaFX já liga nos controles. Comparando lado a lado
  nesta máquina, o LCD ficou um pouco mais nítido que o cinza e sem franjas coloridas.
- `-Dprism.lcdtext=false` troca para suavização em cinza: use se aparecerem franjas coloridas (projetor, TV,
  monitor girado ou ampliação acima de 100%). Em fundo transparente (tema vidro, janelas sem borda) o JavaFX já
  usa cinza.
- `-Dprism.text=t2k` **não tem efeito** no JavaFX 21: o rasterizador T2K foi removido do OpenJFX e o app continua
  no DirectWrite (o log com `-Dprism.debugfonts=true` mostra `DWFactory`).
- Acentos do português (ã, ç, é, õ) conferidos na vitrine.

## Perfis

| Perfil | O que acessa |
|---|---|
| administrador / gerente | tudo: cadastros, custos e margens, estorno de entrada, aplicar/cancelar inventário, pedido de compra, relatórios de valor |
| estoquista | produtos (sem custo/margem), entradas, ajustes, inventário (conta e consulta, **sem aplicar**), etiquetas, sugestão de reposição só com quantidades, crítico e validade sem valores |
| caixa / atendente | **recusados** no login ("este aplicativo é para estoque e gerência") |

O servidor continua sendo a autoridade: as rotas conferem o perfil (por exemplo, `curva-abc` responde 403 ao estoquista).
Para o estoquista o aplicativo não chama rotas de valor e não mostra custo, margem nem valores das divergências.

## Dados locais

Pasta `%APPDATA%\QuackEstoque\` (fallback `~/.quack-estoque`):

- `config.properties`: `apiBaseUrl`, `deviceId` (identifica o terminal nas trilhas), `lastEmail`, `glassTheme`.
- `etiquetas.json`: fila de etiquetas (produtos e cópias), modelo, impressora, ajuste fino (mm) e o **último preço impresso**
  por produto (alimenta o botão "Preços alterados"). É por terminal: com dois terminais de etiqueta a lista pode divergir.
- `rascunhos\`: conferência de NF-e em andamento (`entrada-nfe.json`) e contagens de inventário que a API não recebeu
  (`contagem-<id>.json`). Sessão expirada ou queda da API **não** perde esses rascunhos: na próxima abertura o aplicativo
  oferece retomar a conferência e, ao reabrir a contagem do mesmo inventário, reenvia sozinho as contagens pendentes.

## Atalhos

| Tecla | Ação |
|---|---|
| F2 | Foco na busca (abre Produtos) |
| F3 | Nova entrada por NF-e |
| F4 | Ajustes e histórico |
| F5 | Recarregar o catálogo (também recarrega sozinho a cada 5 min) |
| F6 | Contar inventário (abre direto o inventário em contagem) |
| F8 | Etiquetas |
| Esc | Fecha a janela aberta |

Na contagem: bipe → (quantidade, padrão 1; fração só para unidades diferentes de UN; etiqueta de balança traz o peso) → local →
Enter. Código desconhecido ou fora do escopo toca o aviso sonoro, destaca em vermelho e não registra nada.

## Análise de estoque (dados e demonstração)

A análise (menu "Análise", etapas 3 a 6 da change `analise-estoque`) lê números calculados pela API a partir de:

- **Movimentações** (`EstoqueMovimentacoes`): toda mudança de saldo (venda, cancelamento, correção, entrada por NF-e/manual/
  pedido de compra, estorno, ajuste, inventário e edição de cadastro) grava uma linha com tipo, custo unitário, documento de
  origem, autor e horário, na mesma transação do saldo. Regra: só `EstoqueMovimentacaoAB.AplicarMovimentoAsync` altera
  `Produtos.ProductQnt`.
- **Fotografia diária** (`EstoqueSnapshotDiario`): quantidade e valor ao custo por produto ao fim de cada dia (Brasília).
  Gravada pelo `EstoqueSnapshotWorker` (a cada 30 min, para "ontem") ou sob demanda por
  `POST api/Estoque/analise/snapshot?data=AAAA-MM-DD` (gerente/administrador). Idempotente; o dia passado é reconstruído
  pelo saldo atual menos as movimentações posteriores. O histórico real começa na publicação da migração 57.

### Telas do menu "Análise"

| Tela | Para que serve | Quem vê |
| --- | --- | --- |
| Dashboard | 12 indicadores com variação e semáforo e 6 gráficos (estoque no tempo, ruptura, cobertura por categoria, maior giro, maiores parados, curva ABC do estoque) | todos (estoquista sem R$) |
| Curva ABC | Pareto do período (top 100 + "Outros"), matriz ABC × FSN clicável que filtra a tabela | gerente/administrador |
| Reposição | produtos abaixo do ponto de pedido por urgência (ruptura > crítico > repor > planejar), quantidades editáveis, **rascunho de pedido por fornecedor**, CSV e Excel | todos; gerar pedido só gerente |
| Acuracidade | acuracidade por inventário (meta 98%), divergências e a **contagem cíclica do dia** (lista cega A/B/C; registra ou conta e aplica com a senha do gerente) | todos; aplicar só gerente |
| Perdas e parados | perdas por motivo e mês (quantidade ou R$) e todos os produtos parados | todos (estoquista sem R$) |
| Fornecedores | prazo prometido × real, desvio, % no prazo e produtos | todos |
| Configurações | parâmetros e metas do semáforo, com o padrão ao lado e "Restaurar padrão" | todos veem; só gerente edita |
| Detalhe do produto | indicadores, saldo diário com mínimo/ponto de pedido/máximo, entradas × saídas por semana, histograma da demanda e extrato | duplo clique em qualquer tabela da análise |

Os filtros (período com atalhos, comparação com o período anterior, categoria e fornecedor) ficam lembrados neste terminal em
`%APPDATA%\QuackEstoque\analise.json`. Cada bloco carrega sozinho; o servidor guarda cada bloco por 5 minutos e informa o horário do
cálculo (salvar a configuração, gerar pedidos ou aplicar um inventário invalidam o cache da empresa).

### Fórmulas (calculadas na API, `EstoqueCalculos`)

- **Consumo** = saídas líquidas (vendas − devoluções) na janela ÷ N dias; **desvio** populacional das N demandas diárias, com os dias sem
  venda valendo zero. Sem venda: consumo 0 e "sem giro" (nunca infinito).
- **Giro** = CMV ÷ estoque médio em valor (média das fotografias); anualizado × 365 ÷ dias; **DIO** = 365 ÷ giro anualizado;
  **cobertura** = saldo ÷ consumo. Sem fotografias: "sem histórico".
- **Segurança** = Z × desvio × √lead time; **ponto de pedido** = consumo × lead time + segurança; **alvo** = consumo × (lead time +
  cobertura alvo) + segurança; **sugestão** = máx(0, alvo − saldo − em trânsito), arredondada para cima na unidade (inteira ou 3 casas).
  Lead time: do produto → real do fornecedor → prometido → 7 dias. Período com menos de 14 dias: a sugestão ignora a segurança.
- **Ruptura** = dias com saldo ≤ 0 ÷ dias fotografados; **acuracidade** = Σ mín(contado, sistema) ÷ Σ sistema; **excesso** = (saldo −
  consumo × limite) × custo; **ABC** pelo acumulado (80%/95%) do faturamento ou CMV; **FSN** pelas semanas com saída (F ≥ 50%).
- Saldo negativo vale zero no valor em estoque e conta como ruptura.

### Configurações (padrões)

Janela de consumo 30 dias · Z 1,65 · ABC 80/95 sobre faturamento · FSN rápido ≥ 50% das semanas · parado após 90 dias · excesso acima de
120 dias de cobertura · cobertura alvo 15 dias · lead time padrão 7 dias · contagem A 30 / B 90 / C 180 dias, 20 itens por dia · metas
(verde/amarelo): giro 6/3, cobertura 45/90, ruptura 2%/5%, acuracidade 98%/95%.

### Empresa de demonstração (só em ambiente de teste)

```
dotnet run --project API/tests/EstoqueAnaliseSeed -- --connection "Server=localhost,14333;Database=HorusPdv;User ID=sa;Password=<senha>;TrustServerCertificate=True;Encrypt=True;MultipleActiveResultSets=True"
```

Cria a empresa `demo-analise` (re-executável: apaga e recria só ela) com 200 SKUs em 10 categorias, 12 fornecedores (prazo
prometido x real diferentes), 12 meses de vendas diárias com sazonalidade, pedidos de compra, perdas, 15 parados, 5 itens sem
nenhuma venda, 10 em excesso, rupturas, 6 inventários e as fotografias diárias. Usuários: `gerente.demo@teste.com` e
`estoquista.demo@teste.com` (senha `Demo@12345`). O seed **recusa** qualquer servidor que não seja `localhost` e exige
`--connection` explícita. Grava por SQL em lote (rápido) e confere ao final que a soma das movimentações é igual ao saldo.

### Verificações da API

```
dotnet run --project API/tests/EstoqueIndicadoresChecks    # SQL de teste em localhost,14333 (ou --connection)
```

Confere a regra "soma das movimentações = variação do saldo" em todos os fluxos, tipos/origens/custo e a fotografia diária
(idempotência e reconstrução), as fórmulas com os exemplos do spec e as agregações com dados controlados (demanda, CMV, estoque
médio, lead time real, curva ABC, matriz ABC × FSN, contagem cíclica, acuracidade e perdas). No app: `AnaliseFmtTest`,
`XlsxWriterTest` e os E2E `DashboardE2ETest`, `CurvaReposicaoE2ETest`, `Etapa5E2ETest` e `ConfigE2ETest` (contra a empresa de
demonstração; `-Dquack.test.png=<pasta>` guarda prints; `PrintsAnaliseTest` com `-Dquack.prints=<pasta>` imprime o menu inteiro).

## Inventário (resumo da regra)

Na **primeira** contagem de cada produto o servidor guarda o saldo do sistema naquele instante. Ao aplicar:
`novo saldo = saldo atual + (contado − saldo na 1ª contagem)`. Assim, vendas e entradas feitas depois da contagem continuam
valendo. Conte cada produto de uma vez: o que mexer no estoque entre a primeira e a última contagem do mesmo produto é tratado
como posterior à contagem. Aplicar exige a senha do gerente e justificativa e grava movimentação `inventario` por produto; a
trilha (abertura, contagens, anulações, tentativas negadas, acerto) só aceita INSERT. Detalhes em
`openspec/changes/estoque-javafx/design.md` (D3 a D5).

## Etiquetas: impressão na térmica pelo driver do Windows

O aplicativo imprime pelo **driver** (não usa ZPL/PPLA). Para a etiqueta sair no tamanho certo:

1. Instale o driver da impressora (Elgin L42/L42 Pro, Argox, Zebra...) e **crie no driver um tamanho de papel igual ao da
   etiqueta** (Preferências de impressão → Papel/Tamanho personalizado, ex.: 60 x 40 mm). O aplicativo escolhe o papel do driver
   mais próximo do modelo (até 3 mm de diferença) e usa margem mínima; se o driver não tiver o tamanho, usa o papel padrão,
   centraliza e reduz a etiqueta.
2. Escolha o **modelo** (60x40, 50x30, 40x25, 70x35 em rolo, Pimaco 6180 em folha A4 ou personalizado) e a **impressora** na tela
   Etiquetas. A pré-visualização é em tamanho real.
3. Se a etiqueta sair deslocada, informe o **ajuste fino** em mm (negativo = esquerda/acima) e "Salvar ajuste": fica no terminal.
4. O código de barras é EAN-13 quando o GTIN do produto é válido e Code128 do código interno nos demais casos (o aplicativo avisa
   GTIN inválido), com zona de silêncio de 10 módulos e módulo de pelo menos 2 dots a 203 dpi.

Teste sem papel: escolha **Microsoft Print to PDF** (ou a impressora de PDF instalada) e imprima uma fila de 3 etiquetas; o PDF
tem 3 páginas do tamanho da etiqueta. A impressão roda em segundo plano (a tela continua livre) e avisa o fim ou o erro.
O preço impresso é gravado no terminal só depois que a impressão termina sem erro.

## Classes copiadas do `CAIXA_JAVAFX` (design D1)

Os dois aplicativos evoluem e são distribuídos separadamente, então a infraestrutura foi **copiada** (pacote `br.com.quack.estoque`).
Correção de bug nessas classes precisa ser repetida nos dois lados:

`api/ApiClient` (+ `getBytes` e `LONG_TIMEOUT`), `api/ApiException`, `api/AuthApi`, `config/AppConfig` e `AppPaths` (pasta
`QuackEstoque`, sem offline), `persistence/JsonFiles`, `domain/DomainException`, `domain/Money`, `domain/BalancaBarcode` (adaptada
ao `Produto`), `ui/Fx`, `ui/Toasts`, `ui/LoginView`, `quack.css` / `glass.css` (+ bloco "Quack Estoque" no fim do `quack.css`),
fontes Nunito e Roboto Mono (o JavaFX embaralha o negrito das fontes do Windows nesta máquina: nunca usar fonte do sistema),
ícone e logos, e a técnica de impressão ajustada à largura (`ReceiptPrinter.fitScale/pageRanges/showPage` → `service/PrintService`).
Refatoração futura: módulo comum quando surgir o terceiro aplicativo.

## Limitações desta versão

- Só online. Operações de estoque exigem conexão (o aplicativo avisa e mantém rascunhos).
- Impressão só pelo driver do Windows (sem ZPL/PPLA); sem coletor de dados móvel, transferência entre lojas ou NF-e de saída.
- Produto novo criado pelo aplicativo (cadastro ou item de NF-e) nasce com NCM `00000000` e tributação padrão: complete a
  classificação fiscal na web antes de emitir NFC-e dele.
- Entrada manual só com produtos já cadastrados.
- O GTIN duplicado só é recusado quando o GTIN muda (um cadastro antigo com GTIN repetido continua editável).
