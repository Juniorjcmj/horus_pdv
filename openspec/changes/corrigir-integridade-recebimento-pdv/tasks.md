## 1. Preparação e contrato monetário

- [x] 1.1 Ler proposal, design e seis delta specs desta change; mapear entradas de venda direta, pedido, outbox, sync e impressão usando graphify conforme AGENTS.md, preservando alterações preexistentes.
- [ ] 1.2 Criar fixtures dos cenários da matriz de aceite do design, distinguindo reproduções de hipóteses históricas e incluindo valores zero, ausentes, inválidos, midpoint e quantidades fracionadas.
- [ ] 1.3 Implementar conversão/cálculo monetário determinístico compartilhado no frontend e equivalente decimal na API, com centavos canônicos e arredondamento AwayFromZero; testar 1,005 → 1,01 e 10,00 − 8,80 → 1,20 após JSON.

## 2. Persistência e fiado

- [ ] 2.1 Validar soma dos itens líquidos, cabeçalho, parcelas, recebido e troco antes de efeitos da venda direta e finalização de pedido; persistir exatamente os valores validados e testar rejeição por R$ 0,01.
- [ ] 2.2 Manter fallback de pagamento único legado com ausência de recebido/troco distinguível de zero; testar compatibilidade e rejeição de negativos, não finitos, quantidades inválidas e descontos excessivos.
- [ ] 2.3 Agregar todas as parcelas fiado para limite, débito único, saldo e auditoria; bloquear qualquer parcela fiado quando limite for zero, conforme decisão do usuário e delta de substituição (REMOVED/ADDED); testar limite positivo/zero, cliente ausente, pagamentos mistos, pedido e offline, sem impedir compra integralmente em outros meios.
- [ ] 2.4 Conferir crédito disponível (limite menos dívida, incluindo pendências locais sem dupla contagem) em toda compra fiado e após alterações; testar disponível40/compra100 bloqueada, disponível40/fiado40 permitido e disponível40/fiado40,01 bloqueado, mensagens de insuficiência, dados ausentes e compras offline sucessivas.
- [ ] 2.5 Garantir transação e idempotência nos caminhos afetados; testar falha sem efeitos parciais, resposta perdida com retry e duas vendas concorrentes disputando limite.

## 3. Checkout e offline

- [ ] 3.1 Construir snapshot de fechamento coerente em SalesStartPage, usando centavos para parcelas/saldo/troco e validando antes de confirmar; bloquear submissão duplicada ou snapshot vencido.
- [ ] 3.2 Reconciliar adição/remoção/quantidade e promoções; invalidar confirmação financeira antiga com aviso e reconferência, preservando referências de recebimentos sem estorno/cobrança automática. Testar retorno do pagamento, perda de combo e atualização por polling/focus, inclusive atualização sem efeito financeiro.
- [ ] 3.3 Aplicar invariantes antes de inserir na SaleOutboxAdapter e novamente no sync do servidor; manter registros inválidos antigos com erro acionável e identidade intacta, sem retry infinito ou descarte silencioso.
- [ ] 3.4 Testar venda offline válida e retry de sincronização sem duplicação de venda, estoque, caixa e fiado; verificar item gratuito e quantidade fracionada nos dados persistidos.

## 4. Fiscal e reimpressão

- [ ] 4.1 Normalizar payload em fiscalService e demais adaptadores antes de serializar para Gateway; alinhar FiscalPaymentRules à igualdade em centavos preservando declaração bruta já corrigida por 62b1a94 e rigor do LocalFiscalSigner.
- [ ] 4.2 Criar regressões cloud/local usando payload real e JSON: dinheiro 8,80/10,00, misto PIX50 + dinheiro líquido50/recebido100/troco50, múltiplas parcelas dinheiro e erro real de um centavo; verificar documento original preservado na sincronização.
- [ ] 4.3 Auditar contrato de impressão online/local e expor opcionalmente detalhes existentes necessários; corrigir SalesHistoryPage e renderização para preservar líquido zero, descontos, pagamentos, recebido/troco e ausência legada.
- [ ] 4.4 Testar reimpressão de item gratuito5,14 + demais82,48, pagamentos mistos, campos ausentes e divergência histórica; confirmar indicação da divergência sem regravação de dados.

## 5. Validação e entrega

- [ ] 5.1 Compatibilizar minimamente API/tests/FiscalReviewChecks ou prover suíte isolada adequada para os cenários fiscais/API, documentando o CS1705 inicial e a solução sem migração ampla de runtime.
- [ ] 5.2 Executar build da API (`dotnet build API/NETCORE/HORUSPDV-API.csproj --no-restore`), testes API relevantes e `dotnet test GATEWAY/tests/HorusGateway.Tests --no-restore --filter FullyQualifiedName~LocalFiscalComplianceTests`; registrar resultados e diferenças do baseline14/14.
- [ ] 5.3 Executar testes frontend e verificação de tipos/build pertinentes disponíveis no projeto; validar a matriz de aceite em ambiente local, incluindo adicionar/remover/voltar, promoções, descontos, quantidades fracionadas, mistos e contingência.
- [ ] 5.4 Revisar diff e contratos de compatibilidade, executar `graphify update .` após mudanças de código e `openspec validate corrigir-integridade-recebimento-pdv --strict`; entregar evidências e pendências operacionais sem publicar ou alterar produção.

As tarefas acima podem ser concluídas sem acesso à loja. A conferência do cupom1245, venda816/NFC-e354, XML original e versão instalada está exclusivamente no checklist operacional de `design.md`; não é tarefa obrigatória bloqueante do apply e não autoriza correção histórica, reemissão ou cancelamento.
