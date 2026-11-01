/*
 * Arquivo: API/NETCORE/DataBase/Migrations/58_integracao_token_escrita.sql
 * Objetivo: permissão de escrita por token de integração (MCP). Com PermiteEscritaProdutos = 1 o agente
 *           pode editar o cadastro de produtos e ajustar estoque (ferramentas editar_produto e
 *           ajustar_estoque_produto). Tokens existentes continuam somente leitura (padrão 0).
 *
 * Idempotente: pode ser executado no boot repetidamente sem erro.
 */

SET NOCOUNT ON;
GO

IF COL_LENGTH(N'IntegracaoTokens', N'PermiteEscritaProdutos') IS NULL
BEGIN
    ALTER TABLE IntegracaoTokens
        ADD PermiteEscritaProdutos BIT NOT NULL
            CONSTRAINT DF_IntegracaoTokens_PermiteEscritaProdutos DEFAULT 0;
END
GO
