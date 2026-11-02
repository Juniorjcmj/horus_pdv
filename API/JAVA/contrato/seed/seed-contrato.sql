/*
 * Seed do contrato (D6): empresas e usuarios usados pelos cenarios gravados na API .NET e repetidos na API Java.
 * Aplicado nos DOIS lados (SQL Server do ambiente .NET e do Testcontainers da API Java) depois que o esquema existe.
 * Todos os usuarios tem a senha  Senha#Quack2026  (hash PBKDF2 fixo, mesmo dos vetores de compatibilidade).
 * Idempotente.
 */
SET NOCOUNT ON;

-- A API .NET semeia o usuario de demonstracao usr-001; a Java nao. Tira dos dois lados para as listagens baterem.
DELETE FROM Usuarios WHERE Id = N'usr-001';

-- Empresas ------------------------------------------------------------------
IF NOT EXISTS (SELECT 1 FROM Empresas WHERE Id = N'emp-contrato-aprovada')
    INSERT INTO Empresas (Id, FantasyName, CorporateName, Cnpj, Email, Status, RejectionReason)
    VALUES (N'emp-contrato-aprovada', N'Mercado Aprovado', N'Mercado Aprovado LTDA', N'11.222.333/0001-81',
            N'aprovada@contrato.quack', N'aprovada', NULL);

IF NOT EXISTS (SELECT 1 FROM Empresas WHERE Id = N'emp-contrato-pendente')
    INSERT INTO Empresas (Id, FantasyName, CorporateName, Cnpj, Email, Status, RejectionReason)
    VALUES (N'emp-contrato-pendente', N'Mercado Pendente', N'Mercado Pendente LTDA', N'45.723.174/0001-10',
            N'pendente@contrato.quack', N'pendente', NULL);

IF NOT EXISTS (SELECT 1 FROM Empresas WHERE Id = N'emp-contrato-bloqueada')
    INSERT INTO Empresas (Id, FantasyName, CorporateName, Cnpj, Email, Status, RejectionReason)
    VALUES (N'emp-contrato-bloqueada', N'Mercado Bloqueado', N'Mercado Bloqueado LTDA', N'34.028.316/0001-03',
            N'bloqueada@contrato.quack', N'bloqueada', N'Inadimplência');

-- Usuarios ------------------------------------------------------------------
DECLARE @hash NVARCHAR(300) = N'100000.Q0wmjZSGSpZjzetxaWmnOg==./e7RA0660AYv/UNXRsWH7jS/LW9C9uhWJccn0quZxXs=';

MERGE Usuarios AS alvo
USING (VALUES
    (N'usr-ct-admin',    N'empresa-principal',     N'11111111111', N'Ana Administradora', N'admin@contrato.quack',    N'11999990001', N'administrador', N'ativo',   N''),
    (N'usr-ct-gerente',  N'empresa-principal',     N'22222222222', N'Gil Gerente',        N'gerente@contrato.quack',   N'11999990002', N'gerente',       N'ativo',   N''),
    (N'usr-ct-gerfin',   N'empresa-principal',     N'33333333333', N'Gal Gerente Fin',    N'gerfin@contrato.quack',    N'11999990003', N'gerente',       N'ativo',   N'financeiro'),
    (N'usr-ct-atend',    N'empresa-principal',     N'44444444444', N'Ari Atendente',      N'atendente@contrato.quack', N'11999990004', N'atendente',     N'ativo',   N''),
    (N'usr-ct-caixa',    N'empresa-principal',     N'55555555555', N'Cai Caixa',          N'caixa@contrato.quack',     N'11999990005', N'caixa',         N'ativo',   N''),
    (N'usr-ct-inativo',  N'empresa-principal',     N'66666666666', N'Ivo Inativo',        N'inativo@contrato.quack',   N'11999990006', N'atendente',     N'inativo', N''),
    (N'usr-ct-bloqueio', N'empresa-principal',     N'77777777777', N'Bia Bloqueio',       N'bloqueio@contrato.quack',  N'11999990007', N'atendente',     N'ativo',   N''),
    (N'usr-ct-bloq2',    N'empresa-principal',     N'88888888888', N'Beto Bloqueio',      N'bloqueio2@contrato.quack', N'11999990008', N'atendente',     N'ativo',   N''),
    (N'usr-ct-troca',    N'empresa-principal',     N'99999999999', N'Tina Troca',         N'troca@contrato.quack',     N'11999990009', N'atendente',     N'ativo',   N''),
    (N'usr-ct-apr-adm',  N'emp-contrato-aprovada', N'11222333000181', N'Ada Aprovada',       N'adm.aprovada@contrato.quack', N'11999990010', N'administrador', N'ativo', N''),
    (N'usr-ct-pen-adm',  N'emp-contrato-pendente', N'98765432100', N'Paulo Pendente',     N'adm.pendente@contrato.quack', N'11999990011', N'administrador', N'ativo', N''),
    (N'usr-ct-blq-adm',  N'emp-contrato-bloqueada',N'11144477735', N'Bruna Bloqueada',    N'adm.bloqueada@contrato.quack', N'11999990012', N'administrador', N'ativo', N'')
) AS origem (Id, CompanyId, Cpf, Name, Email, Phone, Role, Status, PerfisAdicionais)
ON alvo.Id = origem.Id
WHEN NOT MATCHED THEN
    INSERT (Id, CompanyId, Cpf, Name, Email, Phone, Role, Status, CreatedAt, LastLoginAt, PasswordHash, MustChangePassword, PerfisAdicionais)
    VALUES (origem.Id, origem.CompanyId, origem.Cpf, origem.Name, origem.Email, origem.Phone, origem.Role,
            origem.Status, N'2026-01-15', N'-', @hash, 0, origem.PerfisAdicionais);

