/*
 * Seed do contrato (D6): empresas e usuarios usados pelos cenarios gravados na API .NET e repetidos na API Java.
 * Aplicado nos DOIS lados (SQL Server do ambiente .NET e do Testcontainers da API Java) depois que o esquema existe.
 * Todos os usuarios tem a senha  Senha#Quack2026  (hash PBKDF2 fixo, mesmo dos vetores de compatibilidade).
 * Idempotente.
 */
SET NOCOUNT ON;

-- A API .NET semeia o usuario de demonstracao usr-001; a Java nao. Tira dos dois lados para as listagens baterem.
DELETE FROM Usuarios WHERE Id = N'usr-001';

-- Empresas ------------------------------------------------------------------
IF NOT EXISTS (SELECT 1 FROM Empresas WHERE Id = N'emp-contrato-aprovada')
    INSERT INTO Empresas (Id, FantasyName, CorporateName, Cnpj, Email, Status, RejectionReason)
    VALUES (N'emp-contrato-aprovada', N'Mercado Aprovado', N'Mercado Aprovado LTDA', N'11.222.333/0001-81',
            N'aprovada@contrato.quack', N'aprovada', NULL);

IF NOT EXISTS (SELECT 1 FROM Empresas WHERE Id = N'emp-contrato-pendente')
    INSERT INTO Empresas (Id, FantasyName, CorporateName, Cnpj, Email, Status, RejectionReason)
    VALUES (N'emp-contrato-pendente', N'Mercado Pendente', N'Mercado Pendente LTDA', N'45.723.174/0001-10',
            N'pendente@contrato.quack', N'pendente', NULL);

IF NOT EXISTS (SELECT 1 FROM Empresas WHERE Id = N'emp-contrato-bloqueada')
    INSERT INTO Empresas (Id, FantasyName, CorporateName, Cnpj, Email, Status, RejectionReason)
    VALUES (N'emp-contrato-bloqueada', N'Mercado Bloqueado', N'Mercado Bloqueado LTDA', N'34.028.316/0001-03',
            N'bloqueada@contrato.quack', N'bloqueada', N'Inadimplência');

-- Usuarios ------------------------------------------------------------------
DECLARE @hash NVARCHAR(300) = N'100000.Q0wmjZSGSpZjzetxaWmnOg==./e7RA0660AYv/UNXRsWH7jS/LW9C9uhWJccn0quZxXs=';

MERGE Usuarios AS alvo
USING (VALUES
    (N'usr-ct-admin',    N'empresa-principal',     N'11111111111', N'Ana Administradora', N'admin@contrato.quack',    N'11999990001', N'administrador', N'ativo',   N''),
    (N'usr-ct-gerente',  N'empresa-principal',     N'22222222222', N'Gil Gerente',        N'gerente@contrato.quack',   N'11999990002', N'gerente',       N'ativo',   N''),
    (N'usr-ct-gerfin',   N'empresa-principal',     N'33333333333', N'Gal Gerente Fin',    N'gerfin@contrato.quack',    N'11999990003', N'gerente',       N'ativo',   N'financeiro'),
    (N'usr-ct-atend',    N'empresa-principal',     N'44444444444', N'Ari Atendente',      N'atendente@contrato.quack', N'11999990004', N'atendente',     N'ativo',   N''),
    (N'usr-ct-caixa',    N'empresa-principal',     N'55555555555', N'Cai Caixa',          N'caixa@contrato.quack',     N'11999990005', N'caixa',         N'ativo',   N''),
    (N'usr-ct-inativo',  N'empresa-principal',     N'66666666666', N'Ivo Inativo',        N'inativo@contrato.quack',   N'11999990006', N'atendente',     N'inativo', N''),
    (N'usr-ct-bloqueio', N'empresa-principal',     N'77777777777', N'Bia Bloqueio',       N'bloqueio@contrato.quack',  N'11999990007', N'atendente',     N'ativo',   N''),
    (N'usr-ct-bloq2',    N'empresa-principal',     N'88888888888', N'Beto Bloqueio',      N'bloqueio2@contrato.quack', N'11999990008', N'atendente',     N'ativo',   N''),
    (N'usr-ct-troca',    N'empresa-principal',     N'99999999999', N'Tina Troca',         N'troca@contrato.quack',     N'11999990009', N'atendente',     N'ativo',   N''),
    (N'usr-ct-apr-adm',  N'emp-contrato-aprovada', N'11222333000181', N'Ada Aprovada',       N'adm.aprovada@contrato.quack', N'11999990010', N'administrador', N'ativo', N''),
    (N'usr-ct-pen-adm',  N'emp-contrato-pendente', N'98765432100', N'Paulo Pendente',     N'adm.pendente@contrato.quack', N'11999990011', N'administrador', N'ativo', N''),
    (N'usr-ct-blq-adm',  N'emp-contrato-bloqueada',N'11144477735', N'Bruna Bloqueada',    N'adm.bloqueada@contrato.quack', N'11999990012', N'administrador', N'ativo', N'')
) AS origem (Id, CompanyId, Cpf, Name, Email, Phone, Role, Status, PerfisAdicionais)
ON alvo.Id = origem.Id
WHEN NOT MATCHED THEN
    INSERT (Id, CompanyId, Cpf, Name, Email, Phone, Role, Status, CreatedAt, LastLoginAt, PasswordHash, MustChangePassword, PerfisAdicionais)
    VALUES (origem.Id, origem.CompanyId, origem.Cpf, origem.Name, origem.Email, origem.Phone, origem.Role,
            origem.Status, N'2026-01-15', N'-', @hash, 0, origem.PerfisAdicionais);
