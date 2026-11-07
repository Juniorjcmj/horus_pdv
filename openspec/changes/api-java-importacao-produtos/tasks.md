# Tasks

Contexto obrigatório: `proposal.md`, `design.md` (I1–I9) e `specs/produtos/importacao-planilha/spec.md`. Código em
`JAVA/api/src/main/java/br/com/quack/pdv/` (`produto/`, `cadastro/categoria/`, `estoque/registrador/`) e
`JAVA/estoque/`. **Nada em `API/NETCORE/` nem `GATEWAY/` pode mudar.** O arquivo real
`docs/planilha material/planilha de produtos.csv` **não pode ser versionado** (dados do cliente) — só lido no teste
manual da tarefa 6.2.

## 1. Leitura e mapeamento (sem banco)

- [x] 1.1 Criar `src/test/resources/importacao/amostra-legado.csv` (ISO-8859-1, cabeçalho idêntico ao do arquivo real com as 114 colunas, ~40 linhas sintéticas cobrindo os casos de I9) e um gerador de teste de 3.300 linhas. Verificação: o arquivo abre com 114 colunas em todas as linhas (teste do leitor) e não contém nenhum CNPJ/descrição copiado do arquivo real.
- [x] 1.2 `LeitorCsvProdutos` (I1): ISO-8859-1 ou UTF-8 com BOM, `;`, aspas, colunas por nome normalizado, recusa do arquivo sem coluna obrigatória com a lista, limite de 15 MB/50.000 linhas. Verificação: unitários cobrem "Colunas fora de ordem", "Coluna obrigatória ausente" e "Acentos preservados".
- [x] 1.3 `MapeadorProdutoLegado` (I3, I4): números brasileiros, GTIN GS1, CFOP/PIS/COFINS padrão com aviso, unidade em maiúsculas, embalagem, fornecedor, localização, saldo negativo → 0 com aviso, aviso `M2` com `MT` na descrição. Verificação: unitários cobrem os cenários de "Valores no formato brasileiro", "Código do produto e GTIN" (exceto repetido), "Preço, custo e cadastro" e "CFOP vazio".

## 2. Plano e categorias

- [x] 2.1 `PlanoImportacao` (I1): novo/atualizado/recusado contra o estado do banco pré-carregado (código→id, gtin→código, categorias, `ImportacoesProduto`); GTIN repetido no arquivo e GTIN usado por outro código no banco → recusa; resumo fiscal agrupado. Verificação: unitários com estado em memória cobrem "GTIN repetido", "Resumo fiscal" e "Linha recusada não bloqueia" (no plano).
- [x] 2.2 Resolução de categorias em três níveis (I5) com chave normalizada e pai. Verificação: unitários cobrem "Categoria criada uma vez" e "Mesmo nome em pais diferentes" e o caso Grupo sem Classe (aviso).

## 3. Gravação, idempotência e rotas

- [x] 3.1 Migração `db/migration/V7__importacao_produtos.sql` com `ImportacoesProduto` (I6) e classificação `FORA` no `CatalogoSincronizacao`. Verificação: `GuardaCatalogoTest`, `CatalogoSchemaIT` e a subida do perfil `loja` no `AmbienteSync` passam.
- [x] 3.2 Extrair de `ProdutoService` o INSERT/UPDATE reaproveitável e implementar `GravadorImportacao` (I1, I6, I7): uma transação (timeout 300 s), categorias → produtos → `RegistradorEstoque.aplicar(..., EstoqueTipos.ENTRADA, ...)` com origem `importacao` + linha em `ImportacoesProduto`. Verificação: IT `ImportacaoProdutosIT` (banco real de teste) cobre "Entrada inicial", "Saldo negativo", "Reimportação não duplica estoque", "Preço atualizado" e "Falha no meio" (forçar erro na linha N e conferir zero produtos do arquivo); testes atuais de `ProdutoService` continuam passando.
- [x] 3.3 Rotas `POST /api/produtos/importacao/simular` e `/confirmar` (I2), `multipart` com limites de 15/16 MB e liberação do limite de corpo só nessas rotas, perfis administrador/gerente. Verificação: `ImportacaoProdutosIT` cobre "Simulação não grava" (com a amostra), "Perfil sem permissão" (403), arquivo acima do limite (400) e outra rota qualquer continua limitada a 2 MB; simular e confirmar a mesma amostra devolvem os mesmos totais.
- [x] 3.4 Volume: com o arquivo sintético de 3.300 linhas, a confirmação termina em menos de 60 s no SQL Server do Testcontainers e o relatório bate com a contagem esperada. Verificação: teste em `ImportacaoProdutosIT` com a duração registrada no log.
- [x] 3.5 Modo loja: confirmar a amostra no servidor da loja do `AmbienteSync` e conferir que produtos, categorias e movimentações chegam na nuvem de teste e que `ImportacoesProduto` não é enviada. Verificação: IT novo em `plataforma/sincronizacao/integracao` (ex.: `ImportacaoLojaIT`) passa.

