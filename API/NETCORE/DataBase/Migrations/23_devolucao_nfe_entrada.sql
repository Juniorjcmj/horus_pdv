-- ============================================================================
-- Migration 23: Suporte à NF-e Modelo 55 de Devolução / Entrada Referenciada
-- ============================================================================

IF COL_LENGTH(N'DocumentosFiscais', N'ChaveReferenciada') IS NULL
BEGIN
    ALTER TABLE DocumentosFiscais ADD ChaveReferenciada CHAR(44) NULL;
END;

IF COL_LENGTH(N'DocumentosFiscais', N'DocumentoOrigemId') IS NULL
BEGIN
    ALTER TABLE DocumentosFiscais ADD DocumentoOrigemId NVARCHAR(40) NULL;
END;

IF COL_LENGTH(N'DocumentosFiscais', N'DevolvidoPorSupervisorId') IS NULL
BEGIN
    ALTER TABLE DocumentosFiscais ADD DevolvidoPorSupervisorId NVARCHAR(40) NULL;
END;

IF COL_LENGTH(N'DocumentosFiscais', N'DevolvidoPorSupervisorNome') IS NULL
BEGIN
    ALTER TABLE DocumentosFiscais ADD DevolvidoPorSupervisorNome NVARCHAR(180) NULL;
END;

IF COL_LENGTH(N'DocumentosFiscais', N'DevolvidoPorOperador') IS NULL
BEGIN
    ALTER TABLE DocumentosFiscais ADD DevolvidoPorOperador NVARCHAR(180) NULL;
END;

IF COL_LENGTH(N'DocumentosFiscais', N'DevolvidoJustificativa') IS NULL
BEGIN
    ALTER TABLE DocumentosFiscais ADD DevolvidoJustificativa NVARCHAR(500) NULL;
END;
