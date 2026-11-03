/*
 * Seed do contrato (D6 / D9): empresas, usuarios, cadastros, produtos, estoque, lotes, notas, inventario, compras e promocoes
 * usados pelos cenarios gravados na API .NET e repetidos na API Java.
 * Aplicado nos DOIS lados (SQL Server do ambiente .NET e do Testcontainers da API Java) depois que o esquema existe.
 * Todos os usuarios tem a senha  Senha#Quack2026  (hash PBKDF2 fixo, mesmo dos vetores de compatibilidade).
 * Idempotente.
 */
SET NOCOUNT ON;
SET QUOTED_IDENTIFIER ON;

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
    (N'usr-ct-admin',     N'empresa-principal',      N'11111111111',    N'Ana Administradora', N'admin@contrato.quack',               N'11999990001', N'administrador', N'ativo',   N'',           N'2026-01-15'),
    (N'usr-ct-gerente',   N'empresa-principal',      N'22222222222',    N'Gil Gerente',        N'gerente@contrato.quack',             N'11999990002', N'gerente',       N'ativo',   N'',           N'2026-01-15'),
    (N'usr-ct-gerfin',    N'empresa-principal',      N'33333333333',    N'Gal Gerente Fin',    N'gerfin@contrato.quack',              N'11999990003', N'gerente',       N'ativo',   N'financeiro', N'2026-01-15'),
    (N'usr-ct-atend',     N'empresa-principal',      N'44444444444',    N'Ari Atendente',      N'atendente@contrato.quack',           N'11999990004', N'atendente',     N'ativo',   N'',           N'2026-01-15'),
    (N'usr-ct-caixa',     N'empresa-principal',      N'55555555555',    N'Cai Caixa',          N'caixa@contrato.quack',               N'11999990005', N'caixa',         N'ativo',   N'',           N'2026-01-15'),
    (N'usr-ct-inativo',   N'empresa-principal',      N'66666666666',    N'Ivo Inativo',        N'inativo@contrato.quack',             N'11999990006', N'atendente',     N'inativo', N'',           N'2026-01-15'),
    (N'usr-ct-bloqueio',  N'empresa-principal',      N'77777777777',    N'Bia Bloqueio',       N'bloqueio@contrato.quack',            N'11999990007', N'atendente',     N'ativo',   N'',           N'2026-01-15'),
    (N'usr-ct-bloq2',     N'empresa-principal',      N'88888888888',    N'Beto Bloqueio',      N'bloqueio2@contrato.quack',           N'11999990008', N'atendente',     N'ativo',   N'',           N'2026-01-15'),
    (N'usr-ct-troca',     N'empresa-principal',      N'99999999999',    N'Tina Troca',         N'troca@contrato.quack',               N'11999990009', N'atendente',     N'ativo',   N'',           N'2026-01-15'),
    (N'usr-ct-estoq',     N'empresa-principal',      N'10101010101',    N'Edu Estoquista',     N'estoquista@contrato.quack',          N'11999990013', N'estoquista',    N'ativo',   N'',           N'2026-01-16'),
    (N'usr-ct-apr-adm',   N'emp-contrato-aprovada',  N'11222333000181', N'Ada Aprovada',       N'adm.aprovada@contrato.quack',        N'11999990010', N'administrador', N'ativo',   N'',           N'2026-01-15'),
    (N'usr-ct-apr-estoq', N'emp-contrato-aprovada',  N'20202020202',    N'Eva Estoquista',     N'estoquista.aprovada@contrato.quack', N'11999990014', N'estoquista',    N'ativo',   N'',           N'2026-01-16'),
    (N'usr-ct-pen-adm',   N'emp-contrato-pendente',  N'98765432100',    N'Paulo Pendente',     N'adm.pendente@contrato.quack',        N'11999990011', N'administrador', N'ativo',   N'',           N'2026-01-15'),
    (N'usr-ct-blq-adm',   N'emp-contrato-bloqueada', N'11144477735',    N'Bruna Bloqueada',    N'adm.bloqueada@contrato.quack',       N'11999990012', N'administrador', N'ativo',   N'',           N'2026-01-15')
) AS origem (Id, CompanyId, Cpf, Name, Email, Phone, Role, Status, PerfisAdicionais, CreatedAt)
ON alvo.Id = origem.Id
WHEN NOT MATCHED THEN
    INSERT (Id, CompanyId, Cpf, Name, Email, Phone, Role, Status, CreatedAt, LastLoginAt, PasswordHash, MustChangePassword, PerfisAdicionais)
    VALUES (origem.Id, origem.CompanyId, origem.Cpf, origem.Name, origem.Email, origem.Phone, origem.Role,
            origem.Status, origem.CreatedAt, N'-', @hash, 0, origem.PerfisAdicionais);

-- Sincroniza prazos padrao de validade das categorias de referencia nos dois bancos (.NET e Java V3).
UPDATE Categorias SET PrazoPadraoValidadeDias = NULL, DiasAlertaValidade = NULL
 WHERE CompanyId = N'empresa-principal' AND Id NOT LIKE N'cat-ct-%';