-- Fase 2: fixtures de cadastros (dados sinteticos, somente ambiente de contrato).
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id=N'cat-ct-dep')
    INSERT INTO Categorias (Id, CompanyId, Nome, Ordem, Ativa, CategoriaPaiId)
    VALUES (N'cat-ct-dep', N'empresa-principal', N'Departamento Contrato', 90, 1, NULL);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id=N'cat-ct-sub')
    INSERT INTO Categorias (Id, CompanyId, Nome, Ordem, Ativa, CategoriaPaiId)
    VALUES (N'cat-ct-sub', N'empresa-principal', N'Subcategoria Contrato', 90, 1, N'cat-ct-dep');
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id=N'cat-ct-livre')
    INSERT INTO Categorias (Id, CompanyId, Nome, Ordem, Ativa, CategoriaPaiId)
    VALUES (N'cat-ct-livre', N'empresa-principal', N'Categoria Livre', 90, 1, NULL);
IF NOT EXISTS (SELECT 1 FROM Fornecedores WHERE Id=N'fr-ct-sol')
    INSERT INTO Fornecedores (Id, CompanyId, CompanyName, FantasyName, Cnpj, Cep, City, State, Address, Neighborhood, StreetComplement, Number, ReferencePoint, Telephone, Cellphone, Email)
    VALUES (N'fr-ct-sol', N'empresa-principal', N'Distribuidora Sol SA', N'Distribuidora Sol', N'11222333000181', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'');
IF NOT EXISTS (SELECT 1 FROM Fornecedores WHERE Id=N'fr-ct-livre')
    INSERT INTO Fornecedores (Id, CompanyId, CompanyName, FantasyName, Cnpj, Cep, City, State, Address, Neighborhood, StreetComplement, Number, ReferencePoint, Telephone, Cellphone, Email)
    VALUES (N'fr-ct-livre', N'empresa-principal', N'Fornecedor Livre SA', N'Fornecedor Livre', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'');
IF NOT EXISTS (SELECT 1 FROM Clientes WHERE Id=N'cl-ct-ana')
    INSERT INTO Clientes (Id, CompanyId, CustomerName, Document, BirthDate, Age, Cep, City, State, Address, Neighborhood, StreetComplement, Number, ReferencePoint, Telephone, Cellphone, Email, IndIeDest, LimiteCredito, SaldoDevedor)
    VALUES (N'cl-ct-ana', N'empresa-principal', N'Ana Cliente', N'12345678901', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'11999990000', N'', N'', 9, 100, 0);
IF NOT EXISTS (SELECT 1 FROM Clientes WHERE Id=N'cl-ct-devedor')
    INSERT INTO Clientes (Id, CompanyId, CustomerName, Document, BirthDate, Age, Cep, City, State, Address, Neighborhood, StreetComplement, Number, ReferencePoint, Telephone, Cellphone, Email, IndIeDest, LimiteCredito, SaldoDevedor)
    VALUES (N'cl-ct-devedor', N'empresa-principal', N'Zelia Devedora', N'98765432100', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'11999990000', N'', N'', 9, 100, 50);
