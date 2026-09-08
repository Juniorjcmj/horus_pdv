/* ------------------------------------------------------------------------- */
/* 04_serie_fiscal.sql                                                       */
/* Permite configurar a série de emissão da NFC-e por empresa                */
/* Idempotente — executado no boot pelo HorusDatabaseInitializer             */
/* ------------------------------------------------------------------------- */

IF COL_LENGTH(N'Empresas', N'SerieNfce') IS NULL
BEGIN
    ALTER TABLE Empresas ADD SerieNfce INT NOT NULL CONSTRAINT DF_Empresas_SerieNfce DEFAULT 2;
END;
GO

-- Se a empresa emp-1788703461569 já existe e está na série 1 (que colidiu com emissões de 2024 na SEFAZ),
-- avança a série dela para 2 para desviar de duplicidade (cStat 539) e começar a numeração limpa do 1.
IF EXISTS (SELECT 1 FROM Empresas WHERE Id = 'emp-1788703461569' AND (SerieNfce IS NULL OR SerieNfce = 1))
BEGIN
    UPDATE Empresas SET SerieNfce = 2 WHERE Id = 'emp-1788703461569';
END;
GO