MERGE Categorias AS catAlvo
USING (VALUES
    (N'cat-acougue',             5,  2),
    (N'cat-acougue-aves',        5,  2),
    (N'cat-acougue-bovinos',     5,  2),
    (N'cat-acougue-suinos',      5,  2),
    (N'cat-bebidas',           180, 30),
    (N'cat-bomboniere',        180, 30),
    (N'cat-congelados',        180, 15),
    (N'cat-frios',              20,  5),
    (N'cat-frios-embutidos',    20,  5),
    (N'cat-frios-queijos',      30,  7),
    (N'cat-hortifruti',          7,  2),
    (N'cat-hortifruti-frutas',   7,  2),
    (N'cat-hortifruti-legumes',  7,  2),
    (N'cat-hortifruti-verduras', 5,  2),
    (N'cat-laticinios',         30,  7),
    (N'cat-laticinios-iogurtes',30,  7),
    (N'cat-laticinios-leite',  120, 15),
    (N'cat-laticinios-manteigas',60, 10),
    (N'cat-mercearia',         365, 60),
    (N'cat-padaria',             5,  2),
    (N'cat-padaria-biscoitos', 180, 30),
    (N'cat-padaria-bolos',       5,  2),
    (N'cat-padaria-paes',        3,  1)
) AS prazos (Id, Prazo, Alerta)
ON catAlvo.Id = prazos.Id AND catAlvo.CompanyId = N'empresa-principal'
WHEN MATCHED THEN
    UPDATE SET PrazoPadraoValidadeDias = prazos.Prazo, DiasAlertaValidade = prazos.Alerta;

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
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id=N'cat-ct-pereciveis')
    INSERT INTO Categorias (Id, CompanyId, Nome, Ordem, Ativa, CategoriaPaiId, PrazoPadraoValidadeDias, DiasAlertaValidade)
    VALUES (N'cat-ct-pereciveis', N'empresa-principal', N'Perecíveis Contrato', 91, 1, NULL, 30, 10);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id=N'cat-ct-laticinios')
    INSERT INTO Categorias (Id, CompanyId, Nome, Ordem, Ativa, CategoriaPaiId, PrazoPadraoValidadeDias, DiasAlertaValidade)
    VALUES (N'cat-ct-laticinios', N'empresa-principal', N'Laticínios Contrato', 92, 1, N'cat-ct-pereciveis', NULL, NULL);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id=N'cat-ct-inv')
    INSERT INTO Categorias (Id, CompanyId, Nome, Ordem, Ativa, CategoriaPaiId, PrazoPadraoValidadeDias, DiasAlertaValidade)
    VALUES (N'cat-ct-inv', N'empresa-principal', N'Categoria Inventário', 93, 1, NULL, NULL, NULL);

IF NOT EXISTS (SELECT 1 FROM Fornecedores WHERE Id=N'fr-ct-sol')
    INSERT INTO Fornecedores (Id, CompanyId, CompanyName, FantasyName, Cnpj, Cep, City, State, Address, Neighborhood, StreetComplement, Number, ReferencePoint, Telephone, Cellphone, Email)
    VALUES (N'fr-ct-sol', N'empresa-principal', N'Distribuidora Sol SA', N'Distribuidora Sol', N'11222333000181', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'');
IF NOT EXISTS (SELECT 1 FROM Fornecedores WHERE Id=N'fr-ct-livre')
    INSERT INTO Fornecedores (Id, CompanyId, CompanyName, FantasyName, Cnpj, Cep, City, State, Address, Neighborhood, StreetComplement, Number, ReferencePoint, Telephone, Cellphone, Email)
    VALUES (N'fr-ct-livre', N'empresa-principal', N'Fornecedor Livre SA', N'Fornecedor Livre', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'');
IF NOT EXISTS (SELECT 1 FROM Fornecedores WHERE Id=N'fr-ct-exclusivo')
    INSERT INTO Fornecedores (Id, CompanyId, CompanyName, FantasyName, Cnpj, Cep, City, State, Address, Neighborhood, StreetComplement, Number, ReferencePoint, Telephone, Cellphone, Email)
    VALUES (N'fr-ct-exclusivo', N'empresa-principal', N'Distribuidora Exclusiva SA', N'Distribuidora Exclusiva', N'99888777000166', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'');

IF NOT EXISTS (SELECT 1 FROM Clientes WHERE Id=N'cl-ct-ana')
    INSERT INTO Clientes (Id, CompanyId, CustomerName, Document, BirthDate, Age, Cep, City, State, Address, Neighborhood, StreetComplement, Number, ReferencePoint, Telephone, Cellphone, Email, IndIeDest, LimiteCredito, SaldoDevedor)
    VALUES (N'cl-ct-ana', N'empresa-principal', N'Ana Cliente', N'12345678901', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'11999990000', N'', N'', 9, 100, 0);
IF NOT EXISTS (SELECT 1 FROM Clientes WHERE Id=N'cl-ct-devedor')
    INSERT INTO Clientes (Id, CompanyId, CustomerName, Document, BirthDate, Age, Cep, City, State, Address, Neighborhood, StreetComplement, Number, ReferencePoint, Telephone, Cellphone, Email, IndIeDest, LimiteCredito, SaldoDevedor)
    VALUES (N'cl-ct-devedor', N'empresa-principal', N'Zelia Devedora', N'98765432100', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'', N'11999990000', N'', N'', 9, 100, 50);

-- Produtos de empresa-principal (Fase 2 + Fase 3 D9) -------------------------
IF NOT EXISTS (SELECT 1 FROM Produtos WHERE Id=N'pr-ct-1')
    INSERT INTO Produtos (Id, CompanyId, ProductImageUrl, ProductImageName, ProductName, ProductCode, ProductSupplier, ProductDescription, EstoqueMinimo, ProductQnt, ProductUnitPrice, ProductSalePrice, TotalPriceOnProduct, Ncm, Cfop, OrigemMercadoria, UnidadeComercial, UnidadeTributavel, Gtin, AliquotaIcms, CstPis, CstCofins, ControlaValidade, DiasAlertaValidade, UnidadeCompra, FatorConversao, QtdEmbalagem, PesoLiquidoKg, PesoBrutoKg, LarguraCm, AlturaCm, ComprimentoCm, EstoqueMaximo, CustoMedio, CustoComImposto, CustoSemImposto, DescontoMaximoPercentual, ComissaoPercentual, MarkupCadastrado, MarkupPraticado, Lucro, CategoriaId)
    VALUES (N'pr-ct-1', N'empresa-principal', N'', N'', N'Produto Contrato 1', N'CT001', N'DISTRIBUIDORA SOL', N'', 0, 10, 2, 3, 20, N'22021000', N'5102', 0, N'UN', N'UN', N'', 0, N'', N'', 0, 0, N'UN', 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, N'cat-ct-sub');
