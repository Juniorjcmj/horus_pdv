# Entrada de NF-e / NFC-e

`dotnet run --project API/tests/InvoiceImportChecks` verifica o parser dos modelos 55/65,
quantidades por peso, custos, GTIN, XML inválido e a chave NFC-e informada no incidente.
Não acessa banco, certificado ou SEFAZ.

O projeto vizinho `InvoiceImportHttpChecks` testa os endpoints da API compilada e a gravação
real em SQL Server descartável: prévia sem gravação, rejeição de segundo item inválido antes
de qualquer escrita, cadastro de produto, entrada em produto vinculado e fornecedor existente.

Compile a API com `dotnet build API/NETCORE/HORUSPDV-API.csproj --no-restore`. Execute o teste
HTTP informando a pasta `API/NETCORE/bin/Debug/net8.0`, o caminho de um arquivo com a senha
do SQL de teste e `127.0.0.1,PORTA`. Use um container SQL novo; o teste recusa um banco existente
sem seu marcador. Remova o container e o arquivo de senha ao terminar. Nunca use o banco da loja.

No frontend, `npm run test:invoice-import` confere a chave real do incidente, entrada digitada,
dados do fornecedor preservados, vínculo de produto existente, upload de XML e dígito inválido.