IF NOT EXISTS (SELECT 1 FROM Produtos WHERE Id=N'pr-ct-1')
    INSERT INTO Produtos (Id, CompanyId, ProductImageUrl, ProductImageName, ProductName, ProductCode, ProductSupplier, ProductDescription, EstoqueMinimo, ProductQnt, ProductUnitPrice, ProductSalePrice, TotalPriceOnProduct, Ncm, Cfop, OrigemMercadoria, UnidadeComercial, UnidadeTributavel, Gtin, AliquotaIcms, CstPis, CstCofins, ControlaValidade, DiasAlertaValidade, UnidadeCompra, FatorConversao, QtdEmbalagem, PesoLiquidoKg, PesoBrutoKg, LarguraCm, AlturaCm, ComprimentoCm, EstoqueMaximo, CustoMedio, CustoComImposto, CustoSemImposto, DescontoMaximoPercentual, ComissaoPercentual, MarkupCadastrado, MarkupPraticado, Lucro, CategoriaId)
    VALUES (N'pr-ct-1', N'empresa-principal', N'', N'', N'Produto Contrato 1', N'CT001', N'DISTRIBUIDORA SOL', N'', 0, 10, 2, 3, 20, N'22021000', N'5102', 0, N'UN', N'UN', N'', 0, N'', N'', 0, 0, N'UN', 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, N'cat-ct-sub');
IF NOT EXISTS (SELECT 1 FROM Produtos WHERE Id=N'pr-ct-2')
    INSERT INTO Produtos (Id, CompanyId, ProductImageUrl, ProductImageName, ProductName, ProductCode, ProductSupplier, ProductDescription, EstoqueMinimo, ProductQnt, ProductUnitPrice, ProductSalePrice, TotalPriceOnProduct, Ncm, Cfop, OrigemMercadoria, UnidadeComercial, UnidadeTributavel, Gtin, AliquotaIcms, CstPis, CstCofins, ControlaValidade, DiasAlertaValidade, UnidadeCompra, FatorConversao, QtdEmbalagem, PesoLiquidoKg, PesoBrutoKg, LarguraCm, AlturaCm, ComprimentoCm, EstoqueMaximo, CustoMedio, CustoComImposto, CustoSemImposto, DescontoMaximoPercentual, ComissaoPercentual, MarkupCadastrado, MarkupPraticado, Lucro, CategoriaId)
    VALUES (N'pr-ct-2', N'empresa-principal', N'', N'', N'Produto Contrato 2', N'CT002', N'', N'', 0, 10, 2, 3, 20, N'22021000', N'5102', 0, N'UN', N'UN', N'', 0, N'', N'', 0, 0, N'UN', 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, NULL);
IF NOT EXISTS (SELECT 1 FROM Vendas WHERE Id=N'vd-ct')
    INSERT INTO Vendas (Id, CompanyId, SaleNumber, CustomerName, CustomerCpf, PaymentType, OperatorName, SaleDate, TotalAmount, Status, ClientSaleId)
    VALUES (N'vd-ct', N'empresa-principal', N'CT0001', N'Ana Cliente', N'', N'Dinheiro', N'Contrato', '2026-01-15T12:00:00+00:00', 3, N'concluida', N'client-sale-ct');
IF NOT EXISTS (SELECT 1 FROM GatewayCreditGrants WHERE GrantId=N'grant-ct')
    INSERT INTO GatewayCreditGrants (CompanyId, GrantId, CustomerId, StoreId, GatewayId, Epoch, TokenId, OperationId, RequestHash, AmountCents, ConsumedCents, Closed, ExpiresAt, GrantJson)
    VALUES (N'empresa-principal', N'grant-ct', N'cl-ct-devedor', N'store-ct', N'gateway-ct', N'epoch-ct', N'token-ct', N'operation-ct', N'0000000000000000000000000000000000000000000000000000000000000000', 3000, 0, 0, '2036-01-01T00:00:00+00:00', N'{}');
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id=N'cat-ct-dep-outra')
    INSERT INTO Categorias (Id, CompanyId, Nome, Ordem, Ativa, CategoriaPaiId)
    VALUES (N'cat-ct-dep-outra', N'emp-contrato-aprovada', N'Departamento Contrato', 90, 1, NULL);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id=N'cat-ct-sub-outra')
    INSERT INTO Categorias (Id, CompanyId, Nome, Ordem, Ativa, CategoriaPaiId)
    VALUES (N'cat-ct-sub-outra', N'emp-contrato-aprovada', N'Subcategoria Contrato', 90, 1, N'cat-ct-dep-outra');
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id=N'cat-ct-livre-outra')
    INSERT INTO Categorias (Id, CompanyId, Nome, Ordem, Ativa, CategoriaPaiId)
    VALUES (N'cat-ct-livre-outra', N'emp-contrato-aprovada', N'Categoria Livre', 90, 1, NULL);
