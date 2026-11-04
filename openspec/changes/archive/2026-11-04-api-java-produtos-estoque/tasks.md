# Tasks

> Antes de cada grupo, leia a decisão correspondente em `design.md`, o spec do módulo em `specs/` e os arquivos .NET
> de referência do Context. Mensagens, códigos HTTP, nomes e formatos de campos (inclusive os valores pt-BR em texto)
> e ordem das listas são copiados literalmente do .NET; em dúvida, vale a gravação do contrato. Trabalhe na branch
> `spring-api`, em `JAVA/api`.
>
> **Ciclo de cada grupo de rotas:**
> 1. escrever os cenários de contrato do grupo;
> 2. gravar contra a API .NET (`contrato/ambiente-dotnet/subir.sh` e o `GravadorDoContrato`);
> 3. implementar com os testes unitários e web;
> 4. rodar o comparador do grupo até ficar verde.
>
> Antes de rodar `mvn verify`, feche qualquer API Java iniciada pelo `.jar` de `target/`, que prende o arquivo.

## 1. Base: seed, entidades e registrador

- [x] 1.1 Ampliar `contrato/seed/seed-contrato.sql` e `reset-contrato.sql` com os dados do D9 (estoquista, produtos com e sem validade e com lotes, produto com estoque mínimo, prazos por categoria, ordens pendente e recebida, uma promoção de cada tipo, inventário aberto, modo FEFO) e criar as fixtures de XML em `src/test/resources/contrato/fixtures/`; verificar aplicando seed e reset duas vezes no ambiente .NET sem erro e com os 11 grupos de contrato existentes ainda verdes
- [x] 1.2 Criar as entidades e repositórios de `Produto` (com `ProductQnt`/`TotalPriceOnProduct` não atualizáveis pelo JPA, D2), `EstoqueMovimentacao`, `ProdutoLote` e demais tabelas de lote, `NotaEntrada`, inventário (tabelas da migração 56), `OrdemCompra`/itens e `Promocao`/`PromocaoProdutos`, com nomes e tipos do V1; verificar com `mvn -B verify` (subida com `ddl-auto=validate`) e IT de repositório lendo produtos do seed com acentos e decimais
- [x] 1.3 Implementar `RegistradorEstoque` (D2: SQL com `OUTPUT INTO` + `SELECT`, `MANDATORY`, delta zero, truncamentos, custo padrão, `aplicarPorCodigo`), `EstoqueTipos` e `MovimentoOrigem`; testes: Mockito do cálculo das origens, IT de concorrência (duas threads tirando 1 de saldo 10 → saldo 8 e movimentações 10→9 e 9→8), IT de rollback (falha depois do registrador não deixa saldo nem movimentação) e `RegistradorUnicoTest` (nenhum `UPDATE Produtos … ProductQnt` fora do registrador)
- [x] 1.4 Implementar o `ValidadorSupervisor` (porta de `HorusSecurityStore.ValidateSupervisorCredentials`, D6) com testes Mockito de cada mensagem (inexistente, inativo, perfil sem permissão, senha errada, outra empresa)

## 2. Produtos e movimentações

- [x] 2.1 Escrever `cenarios/produto.json` (listar por caixa, criar, sem descrição, custo zero, código e GTIN repetidos, fornecedor desconhecido, código vazio, editar com `estoqueConsultado` depois de uma saída no `antes`, editar sem mudar a quantidade, `preco-venda` ok, sem versão, com versão velha → 409, preço com 3 casas, excluir, outra empresa → 404, estoquista criando → 403, vencimentos e resumo, validade inválida, `importar-legado` por atendente → 403) e `cenarios/movimentacao.json` (cada tipo de ajuste, sem motivo, tipo inválido, quantidade zero, saída maior que o saldo, movimentações do produto e gerais com `tipo` e `limit`, caixa → 403) e gravar contra o .NET
- [x] 2.2 Implementar `ProdutoService`/`ProdutoController` (D3: modelo pt-BR, unicidade, fornecedor, estoque relativo pelo registrador, preço com versão e 409, exclusão, vencimentos, validade, carga legada com o script em `db/legado/`) com testes Mockito de cada regra e `@WebMvcTest` de perfis; verificar com o comparador do grupo `produto` verde e um IT da carga legada numa empresa de teste
- [x] 2.3 Implementar o ajuste de estoque e as consultas de movimentação (spec `estoque/movimentacao`, mensagem "<Tipo> de <quantidade> registrada com sucesso." no formato numérico da gravação), com o lote de entrada e o consumo de saída como operações seguras (D4); testes Mockito das validações e IT de que a falha no lote não desfaz o saldo; verificar com o comparador do grupo `movimentacao` verde

