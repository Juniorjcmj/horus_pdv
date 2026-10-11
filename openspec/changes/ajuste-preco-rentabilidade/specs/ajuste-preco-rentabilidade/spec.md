## Purpose

Permitir decisões de preço durante a análise de lucro e markup do produto, sincronizando indicadores e persistindo o novo preço de venda.

## ADDED Requirements

### Requirement: Synchronized pricing decision
The system SHALL allow editing sale price, gross profit in currency, gross margin percentage and markup percentage using the current purchase cost as the fixed basis and rounding sale prices to cents.

#### Scenario: Edit markup
- **WHEN** a product costing 10 reais receives markup of 80 percent
- **THEN** the proposed sale is 18 reais, profit is 8 reais and gross margin is approximately 44.44 percent

#### Scenario: Invalid basis or percentage
- **WHEN** cost is absent or an input cannot yield a valid positive sale price
- **THEN** the adjustment cannot be saved and the reason is displayed

### Requirement: Explicit scoped price saving
The system SHALL save the proposed price only on explicit confirmation, preserve all unrelated product and historical sales data, restrict updates to the company and existing product-editing roles, and return the saved indicators.

#### Scenario: Decision confirmed
- **WHEN** the user confirms a valid proposed price
- **THEN** sale price and derived indicators are saved and the original filtered list reflects them

#### Scenario: Concurrent change
- **WHEN** cost or sale price differs from the consulted basis
- **THEN** the update is rejected without overwriting the newer price and the user can refresh the consultation

#### Scenario: Lost response
- **WHEN** the same confirmed price is requested again with unchanged cost
- **THEN** the result is returned without changing unrelated data