IF NOT EXISTS (SELECT 1 FROM Produtos WHERE Id=N'pr-ct-2')
    INSERT INTO Produtos (Id, CompanyId, ProductImageUrl, ProductImageName, ProductName, ProductCode, ProductSupplier, ProductDescription, EstoqueMinimo, ProductQnt, ProductUnitPrice, ProductSalePrice, TotalPriceOnProduct, Ncm, Cfop, OrigemMercadoria, UnidadeComercial, UnidadeTributavel, Gtin, AliquotaIcms, CstPis, CstCofins, ControlaValidade, DiasAlertaValidade, UnidadeCompra, FatorConversao, QtdEmbalagem, PesoLiquidoKg, PesoBrutoKg, LarguraCm, AlturaCm, ComprimentoCm, EstoqueMaximo, CustoMedio, CustoComImposto, CustoSemImposto, DescontoMaximoPercentual, ComissaoPercentual, MarkupCadastrado, MarkupPraticado, Lucro, CategoriaId)
    VALUES (N'pr-ct-2', N'empresa-principal', N'', N'', N'Produto Contrato 2', N'CT002', N'', N'', 0, 10, 2, 3, 20, N'22021000', N'5102', 0, N'UN', N'UN', N'', 0, N'', N'', 0, 0, N'UN', 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, NULL);
IF NOT EXISTS (SELECT 1 FROM Produtos WHERE Id=N'pr-ct-val')
    INSERT INTO Produtos (Id, CompanyId, ProductImageUrl, ProductImageName, ProductName, ProductCode, ProductSupplier, SupplierId, ProductDescription, EstoqueMinimo, ProductQnt, ProductUnitPrice, ProductSalePrice, TotalPriceOnProduct, Ncm, Cfop, OrigemMercadoria, UnidadeComercial, UnidadeTributavel, Gtin, AliquotaIcms, CstPis, CstCofins, ControlaValidade, DataValidade, DiasAlertaValidade, UnidadeCompra, FatorConversao, QtdEmbalagem, PesoLiquidoKg, PesoBrutoKg, LarguraCm, AlturaCm, ComprimentoCm, EstoqueMaximo, CustoMedio, CustoComImposto, CustoSemImposto, DescontoMaximoPercentual, ComissaoPercentual, MarkupCadastrado, MarkupPraticado, Lucro, CategoriaId)
    VALUES (N'pr-ct-val', N'empresa-principal', N'', N'', N'Iogurte Grego Açaí 170g', N'CT003', N'Distribuidora Exclusiva', N'fr-ct-exclusivo', N'Produto perecível com acentuação', 0, 12.0000, 3.4567, 5.9000, 41.48, N'04031000', N'5102', 0, N'UN', N'UN', N'7891000100103', 0, N'07', N'07', 1, '2026-10-15', 7, N'UN', 1, 1, 0.1700, 0.1850, 0, 0, 0, 0, 3.4567, 3.4567, 3.4567, 0, 0, 0, 70.6830, 2.44, N'cat-ct-laticinios');
IF NOT EXISTS (SELECT 1 FROM Produtos WHERE Id=N'pr-ct-val2')
    INSERT INTO Produtos (Id, CompanyId, ProductImageUrl, ProductImageName, ProductName, ProductCode, ProductSupplier, SupplierId, ProductDescription, EstoqueMinimo, ProductQnt, ProductUnitPrice, ProductSalePrice, TotalPriceOnProduct, Ncm, Cfop, OrigemMercadoria, UnidadeComercial, UnidadeTributavel, Gtin, AliquotaIcms, CstPis, CstCofins, ControlaValidade, DataValidade, DiasAlertaValidade, UnidadeCompra, FatorConversao, QtdEmbalagem, PesoLiquidoKg, PesoBrutoKg, LarguraCm, AlturaCm, ComprimentoCm, EstoqueMaximo, CustoMedio, CustoComImposto, CustoSemImposto, DescontoMaximoPercentual, ComissaoPercentual, MarkupCadastrado, MarkupPraticado, Lucro, CategoriaId)
    VALUES (N'pr-ct-val2', N'empresa-principal', N'', N'', N'Pão de Forma Integral', N'CT004', N'Distribuidora Exclusiva', N'fr-ct-exclusivo', N'', 0, 8.0000, 4.2500, 7.5000, 34.00, N'19059010', N'5102', 0, N'UN', N'UN', N'7891000100202', 0, N'07', N'07', 1, '2026-11-08', 0, N'UN', 1, 1, 0, 0, 0, 0, 0, 0, 4.2500, 4.2500, 4.2500, 0, 0, 0, 76.4706, 3.25, N'cat-ct-pereciveis');
IF NOT EXISTS (SELECT 1 FROM Produtos WHERE Id=N'pr-ct-minimo')
    INSERT INTO Produtos (Id, CompanyId, ProductImageUrl, ProductImageName, ProductName, ProductCode, ProductSupplier, SupplierId, ProductDescription, EstoqueMinimo, ProductQnt, ProductUnitPrice, ProductSalePrice, TotalPriceOnProduct, Ncm, Cfop, OrigemMercadoria, UnidadeComercial, UnidadeTributavel, Gtin, AliquotaIcms, CstPis, CstCofins, ControlaValidade, DiasAlertaValidade, UnidadeCompra, FatorConversao, QtdEmbalagem, PesoLiquidoKg, PesoBrutoKg, LarguraCm, AlturaCm, ComprimentoCm, EstoqueMaximo, CustoMedio, CustoComImposto, CustoSemImposto, DescontoMaximoPercentual, ComissaoPercentual, MarkupCadastrado, MarkupPraticado, Lucro, CategoriaId)
    VALUES (N'pr-ct-minimo', N'empresa-principal', N'', N'', N'Café Torrado Especial 500g', N'CT005', N'Distribuidora Exclusiva', N'fr-ct-exclusivo', N'', 15.0000, 0.0000, 12.5000, 19.9000, 0.00, N'09012100', N'5102', 0, N'UN', N'UN', N'7891000100301', 0, N'07', N'07', 0, 0, N'UN', 1, 1, 0.5000, 0.5200, 0, 0, 0, 50.0000, 12.5000, 12.5000, 12.5000, 0, 0, 0, 59.2000, 7.40, N'cat-ct-inv');
