## Why

Compras com cupom ou comprovante precisam de um recebimento manual acessível em Produtos, sem exigir XML ou chave fiscal, mantendo custo e histórico confiáveis.

## What Changes

- Entrada manual com seleção de produtos ou cadastro de novos na confirmação.
- Quantidade e custo por embalagem convertidos para a unidade de venda; preço de venda preservado por padrão.
- Data, fornecedor opcional, documento e comprovante opcional no histórico de entradas.
- Confirmação atômica, isolamento por empresa e proteção contra repetição da operação ou chave já recebida.

## Capabilities

### New Capabilities
- `entrada-manual-produtos`: recebimento manual de compras com conversão, cadastro e histórico.

### Modified Capabilities
Nenhuma alteração de requisitos de outras capacidades.

## Impact

API de produtos, arquivo de notas de entrada, migração aditiva de comprovantes, menu de Produtos e testes SQL/HTTP e de interface. Importações fiscais mantêm seu comportamento.
