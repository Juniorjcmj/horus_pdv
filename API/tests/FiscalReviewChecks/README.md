# Conferência fiscal e exportação

Compile a API com `dotnet build API/NETCORE/HORUSPDV-API.csproj --no-restore` e execute na raiz:

```powershell
dotnet run --project API/tests/FiscalReviewChecks -- "$PWD/API/NETCORE/bin/Debug/net8.0"
```

Acrescente `--live` para consultar as duas fontes oficiais públicas. Os testes normais simulam falta de internet e usam as referências datadas. Nenhum teste emite nota ou acessa a loja. Cobre NCM/GTIN/vigência, CRT, códigos não suportados, alíquotas/base/redução/totais de 2026, montagem e schema local da NFC-e assinada com certificado efêmero, composição do XML legado e preservação criptográfica da assinatura. O certificado do teste não é certificado ICP-Brasil e não é utilizado para transmissão.
