# Spec Delta

## MODIFIED Requirements

### Requirement: Rotina fiscal desligável
A fila fiscal mínima SHALL poder ser desligada por configuração, para que a API Java e a API .NET não processem a
mesma fila sobre o mesmo banco. No modo `nuvem` ela SHALL ficar sempre desligada. No modo `loja`, até a Fase 7, SHALL
rodar só a retomada das vendas que decidiram emitir e ficaram sem nota, porque a emissão e os demais ramos ficam com a
API .NET da nuvem.

#### Scenario: Rotina desligada
- **WHEN** a configuração da fila fiscal está desligada
- **THEN** nenhuma nota pendente muda de status pela API Java

#### Scenario: Modo loja
- **WHEN** uma venda com nota pendente é cancelada no servidor da loja
- **THEN** a loja não muda a nota; a situação final vem da nuvem pela sincronização

#### Scenario: Venda sem nota no modo loja
- **WHEN** uma venda que decidiu emitir fica sem nota pendente no servidor da loja
- **THEN** a loja grava a pendência com o próximo número da sua sequência fiscal
