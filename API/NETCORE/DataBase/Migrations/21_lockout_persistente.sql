-- Migration 21: Lockout persistente no banco de dados
-- Move o controle de tentativas de login da memória para a tabela Usuarios.

IF COL_LENGTH(N'Usuarios', N'FailedLoginAttempts') IS NULL
BEGIN
    ALTER TABLE Usuarios ADD FailedLoginAttempts INT NOT NULL CONSTRAINT DF_Usuarios_FailedLoginAttempts DEFAULT 0;
END

IF COL_LENGTH(N'Usuarios', N'FirstFailedLoginAt') IS NULL
BEGIN
    ALTER TABLE Usuarios ADD FirstFailedLoginAt DATETIMEOFFSET NULL;
END

IF COL_LENGTH(N'Usuarios', N'LockoutEnd') IS NULL
BEGIN
    ALTER TABLE Usuarios ADD LockoutEnd DATETIMEOFFSET NULL;
END
