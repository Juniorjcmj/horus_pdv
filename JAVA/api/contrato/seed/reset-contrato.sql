/*
 * Volta o banco ao estado do seed antes de cada cenario do contrato (rodado nos DOIS lados, antes do seed-contrato.sql).
 * Apaga tudo o que os cenarios podem ter criado ou alterado nas tabelas desta fase (filhas antes das maes).
 */
SET NOCOUNT ON;
SET QUOTED_IDENTIFIER ON;
DELETE FROM PasswordResetTokens;
DELETE FROM Sessoes;
DELETE FROM AuditLog;
DELETE FROM GatewayCreditGrants;
DELETE FROM VendaPagamentos;
DELETE FROM VendaItens;
DELETE FROM Vendas;
DELETE FROM LoteConsumos;
DELETE FROM ProdutoLotes;
DELETE FROM LoteConfig;
DELETE FROM InventarioContagens;
DELETE FROM InventarioEventos;
DELETE FROM InventarioItens;
DELETE FROM Inventarios;
DELETE FROM NotasEntradaArquivo;
DELETE FROM MapeamentoProdutoFornecedor;
DELETE FROM OrdemCompraItens;
DELETE FROM OrdemCompra;
DELETE FROM PromocaoProdutos;
DELETE FROM Promocoes;
DELETE FROM EstoqueMovimentacoes;
DELETE FROM ModuloMercadoRegistros;
DELETE FROM EmpresaTema;
DELETE FROM Produtos;
DELETE FROM Clientes;
DELETE FROM Fornecedores;
DELETE FROM Categorias WHERE Id LIKE N'cat-ct-%' OR (CompanyId=N'empresa-principal' AND LEN(Id)=36) OR CompanyId<>N'empresa-principal';
DELETE FROM TreinamentoVideos WHERE Id NOT IN (N'trv-entrar-sistema', N'trv-abertura-caixa', N'trv-venda-simples', N'trv-venda-fiado', N'trv-alterar-valor', N'trv-vendas-caixa', N'trv-cancelar-venda', N'trv-sangria-reforco', N'trv-fechamento-caixa');
DELETE FROM TreinamentoSecoes WHERE Id NOT IN (N'trs-primeiros-passos', N'trs-caixa');
DELETE FROM Usuarios;
DELETE FROM Empresas WHERE Id <> N'empresa-principal';
UPDATE Empresas SET RequireApprovalForNewCompanies = 1 WHERE Id = N'empresa-principal';
UPDATE Empresas SET FantasyName=N'Quack PDV', CorporateName=N'Quack PDV LTDA', Cnpj=N'06.332.765/0001-05',
 EmailSmtpEnabled=0,EmailSmtpHost=N'smtp-mail.outlook.com',EmailSmtpPort=587,EmailSmtpEnableSsl=1,EmailSmtpUser=N'',EmailSmtpFromEmail=N'naoresponderhoruspdv@outlook.com',EmailSmtpFromName=N'Quack PDV',EmailSmtpReplyTo=N'',
 Crt=1,CnaeFiscal=N'',CodigoMunicipioIbge=N'3304557',CodigoUfIbge=33,AmbienteFiscal=2,SerieNfce=2,SerieNfe=2,
 CertificadoPfxCifrado=NULL,CertificadoSenhaCifrada=NULL,CertificadoThumbprint=NULL,CertificadoValidoAte=NULL,ReviewedAt=NULL,ReviewedBy=NULL,RejectionReason=NULL,Status=N'aprovada'
 WHERE Id=N'empresa-principal';

-- Restore every registration field changed by Empresa PUT before the next scenario.
UPDATE Empresas SET FantasyName=N'Quack PDV',
 CorporateName=N'Quack PDV LTDA',
 Cnpj=N'06.332.765/0001-05',
 StateRegistration=N'123.456.789.110',
 Website=N'https://www.quackpdv.com.br',
 Email=N'contato@quackpdv.com.br',
 SacPhone=N'(11) 3000-1000',
 Phone=N'(11) 3149-5959',
 Mobile=N'(11) 98888-1000',
 Cep=N'01310-200',
 Address=N'Avenida Paulista',
 Number=N'1578',
 Neighborhood=N'Bela Vista',
 City=N'São Paulo',
 Uf=N'SP',
 Complement=N'Próximo ao MASP',
 EmailSmtpEnabled=0,
 EmailSmtpHost=N'smtp-mail.outlook.com',
 EmailSmtpPort=587,
 EmailSmtpEnableSsl=1,
 EmailSmtpUser=N'',
 EmailSmtpPassword=N'',
 EmailSmtpFromEmail=N'naoresponderhoruspdv@outlook.com',
 EmailSmtpFromName=N'Quack PDV',
 EmailSmtpReplyTo=N'',
 Status=N'aprovada',
 RespTecCnpj=N'',
 RespTecContato=N'',
 RespTecEmail=N'',
 RespTecFone=N'' WHERE Id=N'empresa-principal';
