# Gateway/API fiscal interoperability checks

Build the API and Gateway first in the same configuration. This executable calls the real LAN `LocalFiscalSigner` with a temporary, self-signed test certificate, then sends its exact signed XML to `FiscalGatewayImport.Validate`.

It covers both fiscal environments, CPF/CNPJ consumers, fractional quantities, discounts, mixed cash/PIX payments, cash change, and preservation of the original XML/key/QR/number. It verifies that the Zeus deserialization/serialization still has a valid signature without signing again. It also rejects mismatched issuer, environment, consumer, items, discounts, change, payment type, cash received, and payment allocations that preserve the same grand total. It never sends HTTP, consults SEFAZ, opens a database, or loads a real certificate.

Example from the repository root after building both projects in Debug:

```powershell
dotnet run --project API/tests/FiscalGatewayInteropChecks -- "$PWD/API/NETCORE/bin/Debug/net8.0" "$PWD/GATEWAY/src/HorusGateway/bin/Debug/net8.0"
```
