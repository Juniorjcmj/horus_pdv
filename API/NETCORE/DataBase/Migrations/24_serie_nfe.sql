-- ============================================================================
-- Migration 24: Configuração da Série de Emissão da NF-e (Modelo 55)
-- Avança a série da NF-e para 2 para desviar de duplicidade (cStat 539)
-- com notas emitidas em testes anteriores na SEFAZ.
-- ============================================================================

IF COL_LENGTH(N'Empresas', N'SerieNfe') IS NOT NULL
BEGIN
    UPDATE Empresas
       SET SerieNfe = 2
     WHERE SerieNfe = 1;
END;
