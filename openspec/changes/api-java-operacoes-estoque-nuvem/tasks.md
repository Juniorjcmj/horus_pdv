# Tasks

Contexto obrigatório: `proposal.md`, `design.md` (O1–O9) e `specs/plataforma/operacoes-estoque-nuvem/spec.md`.
Base: change `api-java-modo-loja` (design D2, D6, D7, D10). Código em `JAVA/api/src/main/java/br/com/quack/pdv/`
(`plataforma/sincronizacao/**`, `estoque/registrador/RegistradorEstoque.java`). **Nada em `API/NETCORE/` nem
`GATEWAY/` pode mudar.** Testes de integração em `JAVA/api/src/test/java/.../plataforma/sincronizacao/integracao/`
(usar `AmbienteSync`, como `ComandosIT` e `ConferenciaIT`).

## 1. Conferências no código antes de mudar

- [ ] 1.1 Ler na Java da loja quem grava `MapeamentoProdutoFornecedor`, `ProdutoLotes` e `NotasEntradaArquivo` (e se o saldo de lote é estimado pelas movimentações, como o `LoteEstimador` da .NET); registrar o resultado em "Notas da implementação" no fim do `design.md`. Verificação: a nota cita classe e método de cada gravação.
- [ ] 1.2 Confirmar na .NET (só leitura) se `LoteAB.RegistrarEntradaAsync`, `SincronizarValidadeProdutoAsync` e `MapeamentoProdutoFornecedorAB.SalvarMapeamentoAsync` rodam dentro da transação de `NfeImportService.ConfirmarAsync` (`UseImportTransaction`/`OpenLeaseAsync`), e listar os valores de `Tipo` e `OrigemTipo` gravados em `EstoqueMovimentacoes`. Verificação: nota no `design.md` com a resposta e o efeito no agrupamento (O1).

## 2. Catálogo e recurso anunciado

- [ ] 2.1 Em `TabelaSincronizada`, adicionar `insercaoDaNuvem` e `transicoes` (nome, condição na loja, colunas), mantendo `AceiteDaNuvem` de `Vendas` funcionando (pode virar uma transição). Configurar `EstoqueMovimentacoes`, `ProdutoLotes` e `NotasEntradaArquivo` como na tabela de O4. Verificação: teste unitário do catálogo cobre as três tabelas e `GuardaCatalogoTest` passa.
- [ ] 2.2 Mudar `MapeamentoProdutoFornecedor` para `EDITAVEL` (O5), tirando-a da lista `LOJA` em `CatalogoSincronizacao` e do D2 do `api-java-modo-loja` em nota. Verificação: `GuardaCatalogoTest`, `EdicoesDaNuvemCatalogoTest` e `CatalogoSchemaIT` passam.
- [ ] 2.3 Migração da nuvem (`db/nuvem/V2__...sql`): `SyncServidores.Recursos NVARCHAR(200) NULL` e `SyncComandoChaves(Seq BIGINT, Tabela NVARCHAR(128), Chave NVARCHAR(400), PK (Tabela, Chave, Seq))`. Verificação: `CatalogoSchemaIT`/subida do perfil `nuvem` aplica a migração sem erro.
- [ ] 2.4 Loja: `ClienteComandos.buscar` envia `X-Quack-Recursos: operacao-estoque`. Nuvem: `ServidorLojaFilter` grava o valor em `SyncServidores.Recursos` quando muda; serviço `RecursosLoja.suporta(companyId, "operacao-estoque")`. Verificação: teste em `RegistroServidorIT` (cabeçalho presente grava; ausente mantém nulo).

## 3. Detector da nuvem

- [ ] 3.1 `DetectorNuvem.ciclo`: para empresa com o recurso, pré-ler as mudanças da nuvem em `EstoqueMovimentacoes` do ciclo (produtos com movimentação) e incluir `SYS_CHANGE_VERSION` em `MotorDeteccao.lerMudancas` (novo campo em `Mudanca`). Sem o recurso, nada muda. Verificação: `ComandosIT` e `SincronizacaoIT` continuam passando.
- [ ] 3.2 `MotorDeteccao`: gravações da nuvem em tabela com `insercaoDaNuvem`/`transicoes` (empresa com o recurso) deixam de ir para a quarentena e viram passos (`inserir`, `movimento`, `transicao`) agrupados por versão; ao fim do ciclo, um comando `operacao` por versão, criado depois dos comandos das tabelas `EDITAVEL`, com as chaves em `SyncComandoChaves` (O1, O4). Alteração não prevista continua na quarentena. Verificação: teste novo `OperacoesNuvemIT` — nota com 2 itens gravada na nuvem como a .NET grava (mesma transação) gera **um** `operacao` com os passos na ordem; alteração de `Quantidade` de movimento existente vai para a quarentena.
- [ ] 3.3 Exceção do saldo (O3): `inserir` de `Produtos` com o recurso leva `ProductQnt`/`TotalPriceOnProduct` = 0; mudança dessas colunas num produto com movimentação da nuvem no ciclo, ou em `operacao` pendente (consultar `SyncComandoChaves`), não vai para a quarentena nem gera `reenviar`; aplicar a mesma regra em `DeteccaoInline`. Verificação: casos em `OperacoesNuvemIT` — produto novo com 5 não chega com 10; ajuste na nuvem não gera quarentena; `ProductQnt` mudado na nuvem sem movimento continua indo para a quarentena.
- [ ] 3.4 Medir uma nota de 300 itens: tamanho do payload do `operacao` contra o limite de entrega de comandos; se passar, dividir por tabela mantendo a ordem (Risks do design). Verificação: teste em `OperacoesNuvemIT` com 300 itens aplica tudo na loja.