IF NOT EXISTS (SELECT 1 FROM Produtos WHERE Id=N'pr-ct-kg')
    INSERT INTO Produtos (Id, CompanyId, ProductImageUrl, ProductImageName, ProductName, ProductCode, ProductSupplier, ProductDescription, EstoqueMinimo, ProductQnt, ProductUnitPrice, ProductSalePrice, TotalPriceOnProduct, Ncm, Cfop, OrigemMercadoria, UnidadeComercial, UnidadeTributavel, Gtin, AliquotaIcms, CstPis, CstCofins, ControlaValidade, DiasAlertaValidade, UnidadeCompra, FatorConversao, QtdEmbalagem, PesoLiquidoKg, PesoBrutoKg, LarguraCm, AlturaCm, ComprimentoCm, EstoqueMaximo, CustoMedio, CustoComImposto, CustoSemImposto, DescontoMaximoPercentual, ComissaoPercentual, MarkupCadastrado, MarkupPraticado, Lucro, CategoriaId)
    VALUES (N'pr-ct-kg', N'empresa-principal', N'', N'', N'Queijo Minas Frescal KG', N'CT006', N'', N'', 0, 5.5000, 28.1250, 42.9000, 154.69, N'04061010', N'5102', 0, N'KG', N'KG', N'SEM GTIN', 0, N'07', N'07', 0, 0, N'KG', 1, 1, 0, 0, 0, 0, 0, 0, 28.1250, 28.1250, 28.1250, 0, 0, 0, 52.5333, 14.78, N'cat-ct-inv');
IF NOT EXISTS (SELECT 1 FROM Produtos WHERE Id=N'pr-ct-inv')
    INSERT INTO Produtos (Id, CompanyId, ProductImageUrl, ProductImageName, ProductName, ProductCode, ProductSupplier, ProductDescription, EstoqueMinimo, ProductQnt, ProductUnitPrice, ProductSalePrice, TotalPriceOnProduct, Ncm, Cfop, OrigemMercadoria, UnidadeComercial, UnidadeTributavel, Gtin, AliquotaIcms, CstPis, CstCofins, ControlaValidade, DiasAlertaValidade, UnidadeCompra, FatorConversao, QtdEmbalagem, PesoLiquidoKg, PesoBrutoKg, LarguraCm, AlturaCm, ComprimentoCm, EstoqueMaximo, CustoMedio, CustoComImposto, CustoSemImposto, DescontoMaximoPercentual, ComissaoPercentual, MarkupCadastrado, MarkupPraticado, Lucro, CategoriaId)
    VALUES (N'pr-ct-inv', N'empresa-principal', N'', N'', N'Biscoito Água e Sal', N'CT007', N'', N'', 0, 10.0000, 2.5000, 4.5000, 25.00, N'19053100', N'5102', 0, N'UN', N'UN', N'7891000100707', 0, N'07', N'07', 0, 0, N'UN', 1, 1, 0, 0, 0, 0, 0, 0, 2.5000, 2.5000, 2.5000, 0, 0, 0, 80.0000, 2.00, NULL);
IF NOT EXISTS (SELECT 1 FROM Produtos WHERE Id=N'pr-ct-excluir')
    INSERT INTO Produtos (Id, CompanyId, ProductImageUrl, ProductImageName, ProductName, ProductCode, ProductSupplier, ProductDescription, EstoqueMinimo, ProductQnt, ProductUnitPrice, ProductSalePrice, TotalPriceOnProduct, Ncm, Cfop, OrigemMercadoria, UnidadeComercial, UnidadeTributavel, Gtin, AliquotaIcms, CstPis, CstCofins, ControlaValidade, DiasAlertaValidade, UnidadeCompra, FatorConversao, QtdEmbalagem, PesoLiquidoKg, PesoBrutoKg, LarguraCm, AlturaCm, ComprimentoCm, EstoqueMaximo, CustoMedio, CustoComImposto, CustoSemImposto, DescontoMaximoPercentual, ComissaoPercentual, MarkupCadastrado, MarkupPraticado, Lucro, CategoriaId)
    VALUES (N'pr-ct-excluir', N'empresa-principal', N'', N'', N'Produto Descartável Contrato', N'CT008', N'', N'', 0, 3.0000, 1.0000, 2.0000, 3.00, N'22021000', N'5102', 0, N'UN', N'UN', N'SEM GTIN', 0, N'07', N'07', 0, 0, N'UN', 1, 1, 0, 0, 0, 0, 0, 0, 1.0000, 1.0000, 1.0000, 0, 0, 0, 100.0000, 1.00, NULL);

-- Lotes e Modo FEFO (D9) ----------------------------------------------------
IF NOT EXISTS (SELECT 1 FROM LoteConfig WHERE CompanyId=N'empresa-principal')
    INSERT INTO LoteConfig (CompanyId, Modo, AtualizadoEm)
    VALUES (N'empresa-principal', N'sombra', '2026-01-15T12:00:00+00:00');

IF NOT EXISTS (SELECT 1 FROM ProdutoLotes WHERE Id=N'lote-ct-vencido')
    INSERT INTO ProdutoLotes (Id, CompanyId, ProdutoId, NumeroLote, DataValidade, QtdInicial, Origem, ValidadePadrao, CriadoEm, CriadoPorNome, QtdAtual)
    VALUES (N'lote-ct-vencido', N'empresa-principal', N'pr-ct-val', N'L-VENC', '2026-10-15', 3.0000, N'manual', 0, '2026-01-15T12:00:00+00:00', N'Contrato', 3.0000);
