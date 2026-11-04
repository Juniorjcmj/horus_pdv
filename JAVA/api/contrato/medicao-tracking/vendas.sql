-- Carga de vendas da medicao (tarefa 7.3). Variaveis sqlcmd: N (vendas), PREFIXO (evita colisao entre rodadas).
-- Cada venda e uma transacao: 1 Vendas + 3 VendaItens + 1 VendaPagamentos + 3 EstoqueMovimentacoes + 3 UPDATE em Produtos.
-- A cada 1000 vendas amostra as paginas de version store do tempdb; imprime tempo total e pico.
SET NOCOUNT ON;
DECLARE @i INT = 1, @n INT = $(N), @t0 DATETIME2(7) = SYSDATETIME(), @pico BIGINT = 0, @atual BIGINT;
DECLARE @vid NVARCHAR(40), @p1 INT, @p2 INT, @p3 INT, @agora DATETIMEOFFSET = SYSDATETIMEOFFSET();
WHILE @i <= @n
BEGIN
    SET @vid = CONCAT(N'v$(PREFIXO)-', @i);
    SET @p1 = 1 + ABS(CHECKSUM(NEWID())) % 2000;
    SET @p2 = 1 + ABS(CHECKSUM(NEWID())) % 2000;
    SET @p3 = 1 + ABS(CHECKSUM(NEWID())) % 2000;
    BEGIN TRAN;
    INSERT INTO dbo.Vendas (Id, CompanyId, SaleNumber, CustomerName, CustomerCpf, PaymentType, OperatorName, SaleDate, TotalAmount, Status)
    VALUES (@vid, N'emp-med', @vid, N'Consumidor', N'', N'dinheiro', N'Operador', @agora, 23.70, N'concluida');
    INSERT INTO dbo.VendaItens (Id, VendaId, ProductCode, ProductName, Quantity, UnitPrice, ItemTotal, Desconto)
    VALUES (CONCAT(@vid, N'-1'), @vid, CONCAT(N'C', @p1), N'Item', 1, 7.90, 7.90, 0),
           (CONCAT(@vid, N'-2'), @vid, CONCAT(N'C', @p2), N'Item', 1, 7.90, 7.90, 0),
           (CONCAT(@vid, N'-3'), @vid, CONCAT(N'C', @p3), N'Item', 1, 7.90, 7.90, 0);
    INSERT INTO dbo.VendaPagamentos (Id, CompanyId, VendaId, PaymentType, Amount, CreatedAt)
    VALUES (CONCAT(@vid, N'-pg'), N'emp-med', @vid, N'dinheiro', 23.70, @agora);
    INSERT INTO dbo.EstoqueMovimentacoes (Id, CompanyId, ProdutoId, Tipo, Quantidade, EstoqueAnterior, EstoqueNovo, Motivo, UsuarioId, UsuarioNome, CriadoEm, DepositoId)
    VALUES (CONCAT(@vid, N'-m1'), N'emp-med', CONCAT(N'p', @p1), N'saida', 1, 0, 0, N'venda', N'u1', N'Operador', SYSDATETIME(), N'dep'),
           (CONCAT(@vid, N'-m2'), N'emp-med', CONCAT(N'p', @p2), N'saida', 1, 0, 0, N'venda', N'u1', N'Operador', SYSDATETIME(), N'dep'),
           (CONCAT(@vid, N'-m3'), N'emp-med', CONCAT(N'p', @p3), N'saida', 1, 0, 0, N'venda', N'u1', N'Operador', SYSDATETIME(), N'dep');
    UPDATE dbo.Produtos SET ProductQnt = ProductQnt - 1 WHERE Id = CONCAT(N'p', @p1);
    UPDATE dbo.Produtos SET ProductQnt = ProductQnt - 1 WHERE Id = CONCAT(N'p', @p2);
    UPDATE dbo.Produtos SET ProductQnt = ProductQnt - 1 WHERE Id = CONCAT(N'p', @p3);
    COMMIT;
    IF @i % 1000 = 0
    BEGIN
        SELECT @atual = SUM(version_store_reserved_page_count) FROM tempdb.sys.dm_db_file_space_usage;
        IF @atual > @pico SET @pico = @atual;
    END;
    SET @i += 1;
END;
SELECT DB_NAME() AS Banco, $(N) AS Vendas, DATEDIFF(MILLISECOND, @t0, SYSDATETIME()) AS Milissegundos,
       @pico * 8 / 1024.0 AS PicoVersionStoreMB_Amostrado;
