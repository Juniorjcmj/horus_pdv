/*
 * Arquivo: API/NETCORE/DataBase/Migrations/48_integracao_tokens.sql
 * Objetivo: token de integração por empresa para agentes de IA consultarem o sistema pelo servidor
 *           MCP (endpoint /mcp). O token é SOMENTE LEITURA e só é aceito em /mcp.
 *           O token em texto só é mostrado UMA vez na geração; aqui fica apenas o SHA-256 dele
 *           (TokenHash) e um prefixo curto (TokenPrefix) para o admin reconhecer qual é qual.
 *           Revogar = preencher RevokedAt (o agente perde o acesso na próxima requisição).
 *
 * Idempotente: pode ser executado no boot repetidamente sem erro.
 */

SET NOCOUNT ON;
GO

IF OBJECT_ID(N'IntegracaoTokens', N'U') IS NULL
BEGIN
    CREATE TABLE IntegracaoTokens (
        Id          NVARCHAR(40)    NOT NULL,
        CompanyId   NVARCHAR(100)   NOT NULL,
        Nome        NVARCHAR(120)   NOT NULL,
        TokenHash   CHAR(64)        NOT NULL,   /* SHA-256 em hexadecimal minúsculo */
        TokenPrefix NVARCHAR(16)    NOT NULL,   /* início do token, só para exibição */
        CreatedAt   DATETIMEOFFSET  NOT NULL CONSTRAINT DF_IntegracaoTokens_CreatedAt DEFAULT SYSDATETIMEOFFSET(),
        CreatedBy   NVARCHAR(100)   NULL,
        LastUsedAt  DATETIMEOFFSET  NULL,
        RevokedAt   DATETIMEOFFSET  NULL,
        RevokedBy   NVARCHAR(100)   NULL,
        CONSTRAINT PK_IntegracaoTokens PRIMARY KEY (Id)
    );

    CREATE UNIQUE INDEX UX_IntegracaoTokens_TokenHash ON IntegracaoTokens (TokenHash);
    CREATE INDEX IX_IntegracaoTokens_Company ON IntegracaoTokens (CompanyId, CreatedAt DESC);
END;
GO
