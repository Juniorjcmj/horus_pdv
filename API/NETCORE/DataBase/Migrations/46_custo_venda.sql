-- Mantém o custo apurado ao registrar a venda. NULL identifica vendas anteriores,
-- cujo custo histórico não pode ser reconstruído a partir do cadastro atual.
IF COL_LENGTH(N'dbo.VendaItens', N'CostPrice') IS NULL
    ALTER TABLE dbo.VendaItens ADD CostPrice DECIMAL(18,4) NULL;