IF NOT EXISTS (SELECT 1 FROM ProdutoLotes WHERE Id=N'lote-ct-alerta')
    INSERT INTO ProdutoLotes (Id, CompanyId, ProdutoId, NumeroLote, DataValidade, QtdInicial, Origem, ValidadePadrao, CriadoEm, CriadoPorNome, QtdAtual)
    VALUES (N'lote-ct-alerta', N'empresa-principal', N'pr-ct-val', N'L-ALERTA', '2026-11-06', 4.0000, N'nfe', 0, '2026-01-16T12:00:00+00:00', N'Importação NF-e', 4.0000);
IF NOT EXISTS (SELECT 1 FROM ProdutoLotes WHERE Id=N'lote-ct-ok')
    INSERT INTO ProdutoLotes (Id, CompanyId, ProdutoId, NumeroLote, DataValidade, QtdInicial, Origem, ValidadePadrao, CriadoEm, CriadoPorNome, QtdAtual)
    VALUES (N'lote-ct-ok', N'empresa-principal', N'pr-ct-val', N'L-FUTURO', '2027-06-01', 5.0000, N'manual', 0, '2026-01-17T12:00:00+00:00', N'Contrato', 5.0000);
IF NOT EXISTS (SELECT 1 FROM ProdutoLotes WHERE Id=N'lote-ct-pao1')
    INSERT INTO ProdutoLotes (Id, CompanyId, ProdutoId, NumeroLote, DataValidade, QtdInicial, Origem, ValidadePadrao, CriadoEm, CriadoPorNome, QtdAtual)
    VALUES (N'lote-ct-pao1', N'empresa-principal', N'pr-ct-val2', N'L-PAO-1', '2026-11-08', 8.0000, N'manual', 1, '2026-01-15T12:00:00+00:00', N'Contrato', 8.0000);

-- Movimentacoes iniciais de estoque (somente empresa-principal) -------------
IF NOT EXISTS (SELECT 1 FROM EstoqueMovimentacoes WHERE Id=N'mov-ct-seed1')
    INSERT INTO EstoqueMovimentacoes (Id, CompanyId, ProdutoId, Tipo, Quantidade, EstoqueAnterior, EstoqueNovo, Motivo, UsuarioId, UsuarioNome, CriadoEm, CustoUnitario, OrigemTipo, OrigemId, DepositoId)
    VALUES (N'mov-ct-seed1', N'empresa-principal', N'pr-ct-1', N'entrada', 10.000, 0.000, 10.000, N'Carga inicial contrato', N'usr-ct-gerente', N'Gil Gerente', '2026-01-15T12:00:00', 2.0000, N'cadastro', N'pr-ct-1', N'loja');
IF NOT EXISTS (SELECT 1 FROM EstoqueMovimentacoes WHERE Id=N'mov-ct-seed2')
    INSERT INTO EstoqueMovimentacoes (Id, CompanyId, ProdutoId, Tipo, Quantidade, EstoqueAnterior, EstoqueNovo, Motivo, UsuarioId, UsuarioNome, CriadoEm, CustoUnitario, OrigemTipo, OrigemId, DepositoId)
    VALUES (N'mov-ct-seed2', N'empresa-principal', N'pr-ct-val', N'perda', 1.000, 13.000, 12.000, N'Avaria transporte', N'usr-ct-gerente', N'Gil Gerente', '2026-01-16T12:00:00', 3.4567, N'ajuste_manual', NULL, N'loja');

-- Notas de entrada semeadas em 2026-01 (XML, manual .NET e sem-documento) ---
DECLARE @xmlSeed VARBINARY(MAX) = CONVERT(VARBINARY(MAX), CAST('<?xml version="1.0" encoding="UTF-8"?><nfeProc xmlns="http://www.portalfiscal.inf.br/nfe" versao="4.00"><NFe><infNFe Id="NFe33260111222333000181550010000001001100001005" versao="4.00"><ide><cUF>33</cUF><mod>55</mod><serie>1</serie><nNF>100</nNF><dhEmi>2026-01-10T10:00:00-03:00</dhEmi><tpEmis>1</tpEmis><tpAmb>2</tpAmb></ide><emit><CNPJ>11222333000181</CNPJ><xNome>Distribuidora Sol SA</xNome><xFant>Distribuidora Sol</xFant><enderEmit><xLgr>Rua do Sol</xLgr><nro>50</nro><xBairro>Centro</xBairro><xMun>Rio de Janeiro</xMun><UF>RJ</UF><CEP>20010000</CEP></enderEmit></emit><det nItem="1"><prod><cProd>SOL-01</cProd><cEAN>SEM GTIN</cEAN><xProd>Produto Contrato 1</xProd><NCM>22021000</NCM><uCom>UN</uCom><qCom>2.0000</qCom><vUnCom>2.0000</vUnCom><vProd>4.00</vProd></prod></det><total><ICMSTot><vNF>4.00</vNF></ICMSTot></total></infNFe></NFe><protNFe versao="4.00"><infProt><chNFe>33260111222333000181550010000001001100001005</chNFe></infProt></protNFe></nfeProc>' AS VARCHAR(MAX)));

IF NOT EXISTS (SELECT 1 FROM NotasEntradaArquivo WHERE Id=N'ne-ct-xml')
    INSERT INTO NotasEntradaArquivo (Id, CompanyId, Identidade, ChaveAcesso, Modelo, NumeroNota, Serie, FornecedorNome, FornecedorCnpj, DataEmissao, ValorNota, ValorEntrada, QuantidadeItens, Origem, XmlOriginal, EntradaJson, UsuarioId, UsuarioNome, CriadaEm, ComprovanteNome, ComprovanteTipo, ComprovanteOriginal, OperacaoManualId, Estornada)
    VALUES (N'ne-ct-xml', N'empresa-principal',
            CONVERT(CHAR(64), HASHBYTES('SHA2_256', '33260111222333000181550010000001001100001005'), 2),
            '33260111222333000181550010000001001100001005', 55, N'100', N'1', N'Distribuidora Sol SA', '11222333000181',
            N'2026-01-10T10:00:00-03:00', 4.00, 4.00, 1, 'xml', @xmlSeed,
            N'{"fornecedor":{"cnpj":"11222333000181","companyName":"Distribuidora Sol SA","fantasyName":"Distribuidora Sol","cep":"","city":"","state":"","address":"","neighborhood":"","number":"","telephone":""},"itens":[{"numeroItem":1,"produtoExistenteId":"pr-ct-1","produtoExistenteNome":"Produto Contrato 1","codigoFornecedor":"SOL-01","productCode":"CT001","productName":"Produto Contrato 1","gtin":"SEM GTIN","ncm":"22021000","cest":null,"unidadeComercial":"UN","quantidade":"2","precoCusto":"2,0000","precoVenda":"3,0000","dataValidade":null,"numeroLote":null,"margemPercentual":null}]}',
            N'usr-ct-gerente', N'Gil Gerente', '2026-01-15T13:00:00', N'recibo.pdf', 'application/pdf', 0x255044462D312E340A, NULL, 0);