IF NOT EXISTS (SELECT 1 FROM Fornecedores WHERE Id=N'fr-ct-outra')
    INSERT INTO Fornecedores (Id, CompanyId, CompanyName, FantasyName, Cnpj, Cep, City, State, Address, Neighborhood, StreetComplement, Number, ReferencePoint, Telephone, Cellphone, Email)
    VALUES (N'fr-ct-outra', N'emp-contrato-aprovada', N'Distribuidora Sol SA', N'Distribuidora Sol', N'11222333000181', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'');
IF NOT EXISTS (SELECT 1 FROM Fornecedores WHERE Id=N'fr-ct-livre-outra')
    INSERT INTO Fornecedores (Id, CompanyId, CompanyName, FantasyName, Cnpj, Cep, City, State, Address, Neighborhood, StreetComplement, Number, ReferencePoint, Telephone, Cellphone, Email)
    VALUES (N'fr-ct-livre-outra', N'emp-contrato-aprovada', N'Fornecedor Livre SA', N'Fornecedor Livre', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'');
IF NOT EXISTS (SELECT 1 FROM Clientes WHERE Id=N'cl-ct-ana-outra')
    INSERT INTO Clientes (Id, CompanyId, CustomerName, Document, BirthDate, Age, Cep, City, State, Address, Neighborhood, StreetComplement, Number, ReferencePoint, Telephone, Cellphone, Email, IndIeDest, LimiteCredito, SaldoDevedor)
    VALUES (N'cl-ct-ana-outra', N'emp-contrato-aprovada', N'Ana Cliente', N'12345678901', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'11999990000', N'', N'', 9, 100, 0);
IF NOT EXISTS (SELECT 1 FROM Clientes WHERE Id=N'cl-ct-devedor-outra')
    INSERT INTO Clientes (Id, CompanyId, CustomerName, Document, BirthDate, Age, Cep, City, State, Address, Neighborhood, StreetComplement, Number, ReferencePoint, Telephone, Cellphone, Email, IndIeDest, LimiteCredito, SaldoDevedor)
    VALUES (N'cl-ct-devedor-outra', N'emp-contrato-aprovada', N'Zelia Devedora', N'98765432100', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'11999990000', N'', N'', 9, 100, 50);
IF NOT EXISTS (SELECT 1 FROM Produtos WHERE Id=N'pr-ct-1-outra')
    INSERT INTO Produtos (Id, CompanyId, ProductImageUrl, ProductImageName, ProductName, ProductCode, ProductSupplier, ProductDescription, EstoqueMinimo, ProductQnt, ProductUnitPrice, ProductSalePrice, TotalPriceOnProduct, Ncm, Cfop, OrigemMercadoria, UnidadeComercial, UnidadeTributavel, Gtin, AliquotaIcms, CstPis, CstCofins, ControlaValidade, DiasAlertaValidade, UnidadeCompra, FatorConversao, QtdEmbalagem, PesoLiquidoKg, PesoBrutoKg, LarguraCm, AlturaCm, ComprimentoCm, EstoqueMaximo, CustoMedio, CustoComImposto, CustoSemImposto, DescontoMaximoPercentual, ComissaoPercentual, MarkupCadastrado, MarkupPraticado, Lucro, CategoriaId)
    VALUES (N'pr-ct-1-outra', N'emp-contrato-aprovada', N'', N'', N'Produto Contrato 1', N'CT001', N'DISTRIBUIDORA SOL', N'', 0, 10, 2, 3, 20, N'22021000', N'5102', 0, N'UN', N'UN', N'', 0, N'', N'', 0, 0, N'UN', 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, N'cat-ct-sub-outra');
IF NOT EXISTS (SELECT 1 FROM Produtos WHERE Id=N'pr-ct-2-outra')
    INSERT INTO Produtos (Id, CompanyId, ProductImageUrl, ProductImageName, ProductName, ProductCode, ProductSupplier, ProductDescription, EstoqueMinimo, ProductQnt, ProductUnitPrice, ProductSalePrice, TotalPriceOnProduct, Ncm, Cfop, OrigemMercadoria, UnidadeComercial, UnidadeTributavel, Gtin, AliquotaIcms, CstPis, CstCofins, ControlaValidade, DiasAlertaValidade, UnidadeCompra, FatorConversao, QtdEmbalagem, PesoLiquidoKg, PesoBrutoKg, LarguraCm, AlturaCm, ComprimentoCm, EstoqueMaximo, CustoMedio, CustoComImposto, CustoSemImposto, DescontoMaximoPercentual, ComissaoPercentual, MarkupCadastrado, MarkupPraticado, Lucro, CategoriaId)
    VALUES (N'pr-ct-2-outra', N'emp-contrato-aprovada', N'', N'', N'Produto Contrato 2', N'CT002', N'', N'', 0, 10, 2, 3, 20, N'22021000', N'5102', 0, N'UN', N'UN', N'', 0, N'', N'', 0, 0, N'UN', 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, NULL);
