-- Dados-base para a medicao (tarefa 7.3): 2000 produtos. Rode no banco ja com o esquema aplicado.
SET NOCOUNT ON;
DECLARE @i INT = 1;
WHILE @i <= 2000
BEGIN
    INSERT INTO dbo.Produtos (Id, CompanyId, ProductImageUrl, ProductImageName, ProductName, ProductCode, ProductSupplier,
        ProductDescription, EstoqueMinimo, ProductQnt, ProductUnitPrice, ProductSalePrice, TotalPriceOnProduct, Ncm, Cfop,
        OrigemMercadoria, UnidadeComercial, UnidadeTributavel, Gtin, AliquotaIcms, CstPis, CstCofins, ControlaValidade,
        DiasAlertaValidade, UnidadeCompra, FatorConversao, QtdEmbalagem, PesoLiquidoKg, PesoBrutoKg, LarguraCm, AlturaCm,
        ComprimentoCm, EstoqueMaximo, CustoMedio, CustoComImposto, CustoSemImposto, DescontoMaximoPercentual,
        ComissaoPercentual, MarkupCadastrado, MarkupPraticado, Lucro)
    VALUES (CONCAT(N'p', @i), N'emp-med', N'', N'', CONCAT(N'Produto medicao ', @i), CONCAT(N'C', @i), N'Fornecedor X',
        N'Descricao do produto de medicao para dimensionar a linha', 5, 100000, 4.50, 7.90, 450000.00, '21069090', '5102',
        0, N'UN', N'UN', N'7890000000000', 0, N'01', N'01', 0,
        0, N'UN', 1, 1, 0.5, 0.55, 10, 10,
        10, 200000, 4.50, 4.50, 4.50, 0,
        0, 75, 75, 0);
    SET @i += 1;
END;
SELECT COUNT(*) AS Produtos FROM dbo.Produtos;
