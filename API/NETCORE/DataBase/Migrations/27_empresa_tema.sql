-- Migration 26: Tema (cores de acento) por empresa — cor do sistema + cor da frente de caixa (PDV).
-- A Cloud é a autoridade; cada terminal da empresa herda a cor. Aditiva e idempotente.

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = N'EmpresaTema')
BEGIN
    CREATE TABLE EmpresaTema (
        CompanyId    NVARCHAR(100) NOT NULL,
        SystemAccent NVARCHAR(32) NULL,   -- cor de acento do sistema inteiro (ex.: #0369a1)
        PdvAccent    NVARCHAR(32) NULL,   -- cor de acento apenas da frente de caixa (sobrepõe no PDV)
        UpdatedAt    DATETIME2 NOT NULL CONSTRAINT DF_EmpresaTema_UpdatedAt DEFAULT (SYSUTCDATETIME()),
        UpdatedBy    NVARCHAR(100) NULL,
        CONSTRAINT PK_EmpresaTema PRIMARY KEY (CompanyId)
    );
END
