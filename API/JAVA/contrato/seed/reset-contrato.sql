/*
 * Volta o banco ao estado do seed antes de cada cenario do contrato (rodado nos DOIS lados, antes do seed-contrato.sql).
 * Apaga tudo o que os cenarios podem ter criado ou alterado nas tabelas desta fase.
 */
SET NOCOUNT ON;
DELETE FROM PasswordResetTokens;
DELETE FROM Sessoes;
DELETE FROM AuditLog;
DELETE FROM Usuarios;
DELETE FROM Empresas WHERE Id <> N'empresa-principal';
UPDATE Empresas SET RequireApprovalForNewCompanies = 1 WHERE Id = N'empresa-principal';