IF NOT EXISTS (SELECT 1 FROM Vendas WHERE Id=N'vd-ct-outra')
    INSERT INTO Vendas (Id, CompanyId, SaleNumber, CustomerName, CustomerCpf, PaymentType, OperatorName, SaleDate, TotalAmount, Status, ClientSaleId)
    VALUES (N'vd-ct-outra', N'emp-contrato-aprovada', N'CT0001', N'Ana Cliente', N'', N'Dinheiro', N'Contrato', '2026-01-15T12:00:00+00:00', 3, N'concluida', N'client-sale-ct-outra');
IF NOT EXISTS (SELECT 1 FROM GatewayCreditGrants WHERE GrantId=N'grant-ct-outra')
    INSERT INTO GatewayCreditGrants (CompanyId, GrantId, CustomerId, StoreId, GatewayId, Epoch, TokenId, OperationId, RequestHash, AmountCents, ConsumedCents, Closed, ExpiresAt, GrantJson)
    VALUES (N'emp-contrato-aprovada', N'grant-ct-outra', N'cl-ct-devedor-outra', N'store-ct', N'gateway-ct', N'epoch-ct', N'token-ct', N'operation-ct', N'0000000000000000000000000000000000000000000000000000000000000000', 3000, 0, 0, '2036-01-01T00:00:00+00:00', N'{}');
IF NOT EXISTS (SELECT 1 FROM ModulosMercado WHERE Id=N'compras') INSERT INTO ModulosMercado(Id,Title) VALUES(N'compras',N'Compras e Reposição');
IF NOT EXISTS (SELECT 1 FROM ModuloMercadoRegistros WHERE Id=N'mm-ct')
    INSERT INTO ModuloMercadoRegistros (Id, CompanyId, ModuleId, Title, Description, Status, Amount, Meta)
    VALUES (N'mm-ct', N'empresa-principal', N'compras', N'Registro Contrato', N'', N'Pendente', N'', N'');
IF NOT EXISTS (SELECT 1 FROM ModuloMercadoRegistros WHERE Id=N'mm-ct-outra')
    INSERT INTO ModuloMercadoRegistros (Id, CompanyId, ModuleId, Title, Description, Status, Amount, Meta)
    VALUES (N'mm-ct-outra', N'emp-contrato-aprovada', N'compras', N'Registro Contrato', N'', N'Concluido', N'', N'');
IF NOT EXISTS(SELECT 1 FROM EmpresaTema WHERE CompanyId=N'empresa-principal') INSERT INTO EmpresaTema(CompanyId,SystemAccent,PdvAccent,UpdatedAt,UpdatedBy) VALUES(N'empresa-principal',N'#0369a1',N'#abcdef','2026-01-15T12:00:00',N'usr-ct-admin');
IF NOT EXISTS(SELECT 1 FROM TreinamentoSecoes WHERE Id=N'trs-ct') INSERT INTO TreinamentoSecoes(Id,Nome,Descricao,Ordem) VALUES(N'trs-ct',N'Treinamento Contrato',NULL,99);
UPDATE Empresas SET CreatedAt='2026-01-15T12:00:00+00:00' WHERE Id IN(N'empresa-principal',N'emp-contrato-aprovada',N'emp-contrato-pendente',N'emp-contrato-bloqueada');
UPDATE Empresas SET EmailSmtpPassword=N'enc:v1:FdmCcDDOY2RD6PZz.TgFhBrDDOZyvEdnNrP9DeQ==.nZnx9yNnKCuB5RImbERH5kiNK17YbMpW2g==', CscId=N'000001', CscCifrado=N'enc:v1:tDuKfLdnfLp1FBfw.0CPVA1DR0jjNuPw6VaVQGQ==.q3TdR9IsQ4PKzi6v5eGLW9ra8uRlLMdp' WHERE Id=N'empresa-principal';

IF NOT EXISTS (SELECT 1 FROM Fornecedores WHERE Id=N'fr-ct-exclusivo')
    INSERT INTO Fornecedores (Id, CompanyId, CompanyName, FantasyName, Cnpj, Cep, City, State, Address, Neighborhood, StreetComplement, Number, ReferencePoint, Telephone, Cellphone, Email)
    VALUES (N'fr-ct-exclusivo', N'empresa-principal', N'Distribuidora Exclusiva SA', N'Distribuidora Exclusiva', N'99888777000166', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'');