## 3. Lotes e FEFO

- [x] 3.1 Escrever `cenarios/lote.json` (alertas, consulta com cada filtro, período invertido, janela 4000, lotes do produto, registrar lote, quantidade zero, validade inválida, situação FEFO, mudar modo por gerente, modo inválido, atendente mudando modo → 403, categorias, prazo 0, categoria de outra empresa → 404, perda em modo `ativo` consumindo o lote mais antigo e a consulta seguinte) e gravar contra o .NET
- [x] 3.2 Implementar `LoteService`/`LoteController` e a porta do `LoteEstimador` (D4: modos, faixas com "hoje" em America/Sao_Paulo, janela categoria→produto→padrão, consumo FEFO comum `ConsumirNosLotes`, sincronização da validade do produto), com testes de mesa do estimador e das faixas (relógio fixo) e IT do consumo; verificar com o comparador do grupo `lote` verde

## 4. Entradas de mercadoria

- [x] 4.1 Portar `NfeXmlParser` (DOM endurecido contra XXE, limite de 10 MB) e `NfeAccessKey` (DV módulo 11, modelo, CNPJ) com testes de mesa: `nfe-exemplo.xml`, NFC-e, chave adulterada, sem `<det>`, sem `<emit>`, XML com DOCTYPE recusado, base64 corrompido
- [x] 4.2 Escrever `cenarios/nfe-import.json` (preview de NF-e e de NFC-e, modelo não aceito, sem itens, chave divergente; confirmar criando e vinculando produtos; confirmar de novo a mesma nota; código repetido; notas-entrada lista, detalhe, XML e comprovante; estorno curto, estorno ok, estorno repetido; exportar mês válido e mês 13; vincular XML com emitente diferente e com o certo; `buscar-sefaz` com chave curta, com chave de NFC-e e com chave válida (este com `divergencia`, D5); caixa → 403; estoquista estornando → 403) e `cenarios/entrada-manual.json` (entrada com e sem fornecedor, fornecedor de outra empresa, 0 e 201 itens, reenvio idêntico, reenvio com outros dados, validade inválida) e gravar contra o .NET
- [x] 4.3 Implementar a confirmação de NF-e e a entrada manual (D5: `sp_getapplock` na transação, replay por `operacaoId` + `fingerprint` igual ao .NET, produtos criados ou vinculados, entradas pelo registrador, lotes como operações seguras, JSON de `NotasEntrada.Entrada` igual ao do .NET) com testes Mockito das validações e IT do replay, inclusive lendo uma entrada gravada pelo .NET no seed; verificar com o comparador do grupo `entrada-manual` verde
- [x] 4.4 Implementar as notas de entrada (lista, detalhe, XML, comprovante, estorno pelo registrador, ZIP do mês com `resumo.csv`, vínculo de XML) e o `buscar-sefaz` da D5, com IT do ZIP (entradas, nomes e CSV iguais ao do .NET para o mesmo mês do seed); verificar com o comparador do grupo `nfe-import` verde (com a divergência registrada)

## 5. Inventário

- [x] 5.1 Escrever `cenarios/inventario.json` (abrir por loja, categorias e produtos; escopo inválido e vazio; escopo sobreposto → 409; listar, consultar e itens por gerente e por estoquista (contagem cega); contar por id e por código; código desconhecido → 404; fora do escopo → 422; quantidade negativa; fração em UN; anular a própria contagem, anular a de outro (estoquista) → 403, anular de novo → 409, sem motivo; recontar; aplicar com justificativa curta, senha de supervisor errada → 403, aplicar ok com diferença, aplicar de novo → 409; cancelar sem motivo e com motivo; trilha com as negadas; atendente → 403) e gravar contra o .NET
- [x] 5.2 Implementar `InventarioService`/`InventarioController` (D6: resultados com código, mapa para HTTP, contagem cega, abertura serializada, aplicação pelo registrador com tipo `inventario`, `zerarNaoContados`, lotes depois do commit, trilha e tentativas negadas, ponto de extensão vazio para o cache da análise) com testes Mockito do mapa código→HTTP e das regras de contagem e IT da aplicação (saldo final e movimentações); verificar com o comparador do grupo `inventario` verde

## 6. Ordens de compra

- [x] 6.1 Escrever `cenarios/ordem-compra.json` (criar com e sem fornecedor, sem itens, quantidade zero, numeração seguinte ao seed, listar, consultar, outra empresa → 404, editar pendente, editar recebida → 400, receber parcial, completar o recebimento, receber com validade, nada para receber, cancelar pendente, cancelar recebida → 404, sugestões de reposição, atendente → 403) e gravar contra o .NET
- [x] 6.2 Implementar `OrdemCompraService`/`OrdemCompraController` (D7: número por empresa, situações, recebimento pelo registrador com lote, cancelamento, sugestões com o SQL literal da D11) com testes Mockito do cálculo da situação e IT do recebimento; verificar com o comparador do grupo `ordem-compra` verde