## 4. Quack Estoque

- [x] 4.1 Cliente HTTP do estoque para as duas rotas (multipart, timeout de 5 min) e DTO do relatório. Verificação: teste unitário do cliente com servidor HTTP falso (padrão dos testes existentes do `JAVA/estoque`) envia o campo `arquivo` e lê o relatório.
- [x] 4.2 Tela "Importar planilha" (I8) com cartões de totais, resumo fiscal, abas de recusados/avisos, exportação CSV e botão "Importar" habilitado só após simulação válida, visível só para administrador/gerente. Verificação: teste de UI no padrão dos E2E existentes do estoque (ou, se não houver para telas novas, roteiro manual registrado nas notas) cobre "Confirmar depois de simular" e "Exportar recusados".
- [x] 4.3 Gerar o instalador do Quack Estoque com `JAVA/estoque/build-exe.ps1` (versão incrementada). Verificação: novo `dist\QuackEstoque-<versão>-windows.zip` existe e `versao.txt` foi incrementado.

## 5. Documentação

- [x] 5.1 Seção "Importar produtos de planilha" no `JAVA/api/instalador-loja/CHECKLIST.md` (ordem: instalar → importar → conferir amostra → nuvem → caixas; levar o resumo fiscal ao contador) e nota em `docs/migracao-api-springboot.md`. Verificação: as seções existem e citam que o arquivo do cliente não vai para o repositório.

## 6. Integração

- [x] 6.1 Rodar os testes novos, os de produto/categoria/estoque e a suíte de sincronização. Verificação: saída do Maven sem falhas.
- [ ] 6.2 Teste manual com o arquivo real (fora do repositório) numa API local de teste (banco descartável): simular, registrar no `design.md` ("Notas da implementação") os totais (novos, recusados por motivo, avisos por tipo, categorias criadas, produtos com saldo, tempo) e confirmar; conferir 10 produtos por amostragem (código, GTIN, preço Tab.3, categoria, saldo). Verificação: notas registradas com os números.
- [x] 6.3 Gerar o pacote da loja (`bash JAVA/api/instalador-loja/build-instalador.sh`). Verificação: `quack-servidor-loja.zip` novo.

## Workflow follow-up

- Na loja nova: criar a empresa, instalar o servidor, importar pelo Quack Estoque, conferir amostra, depois ligar os caixas.
- Arquivar esta change depois das changes do modo loja.

## Pendentes por causa do Docker (ver "Notas da implementacao" no design.md)

- 3.1 (so `CatalogoSchemaIT` e a subida do perfil `loja` no `AmbienteSync`; `GuardaCatalogoTest` passou), 3.2, 3.3 (parte de banco), 3.4, 3.5, 6.1 (suite de sincronizacao e ITs) e 6.2 (SQL Server necessario). Os ITs `ImportacaoProdutosIT` e `ImportacaoLojaIT` estao escritos e compilam, mas nao foram executados.
