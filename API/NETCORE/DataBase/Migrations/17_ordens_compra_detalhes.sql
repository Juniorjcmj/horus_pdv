/**
 * Migração 17 — Expansão das Ordens de Compra.
 * Adiciona campos comerciais, previsão de entrega, frete, desconto, motivo de cancelamento e auditoria de recebimento.
 */

IF COL_LENGTH(N'OrdemCompra', N'PrevisaoEntrega') IS NULL
BEGIN
    ALTER TABLE OrdemCompra ADD PrevisaoEntrega DATETIMEOFFSET NULL;
END;

IF COL_LENGTH(N'OrdemCompra', N'CondicaoPagamento') IS NULL
BEGIN
    ALTER TABLE OrdemCompra ADD CondicaoPagamento NVARCHAR(100) NULL;
END;

IF COL_LENGTH(N'OrdemCompra', N'FormaPagamento') IS NULL
BEGIN
    ALTER TABLE OrdemCompra ADD FormaPagamento NVARCHAR(50) NULL;
END;

IF COL_LENGTH(N'OrdemCompra', N'ValorFrete') IS NULL
BEGIN
    ALTER TABLE OrdemCompra ADD ValorFrete DECIMAL(18,2) NOT NULL CONSTRAINT DF_OC_Frete DEFAULT 0;
END;

IF COL_LENGTH(N'OrdemCompra', N'ValorDesconto') IS NULL
BEGIN
    ALTER TABLE OrdemCompra ADD ValorDesconto DECIMAL(18,2) NOT NULL CONSTRAINT DF_OC_Desconto DEFAULT 0;
END;

IF COL_LENGTH(N'OrdemCompra', N'MotivoCancelamento') IS NULL
BEGIN
    ALTER TABLE OrdemCompra ADD MotivoCancelamento NVARCHAR(300) NULL;
END;

IF COL_LENGTH(N'OrdemCompra', N'ReceivedBy') IS NULL
BEGIN
    ALTER TABLE OrdemCompra ADD ReceivedBy NVARCHAR(40) NULL;
END;

IF COL_LENGTH(N'OrdemCompra', N'ReceivedByName') IS NULL
BEGIN
    ALTER TABLE OrdemCompra ADD ReceivedByName NVARCHAR(180) NULL;
END;

IF COL_LENGTH(N'OrdemCompraItens', N'DataValidade') IS NULL
BEGIN
    ALTER TABLE OrdemCompraItens ADD DataValidade DATETIMEOFFSET NULL;
END;
