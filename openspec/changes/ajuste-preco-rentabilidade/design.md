## Context

O resumo compartilhado pelo cadastro e relatórios calcula indicadores usando os preços formatados em reais. O PUT atual substitui todo o cadastro, inclusive estoque, e não é adequado a uma decisão de preço.

## Goals / Non-Goals

**Goals:** cálculo sincronizado, preço em centavos, atualização parcial autorizada e retorno imediato ao filtro original.

**Non-Goals:** editar custo, estoque, tributação, margem desejada para entradas futuras ou vendas históricas.

## Decisions

- Usar um único campo de edição como fonte da prévia; derivar venda arredondada para centavos e demais indicadores a partir dela. O texto digitado permanece durante a edição e normaliza ao sair do campo.
- Fórmulas inversas: venda = custo + lucro; venda = custo × (1 + markup/100); venda = custo/(1 - margem/100). Margem abaixo de 100%, markup acima de -100%, venda positiva; prejuízo permitido e mostrado.
- Percentuais exigem custo positivo; custo ausente bloqueia ajuste. A interface preserva os estados de consulta de produtos antigos incompletos.
- PATCH de preço recebe novo preço, custo e preço consultados. Uma transação bloqueia a linha, compara a base e altera apenas venda, lucro e markup praticado. Repetição do mesmo resultado devolve sucesso; conflito solicita nova consulta.
- Manter permissões de edição de produtos já existentes (administrador, gerente e atendente), com a consulta disponível aos demais perfis. Gravar os indicadores calculados pelo servidor.

## Risks / Trade-offs

- Arredondamento de centavos modifica o percentual solicitado → apresentar percentuais efetivos após normalização.
- Outra pessoa altera custo/preço → rejeitar alteração sem substituir dados mais recentes e oferecer atualização da consulta.
- Perda de resposta → PATCH idempotente e preservação dos valores para tentar novamente.

## Migration Plan

Publicar API antes do frontend. Nenhuma alteração de esquema; reversão por publicação da versão anterior.
