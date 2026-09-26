-- Migration 20: Índice composto em CaixaSessoes (CompanyId, OpenedAt DESC)
-- Otimiza consultas de listagem e busca de sessão aberta por tenant.

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE name = N'IX_CaixaSessoes_CompanyId_OpenedAt'
      AND object_id = OBJECT_ID(N'CaixaSessoes')
)
BEGIN
    CREATE INDEX IX_CaixaSessoes_CompanyId_OpenedAt
    ON CaixaSessoes (CompanyId, OpenedAt DESC);
END