IF NOT EXISTS (SELECT 1 FROM NotasEntradaArquivo WHERE Id=N'ne-ct-manual')
    INSERT INTO NotasEntradaArquivo (Id, CompanyId, Identidade, ChaveAcesso, Modelo, NumeroNota, Serie, FornecedorNome, FornecedorCnpj, DataEmissao, ValorNota, ValorEntrada, QuantidadeItens, Origem, XmlOriginal, EntradaJson, UsuarioId, UsuarioNome, CriadaEm, ComprovanteNome, ComprovanteTipo, ComprovanteOriginal, OperacaoManualId, Estornada)
    VALUES (N'ne-ct-manual', N'empresa-principal',
            'F5133EE07B89EDE1C4D089B421CE97E693F233DEBA9C6C0EE7F58EEDE66A7120',
            NULL, 0, N'REC-SEED-01', N'', N'Distribuidora Sol SA', '11222333000181',
            N'2026-01-15', 4.00, 4.00, 1, 'manual', NULL,
            N'{"operacaoId":"11111111222233334444555555555555","fingerprint":"5787435820AB52D17F90C87E5FFD9D14F5CDE4706366DBB4102E4D02F19DBD42","dataCompra":"2026-01-15","tipoDocumento":"recibo","observacao":"Entrada manual do seed","fornecedor":{"cnpj":"11222333000181","companyName":"Distribuidora Sol SA","fantasyName":"Distribuidora Sol","cep":"","city":"","state":"","address":"","neighborhood":"","number":"","telephone":""},"itens":[{"quantidadeCompra":"2","unidadeCompra":"UN","fatorConversao":"1","custoCompra":"2,00","alterarPrecoVenda":false,"estoqueAntes":"10","estoqueDepois":"12","numeroItem":0,"produtoExistenteId":"pr-ct-2","produtoExistenteNome":null,"codigoFornecedor":null,"productCode":"CT002","productName":"Produto Contrato 2","gtin":"SEM GTIN","ncm":"00000000","cest":null,"unidadeComercial":"UN","quantidade":"2","precoCusto":"2","precoVenda":"3","dataValidade":null,"numeroLote":null,"margemPercentual":null}],"resultado":{"notaEntradaId":"","fornecedorCriado":false,"produtosCriados":0,"produtosAtualizados":1}}',
            N'usr-ct-gerente', N'Gil Gerente', '2026-01-15T14:00:00', NULL, NULL, NULL, '11111111222233334444555555555555', 0);

IF NOT EXISTS (SELECT 1 FROM NotasEntradaArquivo WHERE Id=N'ne-ct-semdoc')
    INSERT INTO NotasEntradaArquivo (Id, CompanyId, Identidade, ChaveAcesso, Modelo, NumeroNota, Serie, FornecedorNome, FornecedorCnpj, DataEmissao, ValorNota, ValorEntrada, QuantidadeItens, Origem, XmlOriginal, EntradaJson, UsuarioId, UsuarioNome, CriadaEm, ComprovanteNome, ComprovanteTipo, ComprovanteOriginal, OperacaoManualId, Estornada)
    VALUES (N'ne-ct-semdoc', N'empresa-principal',
            '0000000000000000000000000000000000000000000000000000000000000102',
            NULL, 0, N'', N'', N'Distribuidora Sol SA', '11222333000181',
            NULL, NULL, 10.00, 1, 'sem-documento', NULL,
            N'{"fornecedor":{"cnpj":"11222333000181","companyName":"Distribuidora Sol SA","fantasyName":"Distribuidora Sol"},"itens":[]}',
            N'usr-ct-gerente', N'Gil Gerente', '2026-01-15T15:00:00', NULL, NULL, NULL, NULL, 0);

-- Inventario aberto (D9) ----------------------------------------------------
IF NOT EXISTS (SELECT 1 FROM Inventarios WHERE Id=N'inv-ct-aberto')
    INSERT INTO Inventarios (Id, CompanyId, Numero, Descricao, Escopo, EscopoJson, Status, AbertoPorId, AbertoPorNome, AbertoEm, ZerarNaoContados)
    VALUES (N'inv-ct-aberto', N'empresa-principal', 1, N'Inventário Aberto Contrato', N'produtos', N'["pr-ct-inv"]', N'em_contagem', N'usr-ct-gerente', N'Gil Gerente', '2026-01-15T12:00:00+00:00', 0);

IF NOT EXISTS (SELECT 1 FROM InventarioItens WHERE InventarioId=N'inv-ct-aberto' AND ProdutoId=N'pr-ct-inv')
    INSERT INTO InventarioItens (InventarioId, ProdutoId, CompanyId, Codigo, Nome, Unidade, Custo, Recontar)
    VALUES (N'inv-ct-aberto', N'pr-ct-inv', N'empresa-principal', N'CT007', N'Biscoito Água e Sal', N'UN', 2.5000, 0);