## 7. Promoções

- [x] 7.1 Escrever `cenarios/promocao.json` (listar, ativas, consultar, resultado, criar cada um dos 6 tipos, cada validação do spec `vendas/promocoes` e do `PromocaoService`, categoria inexistente, editar, ativar e desativar, excluir, inexistente → 404, atendente criando → 403, caixa lendo ativas) e gravar contra o .NET
- [x] 7.2 Implementar `PromocaoService`/`PromocaoController` (D8) com testes Mockito de cada tipo e da vigência (relógio fixo em Brasília) e IT do resultado sobre `VendaItens` do seed; verificar com o comparador do grupo `promocao` verde

## 8. Aceite pelo Quack Estoque

- [x] 8.1 Criar `contrato/aceite-estoque/subir.sh` e `descer.sh` (D10: backup do `HorusPdv` de `pdv-teste-sql`, restauração num container `pdv-java-sql`, API Java na porta 5261 com limite por IP alto e e-mail desligado); verificar que a API sobe, faz baseline e aceita o login `caixa@teste.com`
- [x] 8.2 Rodar os E2E do Quack Estoque contra a API Java com o comando da D10 (sem `RelatoriosE2ETest` e sem `ComprasE2ETest#geraUmPedidoPorFornecedor`) e corrigir até ficarem verdes; para cada falha, gravar o cenário de contrato que faltava e corrigir pelo comparador; verificar pelo relatório do Surefire do `JAVA/estoque`

> Aceite final 03/11/2026 15:03: comando D10 revisado, 36 testes, todos verdes. Por decisão do dono do produto,
> os 6 E2E de inventário que dependem de venda (`InventarioAppE2ETest` e 5 métodos de `InventarioE2ETest`) ficam
> fora até a Fase 4 portar `POST /api/HistoricoVendas`. `TelasE2ETest#todasAsTelasDoAdministrador` estava
> desatualizado (16 telas) e foi corrigido para as 19 telas atuais do app. Log em
> `JAVA/api/contrato/aceite-estoque/.estado/e2e-opcao2.log`.

> Atualização de 03/11/2026 (Fase 4, change `api-java-vendas`): com `POST /api/HistoricoVendas` portado, os 6 E2E de
> inventário excluídos acima (`InventarioAppE2ETest` e os métodos de `InventarioE2ETest` que dependem de venda) voltaram
> ao comando da D10 e passaram. Aceite do Estoque sem as exclusões de inventário: 42 testes, 0 falhas (13 métodos de
> `InventarioE2ETest`, não 14). Comando em `JAVA/api/README.md`.

## 9. Integração

> Aceite 03/11/2026: a rodada D10 original executou 43 testes (27 verdes, 9 falhas e 7 erros).
> Treze falhas/erros pertencem às quatro classes de análise da Fase 6 explicitamente excluídas na D10 revisada.
> Permanecem pendentes as duas suítes de inventário (rotas aguardam decisão sobre contagem cega) e
> `TelasE2ETest#todasAsTelasDoAdministrador` (cliente espera 16 telas, aplicação atual renderiza 19).
> Esses bloqueios não são dispensados pelo teste independente filtrado e a tarefa 8.2 permanece aberta.
> A rodada independente de 03/11/2026 12:16:56 excluiu somente essas pendências nomeadas e terminou com
> 27 testes verdes, zero falhas/erros/skips. Relatórios original e filtrado preservados em
> `JAVA/api/contrato/aceite-estoque/.estado/e2e-original/` e `e2e-filtrado/`.

- [x] 9.1 Rodar `mvn -B verify` em `JAVA/api` com todos os grupos de contrato (os 11 anteriores e os 8 novos) e toda a suíte verde; verificar pelo relatório do Failsafe/Surefire
- [x] 9.2 Atualizar `JAVA/api/README.md` (rotas portadas na Fase 3, o que ficou para as Fases 4, 6 e 7, a divergência do `buscar-sefaz`, como rodar o aceite do Quack Estoque) e `contrato/README.md` (grupos novos, fixtures e dados do seed); verificar que os comandos documentados funcionam

## Workflow follow-up

- Depois do apply: `/opsx:archive api-java-produtos-estoque`.
- Próxima fase do plano: Fase 4, Vendas (usa o registrador, o consumo de lotes e o validador de supervisor desta fase).
