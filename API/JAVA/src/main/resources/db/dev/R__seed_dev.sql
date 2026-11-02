-- Usuario de desenvolvimento (so no perfil "dev"): administrador da empresa-principal.
--   e-mail: flavio@hpdv.com.br    senha: Senha#Quack2026
-- Repetivel (R__): roda de novo quando este arquivo muda; nao altera um usuario que ja exista.

IF NOT EXISTS (SELECT 1 FROM Usuarios WHERE Id = N'usr-001')
BEGIN
    INSERT INTO Usuarios (Id, CompanyId, Cpf, Name, Email, Phone, Role, Status, CreatedAt, LastLoginAt, PasswordHash, MustChangePassword)
    VALUES (N'usr-001', N'empresa-principal', N'06.332.765/0001-05', N'Flávio Oliveira', N'flavio@hpdv.com.br',
            N'(11) 98888-1111', N'administrador', N'ativo', N'2026-02-10', N'-',
            N'100000.Q0wmjZSGSpZjzetxaWmnOg==./e7RA0660AYv/UNXRsWH7jS/LW9C9uhWJccn0quZxXs=', 0);
END;