## 4. Aplicação na loja

- [ ] 4.1 `RegistradorEstoque.aplicarComId(companyId, produtoId, delta, tipo, custo, origem, depositoId, idMovimento)` (O2), reaproveitando o SQL atual; o método antigo passa a chamar o novo com `Id` gerado. Verificação: teste do registrador grava o `Id` dado e anterior/novo da loja; testes atuais do registrador passam.
- [ ] 4.2 `AplicadorOperacoes` (novo, perfil `loja`) e caso `operacao` em `AplicadorComandos`: uma transação, contexto `comando-nuvem`; passos `inserir` (colunas do catálogo, pula se a chave existe), `movimento` (delta = `EstoqueNovo − EstoqueAnterior`, pula se o `Id` existe), `transicao` (regras `estorno` e `xml` de O4, pula se já no destino, `conflito` `linha-ausente` se a linha não existe); `AuditLog` `SincronizacaoOperacao` (O6). Verificação: `OperacoesNuvemIT` — entrada aplicada; venda na loja antes da aplicação dá o saldo do cenário "Venda na loja enquanto a nota é lançada na nuvem" (20 − 3 + 10 = 27, anterior 17); reentrega não muda nada; falha no 4º de 5 itens não grava nenhum.
- [ ] 4.3 Nota em dobro e estorno de nota recusada (O6.1, O8): `conflito` `nota-ja-lancada` com o número; movimentos com `OrigemTipo='nota_entrada'` de nota ausente na loja → `conflito` `nota-recusada`; alerta ao dono via `AlertasSync`. Verificação: `OperacoesNuvemIT` — mesma NF-e na loja e na nuvem sobe o estoque uma vez e gera o alerta; estorno pela nuvem de nota aceita baixa o saldo e marca `Estornada`.
- [ ] 4.4 Loja com jar antigo: nuvem sem o recurso mantém a quarentena; `operacao` entregue a um aplicador que não conhece o tipo resulta em `falhou` (não trava a fila). Verificação: caso em `OperacoesNuvemIT` sem o cabeçalho.

## 5. Conferência

- [ ] 5.1 `ServicoConferencia` na nuvem: não excluir linha de tabela `LOJA` presente em `SyncComandoChaves` de `operacao` sem resultado (O7); depois de `conflito`/`parado`, exclui normalmente. Verificação: `ConferenciaIT` — conferência com a loja offline mantém a nota da nuvem; depois de `conflito`, a nuvem fica igual à loja (saldo e entradas).
- [ ] 5.2 Documentar no `JAVA/api/instalador-loja/CHECKLIST.md` (seção nova "Notas lançadas na nuvem") o que conferir e o alerta de nota em dobro; e em `docs/migracao-api-springboot.md` § 3.3 a regra nova. Verificação: as duas seções existem e citam a ordem de implantação (loja antes da nuvem).

## 6. Integração

- [ ] 6.1 Rodar a suíte de sincronização (`*IT` de `plataforma/sincronizacao/integracao`) e os testes do catálogo; todos passam. Verificação: saída do Maven sem falhas.
- [ ] 6.2 Ponta a ponta com a .NET real (padrão do `ModoLojaDotnetIT`, imagem `horus-pdv-api:teste`): confirmar uma NF-e pela rota da .NET na nuvem de teste e conferir entrada e saldo na loja de teste; estornar pela .NET e conferir. Verificação: teste `OperacoesNuvemDotnetIT` passa.
- [ ] 6.3 Gerar o pacote da loja (`bash JAVA/api/instalador-loja/build-instalador.sh`) e confirmar que o jar anuncia o recurso. Verificação: `quack-servidor-loja.zip` novo; log da loja mostra o cabeçalho no primeiro long-poll (ou teste do 2.4).

## Workflow follow-up

- Implantar na ordem do Migration Plan do design: loja primeiro (`instalar.ps1 -EmailAdmin … -PularCarga`), depois a imagem da nuvem no Portainer.
- Teste na loja real com uma nota de 1 item de teste lançada pela nuvem, e estorno.
- Arquivar esta change depois de `api-java-modo-loja`.
