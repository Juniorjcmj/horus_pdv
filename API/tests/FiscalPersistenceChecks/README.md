# Persistência fiscal isolada

Este teste exige um SQL Server descartável na porta **127.0.0.1:51461**. Recusa outro endereço ou um banco já existente sem a marca `__FiscalPersistenceOnly`. Não o execute contra a loja. Inicializa as migrações e usa somente vendas/empresas fictícias.

Após compilar a API, execute com o caminho da API compilada e um arquivo local contendo a senha fictícia:

```powershell
dotnet run --project API/tests/FiscalPersistenceChecks -- "$PWD/API/NETCORE/bin/Debug/net8.0" "C:/caminho/senha-ficticia.txt" "127.0.0.1,51461"
```

Cobre importação concorrente/replay, vínculo chave–venda–empresa, autoemissão idempotente, gravação de XML antes da transmissão, preservação em retentativas e rollback. A validação do XML Gateway e as assinaturas são cobertas separadamente por `FiscalGatewayChecks` e `FiscalReviewChecks`. Não realiza chamadas à SEFAZ.
