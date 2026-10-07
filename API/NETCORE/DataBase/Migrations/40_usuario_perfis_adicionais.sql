/*
 * Arquivo: API/NETCORE/DataBase/Migrations/40_usuario_perfis_adicionais.sql
 * Objetivo: mais de um perfil por pessoa. Usuarios.Role continua sendo o perfil principal
 *           (administrador, gerente, atendente, caixa); PerfisAdicionais guarda os extras separados por
 *           vírgula — hoje só "financeiro" (acesso a tudo de notas fiscais). Ex.: gerente + financeiro.
 *
 * Idempotente: pode ser executado no boot repetidamente sem erro.
 */

SET NOCOUNT ON;
GO

IF COL_LENGTH('dbo.Usuarios', 'PerfisAdicionais') IS NULL
BEGIN
    ALTER TABLE dbo.Usuarios
        ADD PerfisAdicionais NVARCHAR(200) NOT NULL
            CONSTRAINT DF_Usuarios_PerfisAdicionais DEFAULT N'';
END;
GO
