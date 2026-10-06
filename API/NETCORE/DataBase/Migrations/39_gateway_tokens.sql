/*
 * Arquivo: API/NETCORE/DataBase/Migrations/39_gateway_tokens.sql
 * Objetivo: token por loja para o Local Gateway se autenticar na nuvem (Gateway → Cloud).
 *           O token em texto só é mostrado UMA vez na geração; aqui fica apenas o SHA-256 dele
 *           (TokenHash) e um prefixo curto (TokenPrefix) para o admin reconhecer qual é qual.
 *           Revogar = preencher RevokedAt (o Gateway perde o acesso na próxima requisição).
 *
 * Idempotente: pode ser executado no boot repetidamente sem erro.
 */

SET NOCOUNT ON;
GO

IF OBJECT_ID(N'GatewayTokens', N'U') IS NULL
BEGIN
    CREATE TABLE GatewayTokens (
        Id          NVARCHAR(40)    NOT NULL,
        CompanyId   NVARCHAR(100)   NOT NULL,
        StoreId     NVARCHAR(100)   NULL,
        Nome        NVARCHAR(120)   NOT NULL,
        TokenHash   CHAR(64)        NOT NULL,   /* SHA-256 em hexadecimal minúsculo */
        TokenPrefix NVARCHAR(16)    NOT NULL,   /* início do token, só para exibição */
        CreatedAt   DATETIMEOFFSET  NOT NULL CONSTRAINT DF_GatewayTokens_CreatedAt DEFAULT SYSDATETIMEOFFSET(),
        CreatedBy   NVARCHAR(100)   NULL,
        LastUsedAt  DATETIMEOFFSET  NULL,
        RevokedAt   DATETIMEOFFSET  NULL,
        RevokedBy   NVARCHAR(100)   NULL,
        CONSTRAINT PK_GatewayTokens PRIMARY KEY (Id)
    );

    CREATE UNIQUE INDEX UX_GatewayTokens_TokenHash ON GatewayTokens (TokenHash);
    CREATE INDEX IX_GatewayTokens_Company ON GatewayTokens (CompanyId, CreatedAt DESC);
END;
GO
