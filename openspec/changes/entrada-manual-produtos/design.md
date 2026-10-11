## Context

See proposal.md. Existing invoice receipts use a scoped SQL transaction and the NotasEntradaArquivo history. Stock receiving currently recalculates sale prices from desired margin, which must remain unchanged for invoice imports.

## Goals / Non-Goals

Goals: atomic manual receiving, optional supplier, durable attachments and replay protection. Non-goals: SEFAZ queries or changes to fiscal classification and existing invoice behavior.

## Decisions

- Dedicated endpoint and screen; reuse the archive and stock/lot repositories with their transaction scope.
- Extend stock receiving with optional preservation/explicit sale price, leaving defaults compatible.
- Compute converted quantities/costs on the server and archive purchase quantities, unit conversion and applied prices.
- Stable client operation UUID and request fingerprint, transaction application locks and fiscal-key identity prevent double receipts. Same-operation retries return the original result; changed retries are rejected.
- Stage new products until confirmation, use generated identifiers/codes where omitted. Existing products retain fiscal metadata and supplier.
- Store bounded PDF/image/XML attachments as binary with authenticated company-scoped download.

## Risks / Trade-offs

- Lost response after commit → retry the same payload with the same operation token.
- Partial failures → single transaction includes products, costs, lots, history and attachment.
- Pack/unit confusion → explicit purchase unit/factor, converted cost and stock preview.
- Disk/database growth → one optional attachment up to 10 MB per entry, never duplicate base64 in history JSON.

## Migration Plan

Add nullable attachment columns via the database initializer, deploy API then frontend. Existing rows and API consumers remain compatible. Rollback application code without dropping stored history.
