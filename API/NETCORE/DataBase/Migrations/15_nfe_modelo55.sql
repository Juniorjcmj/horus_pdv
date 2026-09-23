/**
 * Migração 15 — Suporte a NF-e modelo 55 (venda para empresas/CNPJ).
 * Adiciona SerieNfe na tabela Empresas e colunas de destinatário na fila fiscal.
 */

-- Série da NF-e modelo 55 (separada da série NFC-e)
IF COL_LENGTH(N'Empresas', N'SerieNfe') IS NULL
BEGIN
    ALTER TABLE Empresas ADD SerieNfe INT NOT NULL DEFAULT 1;
END;

-- Dados do destinatário persistidos na fila para montagem da NF-e pelo worker
IF COL_LENGTH(N'DocumentosFiscais', N'DestinatarioJson') IS NULL
BEGIN
    ALTER TABLE DocumentosFiscais ADD DestinatarioJson NVARCHAR(MAX) NULL;
END;

IF COL_LENGTH(N'DocumentosFiscais', N'NaturezaOperacao') IS NULL
BEGIN
    ALTER TABLE DocumentosFiscais ADD NaturezaOperacao NVARCHAR(120) NULL;
END;

IF COL_LENGTH(N'DocumentosFiscais', N'ModalidadeFrete') IS NULL
BEGIN
    ALTER TABLE DocumentosFiscais ADD ModalidadeFrete TINYINT NOT NULL DEFAULT 9;
END;
