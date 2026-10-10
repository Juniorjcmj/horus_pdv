# Cadastro de produto: fornecedor opcional

Verifica o controller, a validação automática do ASP.NET, o serviço e a persistência em
SQL Server. Cobre fornecedor omitido, vazio, nulo e espaços; edição/remover fornecedor;
fornecedor cadastrado/inexistente; descrição, custo e venda obrigatórios.

Use um **container SQL Server novo e descartável**, com porta vinculada a `127.0.0.1`.
Nunca use o banco da loja. O programa recusa um banco `HorusPdv` existente sem seu marcador
de teste. A autenticação é simulada apenas no host de teste; nenhuma chamada vai à API publicada.

Primeiro compile a API usando as dependências já restauradas:

```powershell
dotnet build API/NETCORE/HORUSPDV-API.csproj --no-restore
```

Depois execute, na raiz do projeto, substituindo o arquivo da senha e a porta do container:

```powershell
dotnet run --project API/tests/ProductRegistrationChecks -- `
  "$PWD/API/NETCORE/bin/Debug/net8.0" `
  "CAMINHO_DO_ARQUIVO_DE_SENHA_DO_CONTAINER_DE_TESTE" `
  "127.0.0.1,PORTA_DO_CONTAINER_DE_TESTE"
```

O projeto referencia a API compilada e não restaura nem atualiza seus pacotes fiscais.
Os scripts SQL de inicialização são aplicados somente ao container descartável. Ao terminar,
remova esse container e o arquivo temporário da senha.

Também cobre a conferência fiscal HTTP com regime por empresa, ausência de usuário, leitura entre empresas recusada, ausência de certificado/CSC na resposta e exportação de XMLs legados completos sem alterar o banco. O host simula o usuário; a declaração de perfis do endpoint é inspecionada separadamente.
