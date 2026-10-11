# Download da documentação da API

O Administrador Geral pode usar **Gerenciamento Geral → Documentação da API → Baixar
documentação JSON**. A rota `GET /api/Admin/Empresas/documentacao-api` retorna um
arquivo `quack-pdv-api-openapi.json` em OpenAPI 3.0, gerado da API em execução.

Inclui as rotas do API Explorer, parâmetros, contratos de requisição/resposta e
schemas disponíveis, comentários XML existentes, formas de autenticação e endereço
da API. Não consulta o banco nem exporta dados ou valores de credenciais. A sessão
deve pertencer a um usuário com perfil principal `administrador` na `empresa-principal`.

A exportação funciona em produção sem habilitar o Swagger público. A disponibilidade
de campos de resposta e explicações depende da documentação declarada nos controllers
e modelos. Publicar novas rotas/modelos atualiza a próxima exportação.

## Verificação

Este programa sobe um host HTTP local em ambiente Production, com usuário simulado
somente no teste. Não acessa SQL Server, SEFAZ, OpenRouter nem a API publicada.
Verifica acesso, cabeçalhos de download, JSON, presença de todos os endpoints e
referências dos schemas. Também verifica que a rota pública de Swagger não foi aberta.

Depois de compilar a API:

```powershell
dotnet build API/NETCORE/HORUSPDV-API.csproj --no-restore
dotnet run --project API/tests/ApiDocumentationChecks -- "$PWD/API/NETCORE/bin/Debug/net8.0"
```

Se a API estiver compilada em outra pasta, passe `-p:ApiOutput=PASTA` ao compilar o
projeto de teste e execute sua DLL passando a mesma pasta. O JSON usado no teste
fica nessa pasta para inspeção.

O teste de interface `FRONTEND/playwright.api-documentation.config.ts` verifica o
download real do navegador, carregamento, acesso pela tela, falhas, resposta inválida
e repetição após atualizar o servidor. A atualização exige publicar API e frontend.