IF NOT EXISTS (SELECT 1 FROM InventarioEventos WHERE Id=N'ie-ct-1')
    INSERT INTO InventarioEventos (Id, InventarioId, CompanyId, Tipo, OcorridoEm, UsuarioId, UsuarioNome, Ip, Terminal, Descricao)
    VALUES (N'ie-ct-1', N'inv-ct-aberto', N'empresa-principal', N'abertura', '2026-01-15T12:00:00+00:00', N'usr-ct-gerente', N'Gil Gerente', N'127.0.0.1', N'WEB', N'Inventário #1 aberto (escopo: produtos, 1 itens).');

-- Ordens de compra pendente e recebida + outra empresa (D9) -----------------
-- (A ordem de outra empresa fica em emp-contrato-pendente para nao bloquear DELETE Empresas em emp-contrato-aprovada.)
IF NOT EXISTS (SELECT 1 FROM OrdemCompra WHERE Id=N'oc-ct-pendente')
    INSERT INTO OrdemCompra (Id, CompanyId, OrderNumber, SupplierId, SupplierName, SupplierCnpj, Status, CreatedBy, CreatedByName, CreatedAt, Note, TotalEstimado, ValorFrete, ValorDesconto)
    VALUES (N'oc-ct-pendente', N'empresa-principal', N'40', N'fr-ct-sol', N'Distribuidora Sol', N'11222333000181', 0, N'usr-ct-gerente', N'Gil Gerente', '2026-01-15T12:00:00+00:00', N'Pedido pendente do seed', 20.00, 0, 0);

IF NOT EXISTS (SELECT 1 FROM OrdemCompraItens WHERE Id=N'oci-ct-1')
    INSERT INTO OrdemCompraItens (Id, OrdemCompraId, ProductCode, ProductName, Quantity, UnitCost, ItemTotal, QuantityReceived, DataValidade)
    VALUES (N'oci-ct-1', N'oc-ct-pendente', N'CT001', N'Produto Contrato 1', 10.0000, 2.0000, 20.00, 0.0000, NULL);

IF NOT EXISTS (SELECT 1 FROM OrdemCompra WHERE Id=N'oc-ct-recebida')
    INSERT INTO OrdemCompra (Id, CompanyId, OrderNumber, SupplierId, SupplierName, SupplierCnpj, Status, CreatedBy, CreatedByName, CreatedAt, ReceivedAt, ReceivedBy, ReceivedByName, Note, TotalEstimado, ValorFrete, ValorDesconto)
    VALUES (N'oc-ct-recebida', N'empresa-principal', N'41', N'fr-ct-exclusivo', N'Distribuidora Exclusiva', N'99888777000166', 1, N'usr-ct-gerente', N'Gil Gerente', '2026-01-16T12:00:00+00:00', '2026-01-17T12:00:00+00:00', N'usr-ct-gerente', N'Gil Gerente', N'Pedido já recebido', 17.00, 0, 0);

IF NOT EXISTS (SELECT 1 FROM OrdemCompraItens WHERE Id=N'oci-ct-2')
    INSERT INTO OrdemCompraItens (Id, OrdemCompraId, ProductCode, ProductName, Quantity, UnitCost, ItemTotal, QuantityReceived, DataValidade)
    VALUES (N'oci-ct-2', N'oc-ct-recebida', N'CT004', N'Pão de Forma Integral', 4.0000, 4.2500, 17.00, 4.0000, '2026-11-08T00:00:00+00:00');

IF NOT EXISTS (SELECT 1 FROM OrdemCompra WHERE Id=N'oc-ct-outra')
    INSERT INTO OrdemCompra (Id, CompanyId, OrderNumber, SupplierId, SupplierName, SupplierCnpj, Status, CreatedBy, CreatedByName, CreatedAt, Note, TotalEstimado, ValorFrete, ValorDesconto)
    VALUES (N'oc-ct-outra', N'emp-contrato-pendente', N'900', NULL, N'Fornecedor não informado', N'', 0, N'usr-ct-pen-adm', N'Paulo Pendente', '2026-01-15T12:00:00+00:00', N'', 10.00, 0, 0);

-- Uma promocao de cada tipo + VendaItens para resultado (D9) ----------------
IF NOT EXISTS (SELECT 1 FROM Promocoes WHERE Id=N'prm-ct-perc')
    INSERT INTO Promocoes (Id, CompanyId, Nome, Tipo, ValorDesconto, PrecoFixo, QuantidadeLeva, QuantidadePaga, QuantidadeMinima, InicioVigencia, FimVigencia, Ativa, CategoriaId, CriadoPor, CriadoEm)
    VALUES (N'prm-ct-perc', N'empresa-principal', N'Promo Percentual 10%', N'desconto_percentual', 10.00, NULL, NULL, NULL, NULL, '2025-01-01T00:00:00-03:00', '2030-12-31T23:59:59-03:00', 1, NULL, N'Gil Gerente', '2026-01-15T12:00:00-03:00');
IF NOT EXISTS (SELECT 1 FROM PromocaoProdutos WHERE Id=N'prmp-ct-1')
    INSERT INTO PromocaoProdutos (Id, PromocaoId, ProdutoId) VALUES (N'prmp-ct-1', N'prm-ct-perc', N'pr-ct-1');

IF NOT EXISTS (SELECT 1 FROM Promocoes WHERE Id=N'prm-ct-valor')
    INSERT INTO Promocoes (Id, CompanyId, Nome, Tipo, ValorDesconto, PrecoFixo, QuantidadeLeva, QuantidadePaga, QuantidadeMinima, InicioVigencia, FimVigencia, Ativa, CategoriaId, CriadoPor, CriadoEm)
    VALUES (N'prm-ct-valor', N'empresa-principal', N'Promo Desconto Valor', N'desconto_valor', 0.50, NULL, NULL, NULL, NULL, '2025-01-01T00:00:00-03:00', '2030-12-31T23:59:59-03:00', 1, NULL, N'Gil Gerente', '2026-01-15T13:00:00-03:00');
IF NOT EXISTS (SELECT 1 FROM PromocaoProdutos WHERE Id=N'prmp-ct-2')
    INSERT INTO PromocaoProdutos (Id, PromocaoId, ProdutoId) VALUES (N'prmp-ct-2', N'prm-ct-valor', N'pr-ct-2');

