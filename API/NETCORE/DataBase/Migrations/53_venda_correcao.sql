-- Migration 53: Colunas de vínculo para correção de venda (financeiro remove itens).
-- A venda original (status 'corrigida') aponta para a nova via CorrigidaParaVendaId.
-- A nova venda aponta de volta via CorrigidaDeVendaId.

IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('Vendas') AND name = 'CorrigidaParaVendaId')
BEGIN
    ALTER TABLE Vendas ADD CorrigidaParaVendaId NVARCHAR(64) NULL;
END;

IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('Vendas') AND name = 'CorrigidaDeVendaId')
BEGIN
    ALTER TABLE Vendas ADD CorrigidaDeVendaId NVARCHAR(64) NULL;
END;
