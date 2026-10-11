## Purpose

Allow the store to receive purchases manually from Products, including purchases supported by consumer receipts, preserving inventory quantities, current costs and receipt history.

## ADDED Requirements

### Requirement: Manual receiving screen
The system SHALL offer manual entry in Products with existing-product search, barcode selection and staged new products; supplier and document SHALL be optional.

#### Scenario: New product without supplier
- **WHEN** the operator supplies description, cost, selling price and quantity for a new product without supplier
- **THEN** the product and receipt are created only on confirmation

### Requirement: Purchase conversion and prices
The system SHALL convert purchase quantities and pack costs to commercial units on the server and preserve existing sale prices unless explicitly changed.

#### Scenario: Purchase by box
- **WHEN** one box of 12 units is received for 48 reais
- **THEN** inventory increases by 12 and the unit cost is 4 reais

### Requirement: Atomic and duplicate-safe receipt
The system SHALL save the whole receipt atomically and isolate operations by company. Repeated confirmation of the same operation SHALL not add inventory again; repeated fiscal keys SHALL not be received again.

#### Scenario: Lost confirmation response
- **WHEN** a committed operation is retried with the same payload and identifier
- **THEN** the original successful result is returned without further mutations

#### Scenario: Failing receipt
- **WHEN** a product or history write fails
- **THEN** every product, lot and stock change is rolled back

### Requirement: Durable receipt history
The system SHALL archive receipt date, document reference, optional supplier, applied items, costs, conversion, responsible user and optional attachment; access SHALL be restricted to the company.

#### Scenario: Optional scanned receipt
- **WHEN** a manual receipt includes an image or PDF
- **THEN** the authorized operator can download the same file from receipt history