IF NOT EXISTS (SELECT 1 FROM Promocoes WHERE Id=N'prm-ct-fixo')
    INSERT INTO Promocoes (Id, CompanyId, Nome, Tipo, ValorDesconto, PrecoFixo, QuantidadeLeva, QuantidadePaga, QuantidadeMinima, InicioVigencia, FimVigencia, Ativa, CategoriaId, CriadoPor, CriadoEm)
    VALUES (N'prm-ct-fixo', N'empresa-principal', N'Promo Preço Fixo', N'preco_fixo', NULL, 4.99, NULL, NULL, NULL, '2025-01-01T00:00:00-03:00', '2030-12-31T23:59:59-03:00', 1, NULL, N'Gil Gerente', '2026-01-15T14:00:00-03:00');
IF NOT EXISTS (SELECT 1 FROM PromocaoProdutos WHERE Id=N'prmp-ct-3')
    INSERT INTO PromocaoProdutos (Id, PromocaoId, ProdutoId) VALUES (N'prmp-ct-3', N'prm-ct-fixo', N'pr-ct-val');

IF NOT EXISTS (SELECT 1 FROM Promocoes WHERE Id=N'prm-ct-lxpy')
    INSERT INTO Promocoes (Id, CompanyId, Nome, Tipo, ValorDesconto, PrecoFixo, QuantidadeLeva, QuantidadePaga, QuantidadeMinima, InicioVigencia, FimVigencia, Ativa, CategoriaId, CriadoPor, CriadoEm)
    VALUES (N'prm-ct-lxpy', N'empresa-principal', N'Promo Leve 3 Pague 2', N'leve_x_pague_y', NULL, NULL, 3, 2, NULL, '2025-01-01T00:00:00-03:00', '2030-12-31T23:59:59-03:00', 1, NULL, N'Gil Gerente', '2026-01-15T15:00:00-03:00');
IF NOT EXISTS (SELECT 1 FROM PromocaoProdutos WHERE Id=N'prmp-ct-4')
    INSERT INTO PromocaoProdutos (Id, PromocaoId, ProdutoId) VALUES (N'prmp-ct-4', N'prm-ct-lxpy', N'pr-ct-val2');

IF NOT EXISTS (SELECT 1 FROM Promocoes WHERE Id=N'prm-ct-combo')
    INSERT INTO Promocoes (Id, CompanyId, Nome, Tipo, ValorDesconto, PrecoFixo, QuantidadeLeva, QuantidadePaga, QuantidadeMinima, InicioVigencia, FimVigencia, Ativa, CategoriaId, CriadoPor, CriadoEm)
    VALUES (N'prm-ct-combo', N'empresa-principal', N'Promo Combo 3 por 10', N'combo_quantidade', NULL, 10.00, NULL, NULL, 3, '2025-01-01T00:00:00-03:00', '2030-12-31T23:59:59-03:00', 1, NULL, N'Gil Gerente', '2026-01-15T16:00:00-03:00');
IF NOT EXISTS (SELECT 1 FROM PromocaoProdutos WHERE Id=N'prmp-ct-5')
    INSERT INTO PromocaoProdutos (Id, PromocaoId, ProdutoId) VALUES (N'prmp-ct-5', N'prm-ct-combo', N'pr-ct-inv');

IF NOT EXISTS (SELECT 1 FROM Promocoes WHERE Id=N'prm-ct-atacado')
    INSERT INTO Promocoes (Id, CompanyId, Nome, Tipo, ValorDesconto, PrecoFixo, QuantidadeLeva, QuantidadePaga, QuantidadeMinima, InicioVigencia, FimVigencia, Ativa, CategoriaId, CriadoPor, CriadoEm)
    VALUES (N'prm-ct-atacado', N'empresa-principal', N'Promo Atacado 5+', N'preco_atacado', NULL, 2.50, NULL, NULL, 5, '2025-01-01T00:00:00-03:00', '2030-12-31T23:59:59-03:00', 1, N'cat-ct-inv', N'Gil Gerente', '2026-01-15T17:00:00-03:00');

IF NOT EXISTS (SELECT 1 FROM Vendas WHERE Id=N'vd-ct')
    INSERT INTO Vendas (Id, CompanyId, SaleNumber, CustomerName, CustomerCpf, PaymentType, OperatorName, SaleDate, TotalAmount, Status, ClientSaleId)
    VALUES (N'vd-ct', N'empresa-principal', N'CT0001', N'Ana Cliente', N'', N'Dinheiro', N'Contrato', '2026-01-15T12:00:00+00:00', 5.40, N'concluida', N'client-sale-ct');
IF NOT EXISTS (SELECT 1 FROM VendaItens WHERE Id=N'vi-ct-1')
    INSERT INTO VendaItens (Id, VendaId, ProductCode, ProductName, Quantity, UnitPrice, ItemTotal, Desconto, PromocaoId, CostPrice)
    VALUES (N'vi-ct-1', N'vd-ct', N'CT001', N'Produto Contrato 1', 2.0000, 3.0000, 5.40, 0.60, N'prm-ct-perc', 2.0000);

IF NOT EXISTS (SELECT 1 FROM GatewayCreditGrants WHERE GrantId=N'grant-ct')
    INSERT INTO GatewayCreditGrants (CompanyId, GrantId, CustomerId, StoreId, GatewayId, Epoch, TokenId, OperationId, RequestHash, AmountCents, ConsumedCents, Closed, ExpiresAt, GrantJson)
    VALUES (N'empresa-principal', N'grant-ct', N'cl-ct-devedor', N'store-ct', N'gateway-ct', N'epoch-ct', N'token-ct', N'operation-ct', N'0000000000000000000000000000000000000000000000000000000000000000', 3000, 0, 0, '2036-01-01T00:00:00+00:00', N'{}');

-- Dados de emp-contrato-aprovada (Fase 2, isolados) -------------------------
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
