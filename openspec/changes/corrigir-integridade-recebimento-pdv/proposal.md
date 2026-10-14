## Why

A auditoria de recebimento identificou caminhos reproduzíveis que perdem totais líquidos iguais a zero na reimpressão, enviam troco com resíduos de ponto flutuante e permitem persistir vendas sem conferir itens, pagamentos e fiado integralmente. Os relatos do cupom 1245 e da NFC-e 354 motivam prevenir novas divergências; sem banco, XML original e versão instalada, a causa desses registros específicos permanece não comprovada.

## What Changes

- Estabelecer invariantes em centavos entre itens líquidos, total da venda, parcelas líquidas, dinheiro recebido e troco, antes de efeitos persistentes online, offline e na sincronização.
- Normalizar cálculos monetários e serialização fiscal, preservando validações estritas do Gateway e a correção fiscal já existente no commit `62b1a94`.
- Reimprimir valores persistidos fielmente, incluindo itens gratuitos, descontos, meios de pagamento, recebido e troco; explicitar ausência de dados legados sem inventar valores.
- Validar limite e lançar o débito pela soma de todas as parcelas fiado, atomicamente e sem duplicação em retries. Limite zero bloqueia qualquer compra com parcela fiado, conforme decisão expressa do usuário. Toda compra deve conferir crédito disponível (limite menos dívida): R$ 40 restantes não permitem R$ 100 em fiado.
- Garantir consistência ao adicionar/remover itens, alterar quantidades, voltar do pagamento e atualizar promoções durante checkout.
- Criar regressões mensuráveis para pagamentos mistos, descontos, quantidades fracionadas, contingência e reprocessamento.
- Diagnosticar registros antigos somente por leitura quando houver acesso; nenhuma correção histórica, emissão, cancelamento ou operação de produção automática faz parte desta change.

## Capabilities

### New Capabilities

- `vendas/integridade-monetaria`: contrato de fechamento e persistência coerente online/offline, incluindo compatibilidade legada e rejeição sem efeitos parciais.
- `vendas/reimpressao-fiel`: impressão baseada nos dados efetivamente registrados, preservando zero e ausência de informação.

### Modified Capabilities

- `pdv/multiplas-formas-pagamento`: valores em centavos, troco coerente e preservação do declarado fiscal por meio.
- `vendas/fiado-conta-corrente`: agregado de todas as parcelas fiado para limite e débito único.
- `vendas/promocoes`: reconciliação do carrinho e invalidação do pagamento quando a composição financeira muda.
- `fiscal/contingencia-offline-nfce`: payload monetário consistente entre PDV, Gateway e sincronização.

## Impact

Frontend: `SalesStartPage.tsx`, `SalesHistoryPage.tsx`, `SaleOutboxAdapter.ts`, `fiscalService.ts`, contratos de impressão e testes associados. API: `HistoricoVendasAB.cs`, endpoints/DTOs de impressão, regras fiscais e persistência transacional. Gateway: testes de conformidade e fronteiras monetárias, sem relaxar o assinador. As áreas cloud e Gateway já têm mudanças em andamento; preservar trabalho preexistente e reutilizar contratos existentes.

Não há mudança de runtime planejada. O harness `API/tests/FiscalReviewChecks` apresenta CS1705 entre Hosting.Abstractions 8 e 10; sua compatibilização mínima ou uma suíte isolada adequada integra a validação. A implementação posterior deve ocorrer pelo fluxo `openspec-apply`, em contexto separado, com os artefatos desta change.
