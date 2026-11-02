-- Dados de referencia que o sistema exige para funcionar, extraidos do Resumo.sql e das migracoes da API .NET.
-- Idempotente (IF NOT EXISTS): num banco restaurado da producao a empresa-principal ja existe e nada muda.
--
-- Fora daqui, de proposito: usuarios e produtos de demonstracao (usr-001, produtos de mercado/balanca) e os dados
-- que pertencem a modulos que a API Java ainda nao tem (categorias padrao da empresa-principal, conteudo de
-- Treinamento, sequencias fiscais). Eles entram em migracoes V3+ junto com o modulo correspondente.

IF NOT EXISTS (SELECT 1 FROM Empresas WHERE Id = N'empresa-principal')
BEGIN
    INSERT INTO Empresas
        (Id, FantasyName, CorporateName, Cnpj, StateRegistration, Website, Email, SacPhone, Phone, Mobile,
         Cep, Address, Number, Neighborhood, City, Uf, Complement, EmailSmtpEnabled, EmailSmtpHost,
         EmailSmtpPort, EmailSmtpEnableSsl, EmailSmtpUser, EmailSmtpPassword, EmailSmtpFromEmail,
         EmailSmtpFromName, EmailSmtpReplyTo, Status)
    VALUES
        (N'empresa-principal', N'Quack PDV', N'Quack PDV LTDA', N'06.332.765/0001-05',
         N'123.456.789.110', N'https://www.quackpdv.com.br', N'contato@quackpdv.com.br',
         N'(11) 3000-1000', N'(11) 3149-5959', N'(11) 98888-1000', N'01310-200',
         N'Avenida Paulista', N'1578', N'Bela Vista', N'São Paulo', N'SP', N'Próximo ao MASP',
         0, N'smtp-mail.outlook.com', 587, 1, N'', N'', N'naoresponderhoruspdv@outlook.com',
         N'Quack PDV', N'', N'aprovada');
END;
