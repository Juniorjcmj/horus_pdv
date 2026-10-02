-- Migration 30: Regras de emissão de NFC-e por forma de pagamento e intervalo de vendas.
-- Permite escolher quais formas emitem NFC-e e a cada quantas vendas emitir (ex.: a cada 30 vendas emitir 1 NFC-e).
-- Idempotente e multi-tenant por CompanyId.

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = N'RegrasEmissaoNfce')
BEGIN
    CREATE TABLE RegrasEmissaoNfce (
        CompanyId                   NVARCHAR(100) NOT NULL,
        Habilitado                  BIT NOT NULL CONSTRAINT DF_RegrasEmissaoNfce_Habilitado DEFAULT (1),
        FormasPagamentoHabilitadas  NVARCHAR(500) NOT NULL CONSTRAINT DF_RegrasEmissaoNfce_Formas DEFAULT ('dinheiro,credito,debito,pix,fiado'),
        IntervaloNotas              INT NOT NULL CONSTRAINT DF_RegrasEmissaoNfce_Intervalo DEFAULT (1),
        EmitirSempreComCpf          BIT NOT NULL CONSTRAINT DF_RegrasEmissaoNfce_Cpf DEFAULT (1),
        ContadorVendas              INT NOT NULL CONSTRAINT DF_RegrasEmissaoNfce_Contador DEFAULT (0),
        UpdatedAt                   DATETIME2 NOT NULL CONSTRAINT DF_RegrasEmissaoNfce_UpdatedAt DEFAULT (SYSUTCDATETIME()),
        UpdatedBy                   NVARCHAR(100) NULL,
        CONSTRAINT PK_RegrasEmissaoNfce PRIMARY KEY (CompanyId)
    );
END
