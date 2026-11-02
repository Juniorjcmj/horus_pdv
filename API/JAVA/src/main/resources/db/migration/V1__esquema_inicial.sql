-- Esquema inicial da API Quack PDV: tabelas, colunas, defaults, PK, FK, UNIQUE, indices e a funcao
-- fn_HorusParseDecimal, exatamente como o inicializador da API .NET os cria num banco do zero.
-- Gerado por contrato/esquema/gerar-v1.sh. NUNCA edite depois de aplicado (o Flyway valida o checksum).

GO

GO

GO

GO
CREATE TABLE [dbo].[AuditLog] (
    [Id]          BIGINT             IDENTITY (1, 1) NOT NULL,
    [CompanyId]   NVARCHAR (40)      NOT NULL,
    [OccurredAt]  DATETIMEOFFSET (7) NOT NULL,
    [UserId]      NVARCHAR (40)      NOT NULL,
    [UserName]    NVARCHAR (180)     NOT NULL,
    [EventType]   NVARCHAR (60)      NOT NULL,
    [EntityType]  NVARCHAR (60)      NULL,
    [EntityId]    NVARCHAR (80)      NULL,
    [Description] NVARCHAR (500)     NOT NULL,
    [Ip]          NVARCHAR (64)      NULL,
    CONSTRAINT [PK_AuditLog] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_AuditLog_Company_OccurredAt]
    ON [dbo].[AuditLog]([CompanyId] ASC, [OccurredAt] DESC);

GO

GO
CREATE TABLE [dbo].[AutorizacoesPreco] (
    [Id]             NVARCHAR (40)      NOT NULL,
    [CompanyId]      NVARCHAR (40)      NOT NULL,
    [ProductCode]    NVARCHAR (60)      NOT NULL,
    [PrecoTabela]    DECIMAL (15, 4)    NOT NULL,
    [PrecoNovo]      DECIMAL (15, 4)    NOT NULL,
    [SupervisorId]   NVARCHAR (40)      NOT NULL,
    [SupervisorNome] NVARCHAR (120)     NOT NULL,
    [OperadorId]     NVARCHAR (40)      NULL,
    [OperadorNome]   NVARCHAR (120)     NULL,
    [Motivo]         NVARCHAR (200)     NULL,
    [CriadoEm]       DATETIMEOFFSET (7) NOT NULL,
    [ExpiraEm]       DATETIMEOFFSET (7) NOT NULL,
    [UsadoEm]        DATETIMEOFFSET (7) NULL,
    [VendaId]        NVARCHAR (40)      NULL,
    CONSTRAINT [PK_AutorizacoesPreco] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_AutorizacoesPreco_Company]
    ON [dbo].[AutorizacoesPreco]([CompanyId] ASC, [CriadoEm] DESC);

GO

GO
CREATE TABLE [dbo].[CaixaMovimentos] (
    [Id]            NVARCHAR (40)      NOT NULL,
    [CompanyId]     NVARCHAR (40)      NOT NULL,
    [CaixaSessaoId] NVARCHAR (40)      NOT NULL,
    [Tipo]          TINYINT            NOT NULL,
    [Valor]         DECIMAL (18, 2)    NOT NULL,
    [Motivo]        NVARCHAR (300)     NOT NULL,
    [CreatedAt]     DATETIMEOFFSET (7) NOT NULL,
    [OperatorId]    NVARCHAR (40)      NOT NULL,
    [OperatorName]  NVARCHAR (180)     NOT NULL,
    CONSTRAINT [PK_CaixaMovimentos] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_CaixaMovimentos_Sessao]
    ON [dbo].[CaixaMovimentos]([CaixaSessaoId] ASC);

GO

GO
CREATE TABLE [dbo].[CaixaSessoes] (
    [Id]                 NVARCHAR (40)      NOT NULL,
    [CompanyId]          NVARCHAR (40)      NOT NULL,
    [OpenedAt]           DATETIMEOFFSET (7) NOT NULL,
    [ClosedAt]           DATETIMEOFFSET (7) NULL,
    [OperatorId]         NVARCHAR (40)      NOT NULL,
    [OperatorName]       NVARCHAR (180)     NOT NULL,
    [ClosedById]         NVARCHAR (40)      NOT NULL,
    [ClosedByName]       NVARCHAR (180)     NOT NULL,
    [Note]               NVARCHAR (500)     NOT NULL,
    [ExpectedCashAmount] DECIMAL (18, 2)    NULL,
    [DifferenceAmount]   DECIMAL (18, 2)    NULL,
    [DifferenceReason]   NVARCHAR (500)     NULL,
    [OpeningAmount]      DECIMAL (18, 2)    NOT NULL,
    [ClosingAmount]      DECIMAL (18, 2)    NOT NULL,
    CONSTRAINT [PK_CaixaSessoes] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_CaixaSessoes_ClosedAt]
    ON [dbo].[CaixaSessoes]([ClosedAt] ASC);

GO

GO
CREATE NONCLUSTERED INDEX [IX_CaixaSessoes_CompanyId_OpenedAt]
    ON [dbo].[CaixaSessoes]([CompanyId] ASC, [OpenedAt] DESC);

GO

GO
CREATE NONCLUSTERED INDEX [IX_CaixaSessoes_Company_Operator_Open]
    ON [dbo].[CaixaSessoes]([CompanyId] ASC, [OperatorId] ASC, [ClosedAt] ASC) WHERE ([ClosedAt] IS NULL);

GO

GO
CREATE TABLE [dbo].[Categorias] (
    [Id]                      NVARCHAR (40) NOT NULL,
    [CompanyId]               NVARCHAR (40) NOT NULL,
    [Nome]                    NVARCHAR (80) NOT NULL,
    [CategoriaPaiId]          NVARCHAR (40) NULL,
    [Ordem]                   INT           NOT NULL,
    [Ativa]                   BIT           NOT NULL,
    [PrazoPadraoValidadeDias] INT           NULL,
    [DiasAlertaValidade]      INT           NULL,
    CONSTRAINT [PK_Categorias] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_Categorias_Company]
    ON [dbo].[Categorias]([CompanyId] ASC, [Ativa] ASC, [Ordem] ASC);

GO

GO
CREATE UNIQUE NONCLUSTERED INDEX [UQ_Categorias_Nome_Raiz]
    ON [dbo].[Categorias]([CompanyId] ASC, [Nome] ASC) WHERE ([CategoriaPaiId] IS NULL);

GO

GO
CREATE UNIQUE NONCLUSTERED INDEX [UQ_Categorias_Nome_Filho]
    ON [dbo].[Categorias]([CompanyId] ASC, [Nome] ASC, [CategoriaPaiId] ASC) WHERE ([CategoriaPaiId] IS NOT NULL);

GO

GO
CREATE TABLE [dbo].[Clientes] (
    [Id]                  NVARCHAR (40)   NOT NULL,
    [CompanyId]           NVARCHAR (40)   NOT NULL,
    [CustomerName]        NVARCHAR (180)  NOT NULL,
    [Document]            NVARCHAR (30)   NOT NULL,
    [BirthDate]           NVARCHAR (20)   NOT NULL,
    [Age]                 NVARCHAR (10)   NOT NULL,
    [Cep]                 NVARCHAR (20)   NOT NULL,
    [City]                NVARCHAR (120)  NOT NULL,
    [State]               NVARCHAR (2)    NOT NULL,
    [Address]             NVARCHAR (180)  NOT NULL,
    [Neighborhood]        NVARCHAR (120)  NOT NULL,
    [StreetComplement]    NVARCHAR (180)  NOT NULL,
    [Number]              NVARCHAR (30)   NOT NULL,
    [ReferencePoint]      NVARCHAR (180)  NOT NULL,
    [Telephone]           NVARCHAR (30)   NOT NULL,
    [Cellphone]           NVARCHAR (30)   NOT NULL,
    [Email]               NVARCHAR (180)  NOT NULL,
    [IndIeDest]           TINYINT         NOT NULL,
    [InscricaoEstadual]   NVARCHAR (20)   NULL,
    [CodigoMunicipioIbge] NVARCHAR (7)    NULL,
    [LimiteCredito]       DECIMAL (18, 2) NOT NULL,
    [SaldoDevedor]        DECIMAL (18, 2) NOT NULL,
    CONSTRAINT [PK_Clientes] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_Clientes_Company_Name]
    ON [dbo].[Clientes]([CompanyId] ASC, [CustomerName] ASC);

GO

GO
CREATE UNIQUE NONCLUSTERED INDEX [UQ_Clientes_Company_Document]
    ON [dbo].[Clientes]([CompanyId] ASC, [Document] ASC) WHERE ([Document] IS NOT NULL AND [Document]<>N'');

GO

GO
CREATE TABLE [dbo].[CustomerCreditPolicies] (
    [CompanyId]  NVARCHAR (100) NOT NULL,
    [CustomerId] NVARCHAR (100) NOT NULL,
    [Blocked]    BIT            NOT NULL,
    PRIMARY KEY CLUSTERED ([CompanyId] ASC, [CustomerId] ASC)
);

GO

GO
CREATE TABLE [dbo].[DocumentosFiscais] (
    [Id]                         NVARCHAR (40)      NOT NULL,
    [CompanyId]                  NVARCHAR (40)      NOT NULL,
    [VendaId]                    NVARCHAR (40)      NULL,
    [Modelo]                     SMALLINT           NOT NULL,
    [Serie]                      INT                NOT NULL,
    [NumeroNf]                   INT                NOT NULL,
    [Ambiente]                   TINYINT            NOT NULL,
    [ChaveAcesso]                CHAR (44)          NULL,
    [Status]                     TINYINT            NOT NULL,
    [TpEmis]                     TINYINT            NOT NULL,
    [DhContingencia]             DATETIMEOFFSET (7) NULL,
    [JustContingencia]           NVARCHAR (256)     NULL,
    [Protocolo]                  NVARCHAR (20)      NULL,
    [DhAutorizacao]              DATETIMEOFFSET (7) NULL,
    [CodigoStatus]               INT                NULL,
    [MotivoStatus]               NVARCHAR (500)     NULL,
    [XmlAssinado]                NVARCHAR (MAX)     NULL,
    [XmlProtocolado]             NVARCHAR (MAX)     NULL,
    [XmlCancelamento]            NVARCHAR (MAX)     NULL,
    [Tentativas]                 INT                NOT NULL,
    [ProximaTentativaEm]         DATETIMEOFFSET (7) NULL,
    [UltimoErro]                 NVARCHAR (1000)    NULL,
    [CriadoEm]                   DATETIMEOFFSET (7) NOT NULL,
    [AtualizadoEm]               DATETIMEOFFSET (7) NOT NULL,
    [DestinatarioJson]           NVARCHAR (MAX)     NULL,
    [NaturezaOperacao]           NVARCHAR (120)     NULL,
    [ModalidadeFrete]            TINYINT            NOT NULL,
    [CanceladoPorSupervisorId]   NVARCHAR (40)      NULL,
    [CanceladoPorSupervisorNome] NVARCHAR (180)     NULL,
    [CanceladoPorOperador]       NVARCHAR (180)     NULL,
    [CanceladoJustificativa]     NVARCHAR (500)     NULL,
    [ChaveReferenciada]          CHAR (44)          NULL,
    [DocumentoOrigemId]          NVARCHAR (40)      NULL,
    [DevolvidoPorSupervisorId]   NVARCHAR (40)      NULL,
    [DevolvidoPorSupervisorNome] NVARCHAR (180)     NULL,
    [DevolvidoPorOperador]       NVARCHAR (180)     NULL,
    [DevolvidoJustificativa]     NVARCHAR (500)     NULL,
    [QrCodeUrl]                  NVARCHAR (MAX)     NULL,
    CONSTRAINT [PK_DocumentosFiscais] PRIMARY KEY CLUSTERED ([Id] ASC),
    CONSTRAINT [UQ_DocFiscais_Numeracao] UNIQUE NONCLUSTERED ([CompanyId] ASC, [Modelo] ASC, [Serie] ASC, [Ambiente] ASC, [NumeroNf] ASC)
);

GO

GO
CREATE UNIQUE NONCLUSTERED INDEX [UQ_DocFiscais_Chave]
    ON [dbo].[DocumentosFiscais]([ChaveAcesso] ASC) WHERE ([ChaveAcesso] IS NOT NULL);

GO

GO
CREATE NONCLUSTERED INDEX [IX_DocFiscais_Company_Data]
    ON [dbo].[DocumentosFiscais]([CompanyId] ASC, [CriadoEm] DESC);

GO

GO
CREATE NONCLUSTERED INDEX [IX_DocFiscais_Fila]
    ON [dbo].[DocumentosFiscais]([Status] ASC, [ProximaTentativaEm] ASC)
    INCLUDE([CompanyId], [Id]) WHERE ([Status] IN ((1), (2), (8)));

GO

GO
CREATE TABLE [dbo].[Empresas] (
    [Id]                             NVARCHAR (40)      NOT NULL,
    [FantasyName]                    NVARCHAR (180)     NOT NULL,
    [CorporateName]                  NVARCHAR (180)     NOT NULL,
    [Cnpj]                           NVARCHAR (30)      NOT NULL,
    [StateRegistration]              NVARCHAR (60)      NOT NULL,
    [Website]                        NVARCHAR (250)     NOT NULL,
    [Email]                          NVARCHAR (180)     NOT NULL,
    [SacPhone]                       NVARCHAR (30)      NOT NULL,
    [Phone]                          NVARCHAR (30)      NOT NULL,
    [Mobile]                         NVARCHAR (30)      NOT NULL,
    [Cep]                            NVARCHAR (20)      NOT NULL,
    [Address]                        NVARCHAR (180)     NOT NULL,
    [Number]                         NVARCHAR (30)      NOT NULL,
    [Neighborhood]                   NVARCHAR (120)     NOT NULL,
    [City]                           NVARCHAR (120)     NOT NULL,
    [Uf]                             NVARCHAR (2)       NOT NULL,
    [Complement]                     NVARCHAR (180)     NOT NULL,
    [EmailSmtpEnabled]               BIT                NOT NULL,
    [EmailSmtpHost]                  NVARCHAR (180)     NOT NULL,
    [EmailSmtpPort]                  INT                NOT NULL,
    [EmailSmtpEnableSsl]             BIT                NOT NULL,
    [EmailSmtpUser]                  NVARCHAR (180)     NOT NULL,
    [EmailSmtpPassword]              NVARCHAR (500)     NOT NULL,
    [EmailSmtpFromEmail]             NVARCHAR (180)     NOT NULL,
    [EmailSmtpFromName]              NVARCHAR (180)     NOT NULL,
    [EmailSmtpReplyTo]               NVARCHAR (180)     NOT NULL,
    [Crt]                            TINYINT            NOT NULL,
    [CnaeFiscal]                     NVARCHAR (7)       NOT NULL,
    [CodigoMunicipioIbge]            NVARCHAR (7)       NOT NULL,
    [CodigoUfIbge]                   TINYINT            NOT NULL,
    [AmbienteFiscal]                 TINYINT            NOT NULL,
    [CscId]                          NVARCHAR (6)       NULL,
    [CscCifrado]                     NVARCHAR (500)     NULL,
    [CertificadoPfxCifrado]          NVARCHAR (MAX)     NULL,
    [CertificadoSenhaCifrada]        NVARCHAR (500)     NULL,
    [CertificadoThumbprint]          NVARCHAR (80)      NULL,
    [CertificadoValidoAte]           DATETIMEOFFSET (7) NULL,
    [RespTecCnpj]                    NVARCHAR (14)      NULL,
    [RespTecContato]                 NVARCHAR (120)     NULL,
    [RespTecEmail]                   NVARCHAR (180)     NULL,
    [RespTecFone]                    NVARCHAR (20)      NULL,
    [SerieNfce]                      INT                NOT NULL,
    [Status]                         NVARCHAR (20)      NOT NULL,
    [CreatedAt]                      DATETIMEOFFSET (7) NOT NULL,
    [ReviewedAt]                     DATETIMEOFFSET (7) NULL,
    [ReviewedBy]                     NVARCHAR (180)     NULL,
    [RejectionReason]                NVARCHAR (500)     NULL,
    [RequireApprovalForNewCompanies] BIT                NOT NULL,
    [SerieNfe]                       INT                NOT NULL,
    CONSTRAINT [PK_Empresas] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_Empresas_Status]
    ON [dbo].[Empresas]([Status] ASC);

GO

GO
CREATE TABLE [dbo].[EmpresaTema] (
    [CompanyId]    NVARCHAR (100) NOT NULL,
    [SystemAccent] NVARCHAR (32)  NULL,
    [PdvAccent]    NVARCHAR (32)  NULL,
    [UpdatedAt]    DATETIME2 (7)  NOT NULL,
    [UpdatedBy]    NVARCHAR (100) NULL,
    CONSTRAINT [PK_EmpresaTema] PRIMARY KEY CLUSTERED ([CompanyId] ASC)
);

GO

GO
CREATE TABLE [dbo].[EstoqueConfigAnalise] (
    [CompanyId]          NVARCHAR (100) NOT NULL,
    [JanelaConsumoDias]  INT            NOT NULL,
    [NivelServicoZ]      DECIMAL (6, 3) NOT NULL,
    [AbcLimiteA]         DECIMAL (5, 2) NOT NULL,
    [AbcLimiteB]         DECIMAL (5, 2) NOT NULL,
    [AbcBase]            NVARCHAR (12)  NOT NULL,
    [FsnLimiteFast]      DECIMAL (5, 2) NOT NULL,
    [DiasParado]         INT            NOT NULL,
    [DiasExcesso]        INT            NOT NULL,
    [CoberturaAlvoDias]  INT            NOT NULL,
    [LeadTimePadraoDias] INT            NOT NULL,
    [ContagemDiasA]      INT            NOT NULL,
    [ContagemDiasB]      INT            NOT NULL,
    [ContagemDiasC]      INT            NOT NULL,
    [ContagemItensDia]   INT            NOT NULL,
    [MetasJson]          NVARCHAR (MAX) NULL,
    [AtualizadoPor]      NVARCHAR (200) NULL,
    [AtualizadoEm]       DATETIME2 (7)  NOT NULL,
    PRIMARY KEY CLUSTERED ([CompanyId] ASC)
);

GO

GO
CREATE TABLE [dbo].[EstoqueMovimentacoes] (
    [Id]              NVARCHAR (40)   NOT NULL,
    [CompanyId]       NVARCHAR (100)  NOT NULL,
    [ProdutoId]       NVARCHAR (40)   NOT NULL,
    [Tipo]            NVARCHAR (20)   NOT NULL,
    [Quantidade]      DECIMAL (18, 3) NOT NULL,
    [EstoqueAnterior] DECIMAL (18, 3) NOT NULL,
    [EstoqueNovo]     DECIMAL (18, 3) NOT NULL,
    [Motivo]          NVARCHAR (500)  NOT NULL,
    [UsuarioId]       NVARCHAR (36)   NOT NULL,
    [UsuarioNome]     NVARCHAR (200)  NOT NULL,
    [CriadoEm]        DATETIME2 (7)   NOT NULL,
    [CustoUnitario]   DECIMAL (18, 4) NULL,
    [OrigemTipo]      NVARCHAR (20)   NULL,
    [OrigemId]        NVARCHAR (80)   NULL,
    [DepositoId]      NVARCHAR (40)   NOT NULL,
    PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_EstoqueMov_Analise_Tipo]
    ON [dbo].[EstoqueMovimentacoes]([CompanyId] ASC, [Tipo] ASC, [CriadoEm] ASC)
    INCLUDE([ProdutoId], [Quantidade], [CustoUnitario]);

GO

GO
CREATE NONCLUSTERED INDEX [IX_EstoqueMovimentacoes_Produto]
    ON [dbo].[EstoqueMovimentacoes]([CompanyId] ASC, [ProdutoId] ASC, [CriadoEm] DESC);

GO

GO
CREATE NONCLUSTERED INDEX [IX_EstoqueMov_Analise_Produto]
    ON [dbo].[EstoqueMovimentacoes]([CompanyId] ASC, [ProdutoId] ASC, [CriadoEm] ASC)
    INCLUDE([Tipo], [Quantidade], [CustoUnitario]);

GO

GO
CREATE NONCLUSTERED INDEX [IX_EstoqueMovimentacoes_Tipo]
    ON [dbo].[EstoqueMovimentacoes]([CompanyId] ASC, [Tipo] ASC, [CriadoEm] DESC);

GO

GO
CREATE TABLE [dbo].[EstoqueSnapshotDiario] (
    [CompanyId]     NVARCHAR (100)  NOT NULL,
    [DepositoId]    NVARCHAR (40)   NOT NULL,
    [Data]          DATE            NOT NULL,
    [ProdutoId]     NVARCHAR (40)   NOT NULL,
    [Quantidade]    DECIMAL (18, 3) NOT NULL,
    [Valor]         DECIMAL (18, 2) NOT NULL,
    [CustoUnitario] DECIMAL (18, 4) NOT NULL,
    CONSTRAINT [PK_EstoqueSnapshotDiario] PRIMARY KEY CLUSTERED ([CompanyId] ASC, [DepositoId] ASC, [Data] ASC, [ProdutoId] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_EstoqueSnapshot_Produto]
    ON [dbo].[EstoqueSnapshotDiario]([CompanyId] ASC, [ProdutoId] ASC, [Data] ASC);

GO

GO
CREATE TABLE [dbo].[FiadoMovimentos] (
    [Id]             NVARCHAR (40)      NOT NULL,
    [CompanyId]      NVARCHAR (40)      NOT NULL,
    [ClienteId]      NVARCHAR (40)      NOT NULL,
    [Tipo]           TINYINT            NOT NULL,
    [Valor]          DECIMAL (18, 2)    NOT NULL,
    [SaldoAnterior]  DECIMAL (18, 2)    NOT NULL,
    [SaldoAtual]     DECIMAL (18, 2)    NOT NULL,
    [VendaId]        NVARCHAR (40)      NULL,
    [FormaPagamento] NVARCHAR (30)      NULL,
    [Observacao]     NVARCHAR (500)     NULL,
    [OperadorNome]   NVARCHAR (100)     NOT NULL,
    [CriadoEm]       DATETIMEOFFSET (7) NOT NULL,
    CONSTRAINT [PK_FiadoMovimentos] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_FiadoMovimentos_Cliente]
    ON [dbo].[FiadoMovimentos]([CompanyId] ASC, [ClienteId] ASC, [CriadoEm] DESC);

GO

GO
CREATE TABLE [dbo].[FiscalIaAnalises] (
    [Id]              UNIQUEIDENTIFIER NOT NULL,
    [CompanyId]       NVARCHAR (100)   NOT NULL,
    [ProdutoId]       NVARCHAR (100)   NOT NULL,
    [RelatorioJson]   NVARCHAR (MAX)   NOT NULL,
    [CriadaEm]        DATETIME2 (7)    NOT NULL,
    [AplicadaEm]      DATETIME2 (7)    NULL,
    [AplicadaPor]     NVARCHAR (100)   NULL,
    [CamposAplicados] NVARCHAR (MAX)   NULL,
    PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_FiscalIaAnalises_EmpresaProduto]
    ON [dbo].[FiscalIaAnalises]([CompanyId] ASC, [ProdutoId] ASC, [CriadaEm] ASC);

GO

GO
CREATE TABLE [dbo].[FiscalIaConfig] (
    [CompanyId]      NVARCHAR (100)  NOT NULL,
    [ChaveProtegida] NVARCHAR (2000) NOT NULL,
    [UsarJev]        BIT             NOT NULL,
    [AtualizadoEm]   DATETIME2 (7)   NOT NULL,
    PRIMARY KEY CLUSTERED ([CompanyId] ASC)
);

GO

GO
CREATE TABLE [dbo].[FiscalIaSolicitacoes] (
    [Id]          UNIQUEIDENTIFIER NOT NULL,
    [CompanyId]   NVARCHAR (100)   NOT NULL,
    [ProdutoId]   NVARCHAR (100)   NOT NULL,
    [UsuarioId]   NVARCHAR (100)   NOT NULL,
    [Status]      NVARCHAR (20)    NOT NULL,
    [CriadaEm]    DATETIME2 (7)    NOT NULL,
    [IniciadaEm]  DATETIME2 (7)    NULL,
    [ConcluidaEm] DATETIME2 (7)    NULL,
    [AnaliseId]   UNIQUEIDENTIFIER NULL,
    [Mensagem]    NVARCHAR (1000)  NULL,
    PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE UNIQUE NONCLUSTERED INDEX [IX_FiscalIaSolicitacoes_Ativa]
    ON [dbo].[FiscalIaSolicitacoes]([CompanyId] ASC, [ProdutoId] ASC) WHERE ([ConcluidaEm] IS NULL);

GO

GO
CREATE NONCLUSTERED INDEX [IX_FiscalIaSolicitacoes_Fila]
    ON [dbo].[FiscalIaSolicitacoes]([Status] ASC, [CriadaEm] ASC);

GO

GO
CREATE TABLE [dbo].[FiscalSequencias] (
    [CompanyId]     NVARCHAR (40)      NOT NULL,
    [Modelo]        SMALLINT           NOT NULL,
    [Serie]         INT                NOT NULL,
    [Ambiente]      TINYINT            NOT NULL,
    [ProximoNumero] INT                NOT NULL,
    [AtualizadoEm]  DATETIMEOFFSET (7) NOT NULL,
    CONSTRAINT [PK_FiscalSequencias] PRIMARY KEY CLUSTERED ([CompanyId] ASC, [Modelo] ASC, [Serie] ASC, [Ambiente] ASC)
);

GO

GO
CREATE TABLE [dbo].[Fornecedores] (
    [Id]                    NVARCHAR (40)  NOT NULL,
    [CompanyId]             NVARCHAR (40)  NOT NULL,
    [CompanyName]           NVARCHAR (180) NOT NULL,
    [FantasyName]           NVARCHAR (180) NOT NULL,
    [Cnpj]                  NVARCHAR (30)  NOT NULL,
    [Cep]                   NVARCHAR (20)  NOT NULL,
    [City]                  NVARCHAR (120) NOT NULL,
    [State]                 NVARCHAR (2)   NOT NULL,
    [Address]               NVARCHAR (180) NOT NULL,
    [Neighborhood]          NVARCHAR (120) NOT NULL,
    [StreetComplement]      NVARCHAR (180) NOT NULL,
    [Number]                NVARCHAR (30)  NOT NULL,
    [ReferencePoint]        NVARCHAR (180) NOT NULL,
    [Telephone]             NVARCHAR (30)  NOT NULL,
    [Cellphone]             NVARCHAR (30)  NOT NULL,
    [Email]                 NVARCHAR (180) NOT NULL,
    [LeadTimePrometidoDias] INT            NULL,
    CONSTRAINT [PK_Fornecedores] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE UNIQUE NONCLUSTERED INDEX [UQ_Fornecedores_Company_Cnpj]
    ON [dbo].[Fornecedores]([CompanyId] ASC, [Cnpj] ASC) WHERE ([Cnpj] IS NOT NULL AND [Cnpj]<>'');

GO

GO
CREATE NONCLUSTERED INDEX [IX_Fornecedores_Company_Name]
    ON [dbo].[Fornecedores]([CompanyId] ASC, [FantasyName] ASC);

GO

GO
CREATE TABLE [dbo].[GatewayCreditBudgets] (
    [CompanyId]    NVARCHAR (100) NOT NULL,
    [StoreId]      NVARCHAR (100) NOT NULL,
    [CustomerId]   NVARCHAR (100) NOT NULL,
    [MaximumCents] BIGINT         NOT NULL,
    PRIMARY KEY CLUSTERED ([CompanyId] ASC, [StoreId] ASC, [CustomerId] ASC)
);

GO

GO
CREATE TABLE [dbo].[GatewayCreditConsumptions] (
    [CompanyId]       NVARCHAR (100) NOT NULL,
    [AuthorizationId] NVARCHAR (100) NOT NULL,
    [EventId]         NVARCHAR (100) NOT NULL,
    [GrantId]         NVARCHAR (100) NOT NULL,
    [AmountCents]     BIGINT         NOT NULL,
    [SaleHash]        VARCHAR (64)   NOT NULL,
    CONSTRAINT [PK_GatewayCreditConsumptions] PRIMARY KEY CLUSTERED ([CompanyId] ASC, [AuthorizationId] ASC),
    CONSTRAINT [UX_GatewayCreditEvent] UNIQUE NONCLUSTERED ([CompanyId] ASC, [EventId] ASC)
);

GO

GO
CREATE TABLE [dbo].[GatewayCreditGrants] (
    [CompanyId]     NVARCHAR (100)     NOT NULL,
    [GrantId]       NVARCHAR (100)     NOT NULL,
    [CustomerId]    NVARCHAR (100)     NOT NULL,
    [StoreId]       NVARCHAR (100)     NOT NULL,
    [GatewayId]     NVARCHAR (100)     NOT NULL,
    [Epoch]         NVARCHAR (100)     NOT NULL,
    [TokenId]       NVARCHAR (100)     NOT NULL,
    [OperationId]   NVARCHAR (100)     NOT NULL,
    [RequestHash]   VARCHAR (64)       NOT NULL,
    [AmountCents]   BIGINT             NOT NULL,
    [ConsumedCents] BIGINT             NOT NULL,
    [Closed]        BIT                NOT NULL,
    [ExpiresAt]     DATETIMEOFFSET (7) NOT NULL,
    [GrantJson]     NVARCHAR (MAX)     NOT NULL,
    CONSTRAINT [PK_GatewayCreditGrants] PRIMARY KEY CLUSTERED ([CompanyId] ASC, [GrantId] ASC),
    CONSTRAINT [UX_GatewayCreditOperation] UNIQUE NONCLUSTERED ([CompanyId] ASC, [OperationId] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_GatewayCreditCustomer]
    ON [dbo].[GatewayCreditGrants]([CompanyId] ASC, [CustomerId] ASC, [Closed] ASC);

GO

GO
CREATE TABLE [dbo].[GatewayCreditOperatorPermits] (
    [CompanyId]  NVARCHAR (100)     NOT NULL,
    [StoreId]    NVARCHAR (100)     NOT NULL,
    [OperatorId] NVARCHAR (100)     NOT NULL,
    [TerminalId] NVARCHAR (100)     NOT NULL,
    [PermitHash] VARCHAR (64)       NOT NULL,
    [ExpiresAt]  DATETIMEOFFSET (7) NOT NULL,
    PRIMARY KEY CLUSTERED ([CompanyId] ASC, [PermitHash] ASC)
);

GO

GO
CREATE TABLE [dbo].[GatewayCreditStores] (
    [CompanyId]      NVARCHAR (100)  NOT NULL,
    [StoreId]        NVARCHAR (100)  NOT NULL,
    [GatewayId]      NVARCHAR (100)  NOT NULL,
    [Epoch]          NVARCHAR (100)  NOT NULL,
    [TokenId]        NVARCHAR (100)  NOT NULL,
    [State]          VARCHAR (20)    NOT NULL,
    [InventoryJson]  NVARCHAR (MAX)  NOT NULL,
    [ActivationHash] VARCHAR (64)    NULL,
    [GatewayUrl]     NVARCHAR (1000) NULL,
    PRIMARY KEY CLUSTERED ([CompanyId] ASC, [StoreId] ASC)
);

GO

GO
CREATE TABLE [dbo].[GatewayCreditTerminals] (
    [CompanyId]       NVARCHAR (100)     NOT NULL,
    [StoreId]         NVARCHAR (100)     NOT NULL,
    [TerminalId]      NVARCHAR (100)     NOT NULL,
    [ReadyAt]         DATETIMEOFFSET (7) NULL,
    [ProtocolVersion] INT                NULL,
    PRIMARY KEY CLUSTERED ([CompanyId] ASC, [StoreId] ASC, [TerminalId] ASC)
);

GO

GO
CREATE TABLE [dbo].[GatewayTokens] (
    [Id]          NVARCHAR (40)      NOT NULL,
    [CompanyId]   NVARCHAR (100)     NOT NULL,
    [StoreId]     NVARCHAR (100)     NULL,
    [Nome]        NVARCHAR (120)     NOT NULL,
    [TokenHash]   CHAR (64)          NOT NULL,
    [TokenPrefix] NVARCHAR (16)      NOT NULL,
    [CreatedAt]   DATETIMEOFFSET (7) NOT NULL,
    [CreatedBy]   NVARCHAR (100)     NULL,
    [LastUsedAt]  DATETIMEOFFSET (7) NULL,
    [RevokedAt]   DATETIMEOFFSET (7) NULL,
    [RevokedBy]   NVARCHAR (100)     NULL,
    CONSTRAINT [PK_GatewayTokens] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_GatewayTokens_Company]
    ON [dbo].[GatewayTokens]([CompanyId] ASC, [CreatedAt] DESC);

GO

GO
CREATE UNIQUE NONCLUSTERED INDEX [UX_GatewayTokens_TokenHash]
    ON [dbo].[GatewayTokens]([TokenHash] ASC);

GO

GO
CREATE TABLE [dbo].[IntegracaoTokens] (
    [Id]                     NVARCHAR (40)      NOT NULL,
    [CompanyId]              NVARCHAR (100)     NOT NULL,
    [Nome]                   NVARCHAR (120)     NOT NULL,
    [TokenHash]              CHAR (64)          NOT NULL,
    [TokenPrefix]            NVARCHAR (16)      NOT NULL,
    [CreatedAt]              DATETIMEOFFSET (7) NOT NULL,
    [CreatedBy]              NVARCHAR (100)     NULL,
    [LastUsedAt]             DATETIMEOFFSET (7) NULL,
    [RevokedAt]              DATETIMEOFFSET (7) NULL,
    [RevokedBy]              NVARCHAR (100)     NULL,
    [PermiteEscritaProdutos] BIT                NOT NULL,
    CONSTRAINT [PK_IntegracaoTokens] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE UNIQUE NONCLUSTERED INDEX [UX_IntegracaoTokens_TokenHash]
    ON [dbo].[IntegracaoTokens]([TokenHash] ASC);

GO

GO
CREATE NONCLUSTERED INDEX [IX_IntegracaoTokens_Company]
    ON [dbo].[IntegracaoTokens]([CompanyId] ASC, [CreatedAt] DESC);

GO

GO
CREATE TABLE [dbo].[InventarioContagens] (
    [Id]             NVARCHAR (40)      NOT NULL,
    [InventarioId]   NVARCHAR (40)      NOT NULL,
    [CompanyId]      NVARCHAR (40)      NOT NULL,
    [ProdutoId]      NVARCHAR (40)      NOT NULL,
    [Quantidade]     DECIMAL (15, 4)    NOT NULL,
    [Local]          NVARCHAR (80)      NULL,
    [UsuarioId]      NVARCHAR (40)      NOT NULL,
    [UsuarioNome]    NVARCHAR (180)     NOT NULL,
    [Terminal]       NVARCHAR (80)      NULL,
    [Ip]             NVARCHAR (64)      NULL,
    [SaldoSistema]   DECIMAL (15, 4)    NOT NULL,
    [CriadoEm]       DATETIMEOFFSET (7) NOT NULL,
    [AnuladaPorId]   NVARCHAR (40)      NULL,
    [AnuladaPorNome] NVARCHAR (180)     NULL,
    [AnuladaEm]      DATETIMEOFFSET (7) NULL,
    [AnuladaMotivo]  NVARCHAR (300)     NULL,
    CONSTRAINT [PK_InventarioContagens] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_InventarioContagens_Inv]
    ON [dbo].[InventarioContagens]([InventarioId] ASC, [ProdutoId] ASC, [CriadoEm] ASC);

GO

GO
CREATE TABLE [dbo].[InventarioEventos] (
    [Id]             NVARCHAR (40)      NOT NULL,
    [InventarioId]   NVARCHAR (40)      NOT NULL,
    [CompanyId]      NVARCHAR (40)      NOT NULL,
    [Tipo]           NVARCHAR (30)      NOT NULL,
    [OcorridoEm]     DATETIMEOFFSET (7) NOT NULL,
    [UsuarioId]      NVARCHAR (40)      NOT NULL,
    [UsuarioNome]    NVARCHAR (180)     NOT NULL,
    [SupervisorId]   NVARCHAR (40)      NULL,
    [SupervisorNome] NVARCHAR (180)     NULL,
    [Ip]             NVARCHAR (64)      NULL,
    [Terminal]       NVARCHAR (80)      NULL,
    [Descricao]      NVARCHAR (500)     NULL,
    CONSTRAINT [PK_InventarioEventos] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_InventarioEventos_Inv]
    ON [dbo].[InventarioEventos]([InventarioId] ASC, [OcorridoEm] ASC);

GO

GO
CREATE TABLE [dbo].[InventarioItens] (
    [InventarioId]       NVARCHAR (40)      NOT NULL,
    [ProdutoId]          NVARCHAR (40)      NOT NULL,
    [CompanyId]          NVARCHAR (40)      NOT NULL,
    [Codigo]             NVARCHAR (80)      NOT NULL,
    [Nome]               NVARCHAR (180)     NOT NULL,
    [Unidade]            NVARCHAR (6)       NOT NULL,
    [Custo]              DECIMAL (15, 4)    NOT NULL,
    [SaldoNa1aContagem]  DECIMAL (15, 4)    NULL,
    [PrimeiraContagemEm] DATETIMEOFFSET (7) NULL,
    [UltimaContagemEm]   DATETIMEOFFSET (7) NULL,
    [Recontar]           BIT                NOT NULL,
    [Contado]            DECIMAL (15, 4)    NULL,
    [SaldoAntes]         DECIMAL (15, 4)    NULL,
    [Delta]              DECIMAL (15, 4)    NULL,
    [SaldoDepois]        DECIMAL (15, 4)    NULL,
    [ValorDelta]         DECIMAL (15, 2)    NULL,
    [PisoAplicado]       DECIMAL (15, 4)    NULL,
    CONSTRAINT [PK_InventarioItens] PRIMARY KEY CLUSTERED ([InventarioId] ASC, [ProdutoId] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_InventarioItens_Produto]
    ON [dbo].[InventarioItens]([CompanyId] ASC, [ProdutoId] ASC);

GO

GO
CREATE TABLE [dbo].[Inventarios] (
    [Id]                 NVARCHAR (40)      NOT NULL,
    [CompanyId]          NVARCHAR (40)      NOT NULL,
    [Numero]             INT                NOT NULL,
    [Descricao]          NVARCHAR (200)     NOT NULL,
    [Escopo]             NVARCHAR (20)      NOT NULL,
    [EscopoJson]         NVARCHAR (MAX)     NULL,
    [Status]             NVARCHAR (20)      NOT NULL,
    [AbertoPorId]        NVARCHAR (40)      NOT NULL,
    [AbertoPorNome]      NVARCHAR (180)     NOT NULL,
    [AbertoEm]           DATETIMEOFFSET (7) NOT NULL,
    [AplicadoPorId]      NVARCHAR (40)      NULL,
    [AplicadoPorNome]    NVARCHAR (180)     NULL,
    [AplicadoEm]         DATETIMEOFFSET (7) NULL,
    [SupervisorId]       NVARCHAR (40)      NULL,
    [SupervisorNome]     NVARCHAR (180)     NULL,
    [Justificativa]      NVARCHAR (500)     NULL,
    [ZerarNaoContados]   BIT                NOT NULL,
    [CanceladoPorId]     NVARCHAR (40)      NULL,
    [CanceladoPorNome]   NVARCHAR (180)     NULL,
    [CanceladoEm]        DATETIMEOFFSET (7) NULL,
    [MotivoCancelamento] NVARCHAR (500)     NULL,
    [TotalSobra]         DECIMAL (15, 2)    NULL,
    [TotalFalta]         DECIMAL (15, 2)    NULL,
    [TotalLiquido]       DECIMAL (15, 2)    NULL,
    CONSTRAINT [PK_Inventarios] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE UNIQUE NONCLUSTERED INDEX [UQ_Inventarios_Numero]
    ON [dbo].[Inventarios]([CompanyId] ASC, [Numero] ASC);

GO

GO
CREATE NONCLUSTERED INDEX [IX_Inventarios_Status]
    ON [dbo].[Inventarios]([CompanyId] ASC, [Status] ASC, [AbertoEm] DESC);

GO

GO
CREATE TABLE [dbo].[LojaGatewayConfig] (
    [CompanyId]  NVARCHAR (100) NOT NULL,
    [StoreId]    NVARCHAR (100) NULL,
    [GatewayUrl] NVARCHAR (500) NOT NULL,
    [Enabled]    BIT            NOT NULL,
    [UpdatedAt]  DATETIME2 (7)  NOT NULL,
    [UpdatedBy]  NVARCHAR (100) NULL,
    CONSTRAINT [PK_LojaGatewayConfig] PRIMARY KEY CLUSTERED ([CompanyId] ASC)
);

GO

GO
CREATE TABLE [dbo].[LoteConfig] (
    [CompanyId]    NVARCHAR (40)      NOT NULL,
    [Modo]         NVARCHAR (12)      NOT NULL,
    [AtualizadoEm] DATETIMEOFFSET (7) NOT NULL,
    CONSTRAINT [PK_LoteConfig] PRIMARY KEY CLUSTERED ([CompanyId] ASC)
);

GO

GO
CREATE TABLE [dbo].[LoteConsumos] (
    [Id]         NVARCHAR (40)      NOT NULL,
    [CompanyId]  NVARCHAR (40)      NOT NULL,
    [RefId]      NVARCHAR (60)      NOT NULL,
    [Origem]     NVARCHAR (20)      NOT NULL,
    [ProdutoId]  NVARCHAR (40)      NOT NULL,
    [LoteId]     NVARCHAR (40)      NULL,
    [Quantidade] DECIMAL (15, 4)    NOT NULL,
    [Estornado]  BIT                NOT NULL,
    [CriadoEm]   DATETIMEOFFSET (7) NOT NULL,
    CONSTRAINT [PK_LoteConsumos] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_LoteConsumos_Ref]
    ON [dbo].[LoteConsumos]([CompanyId] ASC, [RefId] ASC, [ProdutoId] ASC);

GO

GO
CREATE TABLE [dbo].[MapeamentoProdutoFornecedor] (
    [Id]                      NVARCHAR (100) NOT NULL,
    [CompanyId]               NVARCHAR (100) NOT NULL,
    [FornecedorCnpj]          NVARCHAR (20)  NOT NULL,
    [CodigoProdutoFornecedor] NVARCHAR (100) NOT NULL,
    [GtinFornecedor]          NVARCHAR (50)  NULL,
    [DescricaoFornecedor]     NVARCHAR (300) NULL,
    [ProdutoId]               NVARCHAR (100) NOT NULL,
    [CreatedAt]               DATETIME2 (7)  NOT NULL,
    [UpdatedAt]               DATETIME2 (7)  NOT NULL,
    CONSTRAINT [PK_MapeamentoProdutoFornecedor] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE UNIQUE NONCLUSTERED INDEX [IX_MapeamentoProdFornec_Lookup]
    ON [dbo].[MapeamentoProdutoFornecedor]([CompanyId] ASC, [FornecedorCnpj] ASC, [CodigoProdutoFornecedor] ASC);

GO

GO
CREATE NONCLUSTERED INDEX [IX_MapeamentoProdFornec_Produto]
    ON [dbo].[MapeamentoProdutoFornecedor]([CompanyId] ASC, [ProdutoId] ASC);

GO

GO
CREATE TABLE [dbo].[ModuloMercadoRegistros] (
    [Id]          NVARCHAR (100) NOT NULL,
    [CompanyId]   NVARCHAR (40)  NOT NULL,
    [ModuleId]    NVARCHAR (80)  NOT NULL,
    [Title]       NVARCHAR (180) NOT NULL,
    [Description] NVARCHAR (500) NOT NULL,
    [Status]      NVARCHAR (80)  NOT NULL,
    [Amount]      NVARCHAR (40)  NOT NULL,
    [Meta]        NVARCHAR (180) NOT NULL,
    CONSTRAINT [PK_ModuloMercadoRegistros] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_ModuloMercadoRegistros_ModuleId]
    ON [dbo].[ModuloMercadoRegistros]([ModuleId] ASC);

GO

GO
CREATE TABLE [dbo].[ModulosMercado] (
    [Id]    NVARCHAR (80)  NOT NULL,
    [Title] NVARCHAR (180) NOT NULL,
    CONSTRAINT [PK_ModulosMercado] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE TABLE [dbo].[NotasEntradaArquivo] (
    [Id]                      NVARCHAR (64)   NOT NULL,
    [CompanyId]               NVARCHAR (100)  NOT NULL,
    [Identidade]              CHAR (64)       NOT NULL,
    [ChaveAcesso]             VARCHAR (44)    NULL,
    [Modelo]                  INT             NOT NULL,
    [NumeroNota]              NVARCHAR (30)   NOT NULL,
    [Serie]                   NVARCHAR (10)   NOT NULL,
    [FornecedorNome]          NVARCHAR (250)  NOT NULL,
    [FornecedorCnpj]          VARCHAR (14)    NOT NULL,
    [DataEmissao]             NVARCHAR (40)   NULL,
    [ValorNota]               DECIMAL (18, 2) NULL,
    [ValorEntrada]            DECIMAL (18, 2) NOT NULL,
    [QuantidadeItens]         INT             NOT NULL,
    [Origem]                  VARCHAR (30)    NOT NULL,
    [XmlOriginal]             VARBINARY (MAX) NULL,
    [EntradaJson]             NVARCHAR (MAX)  NOT NULL,
    [UsuarioId]               NVARCHAR (100)  NOT NULL,
    [UsuarioNome]             NVARCHAR (250)  NOT NULL,
    [CriadaEm]                DATETIME2 (7)   NOT NULL,
    [ComprovanteNome]         NVARCHAR (180)  NULL,
    [ComprovanteTipo]         VARCHAR (80)    NULL,
    [ComprovanteOriginal]     VARBINARY (MAX) NULL,
    [OperacaoManualId]        VARCHAR (32)    NULL,
    [Estornada]               BIT             NOT NULL,
    [EstornadaEm]             DATETIME2 (7)   NULL,
    [EstornadaPorUsuarioId]   NVARCHAR (100)  NULL,
    [EstornadaPorUsuarioNome] NVARCHAR (250)  NULL,
    [EstornoJustificativa]    NVARCHAR (500)  NULL,
    PRIMARY KEY CLUSTERED ([Id] ASC),
    CONSTRAINT [UQ_NotasEntradaArquivo_Identidade] UNIQUE NONCLUSTERED ([CompanyId] ASC, [Identidade] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_NotasEntradaArquivo_Company_Data]
    ON [dbo].[NotasEntradaArquivo]([CompanyId] ASC, [CriadaEm] DESC);

GO

GO
CREATE UNIQUE NONCLUSTERED INDEX [UQ_NotasEntrada_OperacaoManual]
    ON [dbo].[NotasEntradaArquivo]([CompanyId] ASC, [OperacaoManualId] ASC) WHERE ([OperacaoManualId] IS NOT NULL);

GO

GO
CREATE TABLE [dbo].[OrdemCompra] (
    [Id]                 NVARCHAR (40)      NOT NULL,
    [CompanyId]          NVARCHAR (40)      NOT NULL,
    [OrderNumber]        NVARCHAR (20)      NOT NULL,
    [SupplierId]         NVARCHAR (40)      NULL,
    [SupplierName]       NVARCHAR (180)     NOT NULL,
    [SupplierCnpj]       NVARCHAR (30)      NULL,
    [Status]             TINYINT            NOT NULL,
    [CreatedBy]          NVARCHAR (40)      NULL,
    [CreatedByName]      NVARCHAR (180)     NULL,
    [CreatedAt]          DATETIMEOFFSET (7) NOT NULL,
    [ReceivedAt]         DATETIMEOFFSET (7) NULL,
    [CanceledAt]         DATETIMEOFFSET (7) NULL,
    [Note]               NVARCHAR (500)     NULL,
    [TotalEstimado]      DECIMAL (18, 2)    NOT NULL,
    [PrevisaoEntrega]    DATETIMEOFFSET (7) NULL,
    [CondicaoPagamento]  NVARCHAR (100)     NULL,
    [FormaPagamento]     NVARCHAR (50)      NULL,
    [ValorFrete]         DECIMAL (18, 2)    NOT NULL,
    [ValorDesconto]      DECIMAL (18, 2)    NOT NULL,
    [MotivoCancelamento] NVARCHAR (300)     NULL,
    [ReceivedBy]         NVARCHAR (40)      NULL,
    [ReceivedByName]     NVARCHAR (180)     NULL,
    CONSTRAINT [PK_OrdemCompra] PRIMARY KEY CLUSTERED ([Id] ASC),
    CONSTRAINT [UQ_OC_Company_Number] UNIQUE NONCLUSTERED ([CompanyId] ASC, [OrderNumber] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_OrdemCompra_Company_Status]
    ON [dbo].[OrdemCompra]([CompanyId] ASC, [Status] ASC, [CreatedAt] DESC);

GO

GO
CREATE TABLE [dbo].[OrdemCompraItens] (
    [Id]               NVARCHAR (40)      NOT NULL,
    [OrdemCompraId]    NVARCHAR (40)      NOT NULL,
    [ProductCode]      NVARCHAR (80)      NOT NULL,
    [ProductName]      NVARCHAR (180)     NOT NULL,
    [Quantity]         DECIMAL (15, 4)    NOT NULL,
    [UnitCost]         DECIMAL (15, 4)    NOT NULL,
    [ItemTotal]        DECIMAL (18, 2)    NOT NULL,
    [QuantityReceived] DECIMAL (15, 4)    NOT NULL,
    [DataValidade]     DATETIMEOFFSET (7) NULL,
    CONSTRAINT [PK_OCItens] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE TABLE [dbo].[PasswordResetTokens] (
    [Id]                 NVARCHAR (40)      NOT NULL,
    [UserId]             NVARCHAR (40)      NOT NULL,
    [Email]              NVARCHAR (180)     NOT NULL,
    [Cnpj]               NVARCHAR (30)      NULL,
    [TokenHash]          NVARCHAR (128)     NOT NULL,
    [CreatedAt]          DATETIMEOFFSET (7) NOT NULL,
    [RequestedAt]        DATETIMEOFFSET (7) NOT NULL,
    [ExpiresAt]          DATETIMEOFFSET (7) NOT NULL,
    [ConsumedAt]         DATETIMEOFFSET (7) NULL,
    [RequestedIp]        NVARCHAR (64)      NULL,
    [RequestedUserAgent] NVARCHAR (500)     NULL,
    [RequestedDevice]    NVARCHAR (120)     NULL,
    [ResetIp]            NVARCHAR (64)      NULL,
    [ResetUserAgent]     NVARCHAR (500)     NULL,
    [ResetDevice]        NVARCHAR (120)     NULL,
    [UpdatedAt]          DATETIMEOFFSET (7) NOT NULL,
    CONSTRAINT [PK_PasswordResetTokens] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_PasswordResetTokens_UserId]
    ON [dbo].[PasswordResetTokens]([UserId] ASC);

GO

GO
CREATE UNIQUE NONCLUSTERED INDEX [IX_PasswordResetTokens_TokenHash]
    ON [dbo].[PasswordResetTokens]([TokenHash] ASC);

GO

GO
CREATE NONCLUSTERED INDEX [IX_PasswordResetTokens_ExpiresAt]
    ON [dbo].[PasswordResetTokens]([ExpiresAt] ASC);

GO

GO
CREATE NONCLUSTERED INDEX [IX_PasswordResetTokens_ConsumedAt]
    ON [dbo].[PasswordResetTokens]([ConsumedAt] ASC);

GO

GO
CREATE TABLE [dbo].[PedidoItens] (
    [Id]          NVARCHAR (40)   NOT NULL,
    [PedidoId]    NVARCHAR (40)   NOT NULL,
    [ProductCode] NVARCHAR (80)   NOT NULL,
    [ProductName] NVARCHAR (180)  NOT NULL,
    [Quantity]    DECIMAL (15, 4) NOT NULL,
    [UnitPrice]   DECIMAL (15, 4) NOT NULL,
    [ItemTotal]   DECIMAL (18, 2) NOT NULL,
    CONSTRAINT [PK_PedidoItens] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_PedidoItens_PedidoId]
    ON [dbo].[PedidoItens]([PedidoId] ASC);

GO

GO
CREATE TABLE [dbo].[Pedidos] (
    [Id]           NVARCHAR (40)      NOT NULL,
    [CompanyId]    NVARCHAR (40)      NOT NULL,
    [OrderNumber]  NVARCHAR (20)      NOT NULL,
    [CustomerName] NVARCHAR (180)     NOT NULL,
    [CustomerCpf]  NVARCHAR (30)      NOT NULL,
    [SellerId]     NVARCHAR (40)      NOT NULL,
    [SellerName]   NVARCHAR (180)     NOT NULL,
    [Status]       TINYINT            NOT NULL,
    [VendaId]      NVARCHAR (40)      NULL,
    [CreatedAt]    DATETIMEOFFSET (7) NOT NULL,
    [FinalizedAt]  DATETIMEOFFSET (7) NULL,
    [CanceledAt]   DATETIMEOFFSET (7) NULL,
    [Note]         NVARCHAR (500)     NOT NULL,
    CONSTRAINT [PK_Pedidos] PRIMARY KEY CLUSTERED ([Id] ASC),
    CONSTRAINT [UQ_Pedidos_Company_OrderNumber] UNIQUE NONCLUSTERED ([CompanyId] ASC, [OrderNumber] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_Pedidos_Company_Status]
    ON [dbo].[Pedidos]([CompanyId] ASC, [Status] ASC, [CreatedAt] DESC);

GO

GO
CREATE TABLE [dbo].[ProcessedEvents] (
    [Id]              NVARCHAR (50)      NOT NULL,
    [CompanyId]       NVARCHAR (40)      NOT NULL,
    [EventId]         NVARCHAR (50)      NOT NULL,
    [EventType]       NVARCHAR (50)      NOT NULL,
    [ClientSaleId]    NVARCHAR (50)      NULL,
    [PayloadHash]     NVARCHAR (64)      NOT NULL,
    [ProcessedAt]     DATETIMEOFFSET (7) NOT NULL,
    [ResponsePayload] NVARCHAR (MAX)     NOT NULL,
    CONSTRAINT [PK_ProcessedEvents] PRIMARY KEY CLUSTERED ([Id] ASC),
    CONSTRAINT [UQ_ProcessedEvents_Company_Event] UNIQUE NONCLUSTERED ([CompanyId] ASC, [EventId] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_ProcessedEvents_ProcessedAt]
    ON [dbo].[ProcessedEvents]([ProcessedAt] ASC);

GO

GO
CREATE NONCLUSTERED INDEX [IX_ProcessedEvents_Company_ClientSaleId]
    ON [dbo].[ProcessedEvents]([CompanyId] ASC, [ClientSaleId] ASC) WHERE ([ClientSaleId] IS NOT NULL);

GO

GO
CREATE TABLE [dbo].[ProdutoClassificacao] (
    [CompanyId]   NVARCHAR (100) NOT NULL,
    [ProdutoId]   NVARCHAR (40)  NOT NULL,
    [Abc]         CHAR (1)       NOT NULL,
    [Fsn]         CHAR (1)       NOT NULL,
    [CalculadoEm] DATETIME2 (7)  NOT NULL,
    CONSTRAINT [PK_ProdutoClassificacao] PRIMARY KEY CLUSTERED ([CompanyId] ASC, [ProdutoId] ASC)
);

GO

GO
CREATE TABLE [dbo].[ProdutoLotes] (
    [Id]             NVARCHAR (40)      NOT NULL,
    [CompanyId]      NVARCHAR (40)      NOT NULL,
    [ProdutoId]      NVARCHAR (40)      NOT NULL,
    [NumeroLote]     NVARCHAR (60)      NULL,
    [DataValidade]   DATE               NOT NULL,
    [QtdInicial]     DECIMAL (15, 4)    NOT NULL,
    [Origem]         NVARCHAR (20)      NOT NULL,
    [ValidadePadrao] BIT                NOT NULL,
    [CriadoEm]       DATETIMEOFFSET (7) NOT NULL,
    [CriadoPorNome]  NVARCHAR (120)     NULL,
    [QtdAtual]       DECIMAL (15, 4)    NULL,
    CONSTRAINT [PK_ProdutoLotes] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_ProdutoLotes_Produto]
    ON [dbo].[ProdutoLotes]([CompanyId] ASC, [ProdutoId] ASC, [DataValidade] DESC);

GO

GO
CREATE TABLE [dbo].[Produtos] (
    [Id]                       NVARCHAR (40)   NOT NULL,
    [CompanyId]                NVARCHAR (40)   NOT NULL,
    [ProductImageUrl]          NVARCHAR (500)  NOT NULL,
    [ProductImageName]         NVARCHAR (180)  NOT NULL,
    [ProductName]              NVARCHAR (180)  NOT NULL,
    [ProductCode]              NVARCHAR (80)   NOT NULL,
    [ProductSupplier]          NVARCHAR (180)  NOT NULL,
    [SupplierId]               NVARCHAR (40)   NULL,
    [ProductDescription]       NVARCHAR (500)  NOT NULL,
    [MargemDesejadaPercentual] DECIMAL (9, 4)  NULL,
    [EstoqueMinimo]            DECIMAL (15, 4) NOT NULL,
    [ProductQnt]               DECIMAL (15, 4) NOT NULL,
    [ProductUnitPrice]         DECIMAL (15, 4) NOT NULL,
    [ProductSalePrice]         DECIMAL (15, 4) NOT NULL,
    [TotalPriceOnProduct]      DECIMAL (18, 2) NOT NULL,
    [Ncm]                      CHAR (8)        NOT NULL,
    [Cest]                     CHAR (7)        NULL,
    [Cfop]                     CHAR (4)        NOT NULL,
    [OrigemMercadoria]         TINYINT         NOT NULL,
    [UnidadeComercial]         NVARCHAR (6)    NOT NULL,
    [UnidadeTributavel]        NVARCHAR (6)    NOT NULL,
    [Gtin]                     NVARCHAR (14)   NOT NULL,
    [CsosnIcms]                NVARCHAR (4)    NULL,
    [CstIcms]                  NVARCHAR (3)    NULL,
    [AliquotaIcms]             DECIMAL (7, 4)  NOT NULL,
    [CstPis]                   NVARCHAR (3)    NOT NULL,
    [CstCofins]                NVARCHAR (3)    NOT NULL,
    [CstIbsCbs]                NVARCHAR (3)    NULL,
    [CClassTrib]               NVARCHAR (6)    NULL,
    [CategoriaId]              NVARCHAR (40)   NULL,
    [DataValidade]             DATE            NULL,
    [ControlaValidade]         BIT             NOT NULL,
    [DiasAlertaValidade]       INT             NOT NULL,
    [UnidadeCompra]            NVARCHAR (6)    NOT NULL,
    [FatorConversao]           DECIMAL (15, 4) NOT NULL,
    [QtdEmbalagem]             DECIMAL (15, 4) NOT NULL,
    [Marca]                    NVARCHAR (120)  NULL,
    [Fabricante]               NVARCHAR (120)  NULL,
    [ReferenciaFabricante]     NVARCHAR (80)   NULL,
    [PesoLiquidoKg]            DECIMAL (15, 4) NOT NULL,
    [PesoBrutoKg]              DECIMAL (15, 4) NOT NULL,
    [LarguraCm]                DECIMAL (10, 2) NOT NULL,
    [AlturaCm]                 DECIMAL (10, 2) NOT NULL,
    [ComprimentoCm]            DECIMAL (10, 2) NOT NULL,
    [EstoqueMaximo]            DECIMAL (15, 4) NOT NULL,
    [LocalizacaoEstoque]       NVARCHAR (100)  NULL,
    [CustoMedio]               DECIMAL (15, 4) NOT NULL,
    [CustoComImposto]          DECIMAL (15, 4) NOT NULL,
    [CustoSemImposto]          DECIMAL (15, 4) NOT NULL,
    [DescontoMaximoPercentual] DECIMAL (9, 4)  NOT NULL,
    [ComissaoPercentual]       DECIMAL (9, 4)  NOT NULL,
    [MarkupCadastrado]         DECIMAL (9, 4)  NOT NULL,
    [MarkupPraticado]          DECIMAL (9, 4)  NOT NULL,
    [Lucro]                    DECIMAL (18, 2) NOT NULL,
    [LeadTimeDias]             INT             NULL,
    CONSTRAINT [PK_Produtos] PRIMARY KEY CLUSTERED ([Id] ASC),
    CONSTRAINT [UQ_Produtos_Company_ProductCode] UNIQUE NONCLUSTERED ([CompanyId] ASC, [ProductCode] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_Produtos_Validade]
    ON [dbo].[Produtos]([CompanyId] ASC, [ControlaValidade] ASC, [DataValidade] ASC)
    INCLUDE([ProductCode], [ProductName], [ProductQnt], [ProductUnitPrice], [ProductSalePrice], [CategoriaId]) WHERE ([ControlaValidade]=(1) AND [DataValidade] IS NOT NULL);

GO

GO
CREATE NONCLUSTERED INDEX [IX_Produtos_Company_Marca]
    ON [dbo].[Produtos]([CompanyId] ASC, [Marca] ASC) WHERE ([Marca] IS NOT NULL);

GO

GO
CREATE NONCLUSTERED INDEX [IX_Produtos_Company_Categoria]
    ON [dbo].[Produtos]([CompanyId] ASC, [CategoriaId] ASC);

GO

GO
CREATE NONCLUSTERED INDEX [IX_Produtos_Company_Code]
    ON [dbo].[Produtos]([CompanyId] ASC, [ProductCode] ASC);

GO

GO
CREATE TABLE [dbo].[PromocaoProdutos] (
    [Id]         NVARCHAR (40) NOT NULL,
    [PromocaoId] NVARCHAR (40) NOT NULL,
    [ProdutoId]  NVARCHAR (40) NOT NULL,
    CONSTRAINT [PK_PromocaoProdutos] PRIMARY KEY CLUSTERED ([Id] ASC),
    CONSTRAINT [UQ_PromocaoProdutos] UNIQUE NONCLUSTERED ([PromocaoId] ASC, [ProdutoId] ASC)
);

GO

GO
CREATE TABLE [dbo].[Promocoes] (
    [Id]               NVARCHAR (40)      NOT NULL,
    [CompanyId]        NVARCHAR (40)      NOT NULL,
    [Nome]             NVARCHAR (180)     NOT NULL,
    [Tipo]             NVARCHAR (30)      NOT NULL,
    [ValorDesconto]    DECIMAL (18, 2)    NULL,
    [PrecoFixo]        DECIMAL (18, 2)    NULL,
    [QuantidadeLeva]   INT                NULL,
    [QuantidadePaga]   INT                NULL,
    [QuantidadeMinima] INT                NULL,
    [InicioVigencia]   DATETIMEOFFSET (7) NOT NULL,
    [FimVigencia]      DATETIMEOFFSET (7) NOT NULL,
    [Ativa]            BIT                NOT NULL,
    [CategoriaId]      NVARCHAR (40)      NULL,
    [CriadoPor]        NVARCHAR (180)     NOT NULL,
    [CriadoEm]         DATETIMEOFFSET (7) NOT NULL,
    CONSTRAINT [PK_Promocoes] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_Promocoes_Vigencia]
    ON [dbo].[Promocoes]([CompanyId] ASC, [Ativa] ASC, [InicioVigencia] ASC, [FimVigencia] ASC);

GO

GO
CREATE TABLE [dbo].[RegrasEmissaoNfce] (
    [CompanyId]                  NVARCHAR (100) NOT NULL,
    [Habilitado]                 BIT            NOT NULL,
    [FormasPagamentoHabilitadas] NVARCHAR (500) NOT NULL,
    [IntervaloNotas]             INT            NOT NULL,
    [EmitirSempreComCpf]         BIT            NOT NULL,
    [ContadorVendas]             INT            NOT NULL,
    [UpdatedAt]                  DATETIME2 (7)  NOT NULL,
    [UpdatedBy]                  NVARCHAR (100) NULL,
    [SemanticaV2]                BIT            NOT NULL,
    CONSTRAINT [PK_RegrasEmissaoNfce] PRIMARY KEY CLUSTERED ([CompanyId] ASC)
);

GO

GO
CREATE TABLE [dbo].[Sessoes] (
    [Id]         NVARCHAR (80)      NOT NULL,
    [UserId]     NVARCHAR (40)      NOT NULL,
    [Device]     NVARCHAR (120)     NOT NULL,
    [Location]   NVARCHAR (150)     NOT NULL,
    [Ip]         NVARCHAR (64)      NOT NULL,
    [LastActive] NVARCHAR (80)      NOT NULL,
    [Platform]   NVARCHAR (20)      NOT NULL,
    [CreatedAt]  DATETIMEOFFSET (7) NOT NULL,
    CONSTRAINT [PK_Sessoes] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_Sessoes_UserId]
    ON [dbo].[Sessoes]([UserId] ASC);

GO

GO
CREATE TABLE [dbo].[TreinamentoSecoes] (
    [Id]        NVARCHAR (40)      NOT NULL,
    [Nome]      NVARCHAR (120)     NOT NULL,
    [Descricao] NVARCHAR (400)     NULL,
    [Ordem]     INT                NOT NULL,
    [CriadoEm]  DATETIMEOFFSET (7) NOT NULL,
    CONSTRAINT [PK_TreinamentoSecoes] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE TABLE [dbo].[TreinamentoVideos] (
    [Id]         NVARCHAR (40)      NOT NULL,
    [SecaoId]    NVARCHAR (40)      NOT NULL,
    [Titulo]     NVARCHAR (160)     NOT NULL,
    [Descricao]  NVARCHAR (600)     NULL,
    [Instrucoes] NVARCHAR (MAX)     NULL,
    [YoutubeId]  NVARCHAR (20)      NOT NULL,
    [Ordem]      INT                NOT NULL,
    [CriadoEm]   DATETIMEOFFSET (7) NOT NULL,
    CONSTRAINT [PK_TreinamentoVideos] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_TreinamentoVideos_Secao]
    ON [dbo].[TreinamentoVideos]([SecaoId] ASC, [Ordem] ASC);

GO

GO
CREATE TABLE [dbo].[UserCreditStores] (
    [CompanyId] NVARCHAR (100) NOT NULL,
    [UserId]    NVARCHAR (100) NOT NULL,
    [StoreId]   NVARCHAR (100) NOT NULL,
    PRIMARY KEY CLUSTERED ([CompanyId] ASC, [UserId] ASC)
);

GO

GO
CREATE TABLE [dbo].[Usuarios] (
    [Id]                  NVARCHAR (40)      NOT NULL,
    [CompanyId]           NVARCHAR (40)      NOT NULL,
    [Cpf]                 NVARCHAR (30)      NOT NULL,
    [Name]                NVARCHAR (180)     NOT NULL,
    [Email]               NVARCHAR (180)     NOT NULL,
    [Phone]               NVARCHAR (30)      NOT NULL,
    [Role]                NVARCHAR (60)      NOT NULL,
    [Status]              NVARCHAR (20)      NOT NULL,
    [CreatedAt]           NVARCHAR (30)      NOT NULL,
    [LastLoginAt]         NVARCHAR (60)      NOT NULL,
    [PasswordHash]        NVARCHAR (300)     NOT NULL,
    [MustChangePassword]  BIT                NOT NULL,
    [FailedLoginAttempts] INT                NOT NULL,
    [FirstFailedLoginAt]  DATETIMEOFFSET (7) NULL,
    [LockoutEnd]          DATETIMEOFFSET (7) NULL,
    [PerfisAdicionais]    NVARCHAR (200)     NOT NULL,
    CONSTRAINT [PK_Usuarios] PRIMARY KEY CLUSTERED ([Id] ASC),
    CONSTRAINT [UQ_Usuarios_Company_Cpf] UNIQUE NONCLUSTERED ([CompanyId] ASC, [Cpf] ASC),
    CONSTRAINT [UQ_Usuarios_Email] UNIQUE NONCLUSTERED ([Email] ASC)
);

GO

GO
CREATE TABLE [dbo].[VendaCancelamentos] (
    [Id]                NVARCHAR (40)      NOT NULL,
    [CompanyId]         NVARCHAR (40)      NOT NULL,
    [OcorridoEm]        DATETIMEOFFSET (7) NOT NULL,
    [Resultado]         NVARCHAR (20)      NOT NULL,
    [Via]               NVARCHAR (20)      NOT NULL,
    [MotivoRecusa]      NVARCHAR (500)     NULL,
    [VendaId]           NVARCHAR (40)      NULL,
    [SaleNumber]        NVARCHAR (40)      NULL,
    [OperadorId]        NVARCHAR (40)      NOT NULL,
    [OperadorNome]      NVARCHAR (180)     NOT NULL,
    [SupervisorId]      NVARCHAR (40)      NULL,
    [SupervisorNome]    NVARCHAR (180)     NULL,
    [Justificativa]     NVARCHAR (500)     NULL,
    [Terminal]          NVARCHAR (80)      NULL,
    [Ip]                NVARCHAR (64)      NULL,
    [CaixaSessaoId]     NVARCHAR (60)      NULL,
    [CaixaSessaoStatus] NVARCHAR (20)      NULL,
    [TotalVenda]        DECIMAL (15, 2)    NULL,
    [ClienteNome]       NVARCHAR (180)     NULL,
    [ClienteCpf]        NVARCHAR (30)      NULL,
    [ItensEstornados]   INT                NULL,
    [FiadoEstornado]    DECIMAL (15, 2)    NULL,
    [Snapshot]          NVARCHAR (MAX)     NULL,
    CONSTRAINT [PK_VendaCancelamentos] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_VendaCancelamentos_Venda]
    ON [dbo].[VendaCancelamentos]([CompanyId] ASC, [SaleNumber] ASC);

GO

GO
CREATE NONCLUSTERED INDEX [IX_VendaCancelamentos_Company]
    ON [dbo].[VendaCancelamentos]([CompanyId] ASC, [OcorridoEm] DESC);

GO

GO
CREATE TABLE [dbo].[VendaItens] (
    [Id]          NVARCHAR (40)   NOT NULL,
    [VendaId]     NVARCHAR (40)   NOT NULL,
    [ProductCode] NVARCHAR (80)   NOT NULL,
    [ProductName] NVARCHAR (180)  NOT NULL,
    [Quantity]    DECIMAL (15, 4) NOT NULL,
    [UnitPrice]   DECIMAL (15, 4) NOT NULL,
    [ItemTotal]   DECIMAL (18, 2) NOT NULL,
    [Desconto]    DECIMAL (18, 2) NOT NULL,
    [PromocaoId]  NVARCHAR (40)   NULL,
    [CostPrice]   DECIMAL (18, 4) NULL,
    CONSTRAINT [PK_VendaItens] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_VendaItens_VendaId]
    ON [dbo].[VendaItens]([VendaId] ASC);

GO

GO
CREATE TABLE [dbo].[VendaPagamentos] (
    [Id]           NVARCHAR (40)      NOT NULL,
    [CompanyId]    NVARCHAR (40)      NOT NULL,
    [VendaId]      NVARCHAR (40)      NOT NULL,
    [PaymentType]  NVARCHAR (30)      NOT NULL,
    [Amount]       DECIMAL (18, 2)    NOT NULL,
    [CashGiven]    DECIMAL (18, 2)    NULL,
    [ChangeAmount] DECIMAL (18, 2)    NULL,
    [CreatedAt]    DATETIMEOFFSET (7) NOT NULL,
    CONSTRAINT [PK_VendaPagamentos] PRIMARY KEY CLUSTERED ([Id] ASC)
);

GO

GO
CREATE NONCLUSTERED INDEX [IX_VendaPagamentos_Company_Payment]
    ON [dbo].[VendaPagamentos]([CompanyId] ASC, [PaymentType] ASC);

GO

GO
CREATE NONCLUSTERED INDEX [IX_VendaPagamentos_Company_Venda]
    ON [dbo].[VendaPagamentos]([CompanyId] ASC, [VendaId] ASC);

GO

GO
CREATE TABLE [dbo].[Vendas] (
    [Id]                         NVARCHAR (40)      NOT NULL,
    [CompanyId]                  NVARCHAR (40)      NOT NULL,
    [SaleNumber]                 NVARCHAR (30)      NOT NULL,
    [CustomerName]               NVARCHAR (180)     NOT NULL,
    [CustomerCpf]                NVARCHAR (30)      NOT NULL,
    [PaymentType]                NVARCHAR (30)      NOT NULL,
    [OperatorName]               NVARCHAR (180)     NOT NULL,
    [SaleDate]                   DATETIMEOFFSET (7) NOT NULL,
    [CaixaSessaoId]              NVARCHAR (40)      NULL,
    [TotalAmount]                DECIMAL (18, 2)    NOT NULL,
    [ClientSaleId]               NVARCHAR (50)      NULL,
    [OfflineReference]           NVARCHAR (50)      NULL,
    [SyncedAt]                   DATETIMEOFFSET (7) NULL,
    [Status]                     NVARCHAR (30)      NOT NULL,
    [CanceladoEm]                DATETIMEOFFSET (7) NULL,
    [CanceladoPorOperadorId]     NVARCHAR (40)      NULL,
    [CanceladoPorOperadorNome]   NVARCHAR (180)     NULL,
    [CanceladoPorSupervisorId]   NVARCHAR (40)      NULL,
    [CanceladoPorSupervisorNome] NVARCHAR (180)     NULL,
    [CanceladoJustificativa]     NVARCHAR (500)     NULL,
    [EmitirNfce]                 BIT                NULL,
    [CorrigidaParaVendaId]       NVARCHAR (64)      NULL,
    [CorrigidaDeVendaId]         NVARCHAR (64)      NULL,
    CONSTRAINT [PK_Vendas] PRIMARY KEY CLUSTERED ([Id] ASC),
    CONSTRAINT [UQ_Vendas_Company_SaleNumber] UNIQUE NONCLUSTERED ([CompanyId] ASC, [SaleNumber] ASC)
);

GO

GO
CREATE UNIQUE NONCLUSTERED INDEX [IX_Vendas_Company_ClientSaleId]
    ON [dbo].[Vendas]([CompanyId] ASC, [ClientSaleId] ASC) WHERE ([ClientSaleId] IS NOT NULL);

GO

GO
CREATE NONCLUSTERED INDEX [IX_Vendas_Company_SaleDate]
    ON [dbo].[Vendas]([CompanyId] ASC, [SaleDate] DESC)
    INCLUDE([TotalAmount]);

GO

GO
CREATE NONCLUSTERED INDEX [IX_Vendas_CaixaSessaoId]
    ON [dbo].[Vendas]([CaixaSessaoId] ASC) WHERE ([CaixaSessaoId] IS NOT NULL);

GO

GO
CREATE NONCLUSTERED INDEX [IX_Vendas_Company_Status_CanceladoEm]
    ON [dbo].[Vendas]([CompanyId] ASC, [Status] ASC, [CanceladoEm] ASC)
    INCLUDE([SaleNumber], [TotalAmount], [OperatorName], [CanceladoPorSupervisorNome]);

GO

GO

GO

GO
ALTER TABLE [dbo].[AuditLog]
    ADD CONSTRAINT [DF_AuditLog_Description] DEFAULT (N'') FOR [Description];

GO

GO
ALTER TABLE [dbo].[AuditLog]
    ADD CONSTRAINT [DF_AuditLog_UserName] DEFAULT (N'') FOR [UserName];

GO

GO
ALTER TABLE [dbo].[AuditLog]
    ADD CONSTRAINT [DF_AuditLog_OccurredAt] DEFAULT (sysdatetimeoffset()) FOR [OccurredAt];

GO

GO
ALTER TABLE [dbo].[AuditLog]
    ADD CONSTRAINT [DF_AuditLog_UserId] DEFAULT (N'') FOR [UserId];

GO

GO
ALTER TABLE [dbo].[AutorizacoesPreco]
    ADD CONSTRAINT [DF_AutorizacoesPreco_CriadoEm] DEFAULT (sysdatetimeoffset()) FOR [CriadoEm];

GO

GO
ALTER TABLE [dbo].[CaixaMovimentos]
    ADD CONSTRAINT [DF_CaixaMovimentos_CreatedAt] DEFAULT (sysdatetimeoffset()) FOR [CreatedAt];

GO

GO
ALTER TABLE [dbo].[CaixaMovimentos]
    ADD CONSTRAINT [DF_CaixaMovimentos_Motivo] DEFAULT (N'') FOR [Motivo];

GO

GO
ALTER TABLE [dbo].[CaixaMovimentos]
    ADD CONSTRAINT [DF_CaixaMovimentos_OperatorName] DEFAULT (N'') FOR [OperatorName];

GO

GO
ALTER TABLE [dbo].[CaixaSessoes]
    ADD CONSTRAINT [DF_CaixaSessoes_ClosingAmount] DEFAULT ((0)) FOR [ClosingAmount];

GO

GO
ALTER TABLE [dbo].[CaixaSessoes]
    ADD CONSTRAINT [DF_CaixaSessoes_OpeningAmount] DEFAULT ((0)) FOR [OpeningAmount];

GO

GO
ALTER TABLE [dbo].[CaixaSessoes]
    ADD CONSTRAINT [DF_CaixaSessoes_ClosedById] DEFAULT (N'') FOR [ClosedById];

GO

GO
ALTER TABLE [dbo].[CaixaSessoes]
    ADD CONSTRAINT [DF_CaixaSessoes_ClosedByName] DEFAULT (N'') FOR [ClosedByName];

GO

GO
ALTER TABLE [dbo].[CaixaSessoes]
    ADD CONSTRAINT [DF_CaixaSessoes_CompanyId] DEFAULT (N'empresa-principal') FOR [CompanyId];

GO

GO
ALTER TABLE [dbo].[CaixaSessoes]
    ADD CONSTRAINT [DF_CaixaSessoes_Note] DEFAULT (N'') FOR [Note];

GO

GO
ALTER TABLE [dbo].[CaixaSessoes]
    ADD CONSTRAINT [DF_CaixaSessoes_OperatorName] DEFAULT (N'') FOR [OperatorName];

GO

GO
ALTER TABLE [dbo].[Categorias]
    ADD DEFAULT ('empresa-principal') FOR [CompanyId];

GO

GO
ALTER TABLE [dbo].[Categorias]
    ADD DEFAULT ((0)) FOR [Ordem];

GO

GO
ALTER TABLE [dbo].[Categorias]
    ADD DEFAULT ((1)) FOR [Ativa];

GO

GO
ALTER TABLE [dbo].[Clientes]
    ADD CONSTRAINT [DF_Clientes_Document] DEFAULT (N'') FOR [Document];

GO

GO
ALTER TABLE [dbo].[Clientes]
    ADD CONSTRAINT [DF_Clientes_Cep] DEFAULT (N'') FOR [Cep];

GO

GO
ALTER TABLE [dbo].[Clientes]
    ADD CONSTRAINT [DF_Clientes_CompanyId] DEFAULT (N'empresa-principal') FOR [CompanyId];

GO

GO
ALTER TABLE [dbo].[Clientes]
    ADD CONSTRAINT [DF_Clientes_StreetComplement] DEFAULT (N'') FOR [StreetComplement];

GO

GO
ALTER TABLE [dbo].[Clientes]
    ADD CONSTRAINT [DF_Clientes_SaldoDevedor] DEFAULT ((0)) FOR [SaldoDevedor];

GO

GO
ALTER TABLE [dbo].[Clientes]
    ADD CONSTRAINT [DF_Clientes_ReferencePoint] DEFAULT (N'') FOR [ReferencePoint];

GO

GO
ALTER TABLE [dbo].[Clientes]
    ADD CONSTRAINT [DF_Clientes_Neighborhood] DEFAULT (N'') FOR [Neighborhood];

GO

GO
ALTER TABLE [dbo].[Clientes]
    ADD CONSTRAINT [DF_Clientes_Address] DEFAULT (N'') FOR [Address];

GO

GO
ALTER TABLE [dbo].[Clientes]
    ADD CONSTRAINT [DF_Clientes_BirthDate] DEFAULT (N'') FOR [BirthDate];

GO

GO
ALTER TABLE [dbo].[Clientes]
    ADD CONSTRAINT [DF_Clientes_IndIeDest] DEFAULT ((9)) FOR [IndIeDest];

GO

GO
ALTER TABLE [dbo].[Clientes]
    ADD CONSTRAINT [DF_Clientes_CustomerName] DEFAULT (N'') FOR [CustomerName];

GO

GO
ALTER TABLE [dbo].[Clientes]
    ADD CONSTRAINT [DF_Clientes_Email] DEFAULT (N'') FOR [Email];

GO

GO
ALTER TABLE [dbo].[Clientes]
    ADD CONSTRAINT [DF_Clientes_Cellphone] DEFAULT (N'') FOR [Cellphone];

GO

GO
ALTER TABLE [dbo].[Clientes]
    ADD CONSTRAINT [DF_Clientes_Telephone] DEFAULT (N'') FOR [Telephone];

GO

GO
ALTER TABLE [dbo].[Clientes]
    ADD CONSTRAINT [DF_Clientes_City] DEFAULT (N'') FOR [City];

GO

GO
ALTER TABLE [dbo].[Clientes]
    ADD CONSTRAINT [DF_Clientes_State] DEFAULT (N'') FOR [State];

GO

GO
ALTER TABLE [dbo].[Clientes]
    ADD CONSTRAINT [DF_Clientes_Number] DEFAULT (N'') FOR [Number];

GO

GO
ALTER TABLE [dbo].[Clientes]
    ADD CONSTRAINT [DF_Clientes_Age] DEFAULT (N'') FOR [Age];

GO

GO
ALTER TABLE [dbo].[Clientes]
    ADD CONSTRAINT [DF_Clientes_LimiteCredito] DEFAULT ((0)) FOR [LimiteCredito];

GO

GO
ALTER TABLE [dbo].[CustomerCreditPolicies]
    ADD DEFAULT ((0)) FOR [Blocked];

GO

GO
ALTER TABLE [dbo].[DocumentosFiscais]
    ADD DEFAULT ((9)) FOR [ModalidadeFrete];

GO

GO
ALTER TABLE [dbo].[DocumentosFiscais]
    ADD CONSTRAINT [DF_DocFiscais_Status] DEFAULT ((0)) FOR [Status];

GO

GO
ALTER TABLE [dbo].[DocumentosFiscais]
    ADD CONSTRAINT [DF_DocFiscais_Modelo] DEFAULT ((65)) FOR [Modelo];

GO

GO
ALTER TABLE [dbo].[DocumentosFiscais]
    ADD CONSTRAINT [DF_DocFiscais_TpEmis] DEFAULT ((1)) FOR [TpEmis];

GO

GO
ALTER TABLE [dbo].[DocumentosFiscais]
    ADD CONSTRAINT [DF_DocFiscais_AtualizadoEm] DEFAULT (sysdatetimeoffset()) FOR [AtualizadoEm];

GO

GO
ALTER TABLE [dbo].[DocumentosFiscais]
    ADD CONSTRAINT [DF_DocFiscais_Tentativas] DEFAULT ((0)) FOR [Tentativas];

GO

GO
ALTER TABLE [dbo].[DocumentosFiscais]
    ADD CONSTRAINT [DF_DocFiscais_CriadoEm] DEFAULT (sysdatetimeoffset()) FOR [CriadoEm];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_Cep] DEFAULT (N'') FOR [Cep];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_CorporateName] DEFAULT (N'') FOR [CorporateName];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_Cnae] DEFAULT (N'') FOR [CnaeFiscal];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_EmailSmtpFromEmail] DEFAULT (N'') FOR [EmailSmtpFromEmail];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_EmailSmtpPort] DEFAULT ((587)) FOR [EmailSmtpPort];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_SerieNfce] DEFAULT ((2)) FOR [SerieNfce];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_Neighborhood] DEFAULT (N'') FOR [Neighborhood];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_Email] DEFAULT (N'') FOR [Email];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_ReqApproval] DEFAULT ((1)) FOR [RequireApprovalForNewCompanies];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_EmailSmtpEnableSsl] DEFAULT ((1)) FOR [EmailSmtpEnableSsl];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_EmailSmtpPassword] DEFAULT (N'') FOR [EmailSmtpPassword];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_CUf] DEFAULT ((33)) FOR [CodigoUfIbge];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_SacPhone] DEFAULT (N'') FOR [SacPhone];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_Mobile] DEFAULT (N'') FOR [Mobile];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_EmailSmtpEnabled] DEFAULT ((0)) FOR [EmailSmtpEnabled];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_EmailSmtpUser] DEFAULT (N'') FOR [EmailSmtpUser];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_Phone] DEFAULT (N'') FOR [Phone];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_EmailSmtpHost] DEFAULT (N'smtp-mail.outlook.com') FOR [EmailSmtpHost];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_Uf] DEFAULT (N'') FOR [Uf];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_Crt] DEFAULT ((1)) FOR [Crt];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_Amb] DEFAULT ((2)) FOR [AmbienteFiscal];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_Complement] DEFAULT (N'') FOR [Complement];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_CMun] DEFAULT (N'3304557') FOR [CodigoMunicipioIbge];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_FantasyName] DEFAULT (N'') FOR [FantasyName];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD DEFAULT ((1)) FOR [SerieNfe];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_Website] DEFAULT (N'') FOR [Website];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_StateRegistration] DEFAULT (N'') FOR [StateRegistration];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_Address] DEFAULT (N'') FOR [Address];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_EmailSmtpReplyTo] DEFAULT (N'') FOR [EmailSmtpReplyTo];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_EmailSmtpFromName] DEFAULT (N'') FOR [EmailSmtpFromName];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_CreatedAt] DEFAULT (sysdatetimeoffset()) FOR [CreatedAt];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_Cnpj] DEFAULT (N'') FOR [Cnpj];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_City] DEFAULT (N'') FOR [City];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_Number] DEFAULT (N'') FOR [Number];

GO

GO
ALTER TABLE [dbo].[Empresas]
    ADD CONSTRAINT [DF_Empresas_Status] DEFAULT (N'pendente') FOR [Status];

GO

GO
ALTER TABLE [dbo].[EmpresaTema]
    ADD CONSTRAINT [DF_EmpresaTema_UpdatedAt] DEFAULT (sysutcdatetime()) FOR [UpdatedAt];

GO

GO
ALTER TABLE [dbo].[EstoqueConfigAnalise]
    ADD DEFAULT ((90)) FOR [DiasParado];

GO

GO
ALTER TABLE [dbo].[EstoqueConfigAnalise]
    ADD DEFAULT ('faturamento') FOR [AbcBase];

GO

GO
ALTER TABLE [dbo].[EstoqueConfigAnalise]
    ADD DEFAULT ((80)) FOR [AbcLimiteA];

GO

GO
ALTER TABLE [dbo].[EstoqueConfigAnalise]
    ADD DEFAULT (sysutcdatetime()) FOR [AtualizadoEm];

GO

GO
ALTER TABLE [dbo].[EstoqueConfigAnalise]
    ADD DEFAULT ((90)) FOR [ContagemDiasB];

GO

GO
ALTER TABLE [dbo].[EstoqueConfigAnalise]
    ADD DEFAULT ((30)) FOR [ContagemDiasA];

GO

GO
ALTER TABLE [dbo].[EstoqueConfigAnalise]
    ADD DEFAULT ((50)) FOR [FsnLimiteFast];

GO

GO
ALTER TABLE [dbo].[EstoqueConfigAnalise]
    ADD DEFAULT ((7)) FOR [LeadTimePadraoDias];

GO

GO
ALTER TABLE [dbo].[EstoqueConfigAnalise]
    ADD DEFAULT ((15)) FOR [CoberturaAlvoDias];

GO

GO
ALTER TABLE [dbo].[EstoqueConfigAnalise]
    ADD DEFAULT ((30)) FOR [JanelaConsumoDias];

GO

GO
ALTER TABLE [dbo].[EstoqueConfigAnalise]
    ADD DEFAULT ((120)) FOR [DiasExcesso];

GO

GO
ALTER TABLE [dbo].[EstoqueConfigAnalise]
    ADD DEFAULT ((20)) FOR [ContagemItensDia];

GO

GO
ALTER TABLE [dbo].[EstoqueConfigAnalise]
    ADD DEFAULT ((1.65)) FOR [NivelServicoZ];

GO

GO
ALTER TABLE [dbo].[EstoqueConfigAnalise]
    ADD DEFAULT ((95)) FOR [AbcLimiteB];

GO

GO
ALTER TABLE [dbo].[EstoqueConfigAnalise]
    ADD DEFAULT ((180)) FOR [ContagemDiasC];

GO

GO
ALTER TABLE [dbo].[EstoqueMovimentacoes]
    ADD CONSTRAINT [DF_EstoqueMovimentacoes_Deposito] DEFAULT ('loja') FOR [DepositoId];

GO

GO
ALTER TABLE [dbo].[EstoqueMovimentacoes]
    ADD DEFAULT (sysutcdatetime()) FOR [CriadoEm];

GO

GO
ALTER TABLE [dbo].[EstoqueSnapshotDiario]
    ADD CONSTRAINT [DF_EstoqueSnapshot_Deposito] DEFAULT ('loja') FOR [DepositoId];

GO

GO
ALTER TABLE [dbo].[FiadoMovimentos]
    ADD CONSTRAINT [DF_FiadoMovimentos_CompanyId] DEFAULT (N'empresa-principal') FOR [CompanyId];

GO

GO
ALTER TABLE [dbo].[FiadoMovimentos]
    ADD CONSTRAINT [DF_FiadoMovimentos_CriadoEm] DEFAULT (sysdatetimeoffset()) FOR [CriadoEm];

GO

GO
ALTER TABLE [dbo].[FiadoMovimentos]
    ADD CONSTRAINT [DF_FiadoMovimentos_Operador] DEFAULT (N'Operador') FOR [OperadorNome];

GO

GO
ALTER TABLE [dbo].[FiscalIaAnalises]
    ADD DEFAULT (sysutcdatetime()) FOR [CriadaEm];

GO

GO
ALTER TABLE [dbo].[FiscalIaConfig]
    ADD DEFAULT (sysutcdatetime()) FOR [AtualizadoEm];

GO

GO
ALTER TABLE [dbo].[FiscalIaConfig]
    ADD DEFAULT ((1)) FOR [UsarJev];

GO

GO
ALTER TABLE [dbo].[FiscalIaSolicitacoes]
    ADD DEFAULT (sysutcdatetime()) FOR [CriadaEm];

GO

GO
ALTER TABLE [dbo].[FiscalIaSolicitacoes]
    ADD DEFAULT ('aguardando') FOR [Status];

GO

GO
ALTER TABLE [dbo].[FiscalSequencias]
    ADD CONSTRAINT [DF_FiscalSequencias_Atualizado] DEFAULT (sysdatetimeoffset()) FOR [AtualizadoEm];

GO

GO
ALTER TABLE [dbo].[FiscalSequencias]
    ADD CONSTRAINT [DF_FiscalSequencias_Proximo] DEFAULT ((1)) FOR [ProximoNumero];

GO

GO
ALTER TABLE [dbo].[Fornecedores]
    ADD CONSTRAINT [DF_Fornecedores_Cnpj] DEFAULT (N'') FOR [Cnpj];

GO

GO
ALTER TABLE [dbo].[Fornecedores]
    ADD CONSTRAINT [DF_Fornecedores_CompanyId] DEFAULT (N'empresa-principal') FOR [CompanyId];

GO

GO
ALTER TABLE [dbo].[Fornecedores]
    ADD CONSTRAINT [DF_Fornecedores_CompanyName] DEFAULT (N'') FOR [CompanyName];

GO

GO
ALTER TABLE [dbo].[Fornecedores]
    ADD CONSTRAINT [DF_Fornecedores_Email] DEFAULT (N'') FOR [Email];

GO

GO
ALTER TABLE [dbo].[Fornecedores]
    ADD CONSTRAINT [DF_Fornecedores_FantasyName] DEFAULT (N'') FOR [FantasyName];

GO

GO
ALTER TABLE [dbo].[Fornecedores]
    ADD CONSTRAINT [DF_Fornecedores_Neighborhood] DEFAULT (N'') FOR [Neighborhood];

GO

GO
ALTER TABLE [dbo].[Fornecedores]
    ADD CONSTRAINT [DF_Fornecedores_Number] DEFAULT (N'') FOR [Number];

GO

GO
ALTER TABLE [dbo].[Fornecedores]
    ADD CONSTRAINT [DF_Fornecedores_ReferencePoint] DEFAULT (N'') FOR [ReferencePoint];

GO

GO
ALTER TABLE [dbo].[Fornecedores]
    ADD CONSTRAINT [DF_Fornecedores_State] DEFAULT (N'') FOR [State];

GO

GO
ALTER TABLE [dbo].[Fornecedores]
    ADD CONSTRAINT [DF_Fornecedores_StreetComplement] DEFAULT (N'') FOR [StreetComplement];

GO

GO
ALTER TABLE [dbo].[Fornecedores]
    ADD CONSTRAINT [DF_Fornecedores_Address] DEFAULT (N'') FOR [Address];

GO

GO
ALTER TABLE [dbo].[Fornecedores]
    ADD CONSTRAINT [DF_Fornecedores_Telephone] DEFAULT (N'') FOR [Telephone];

GO

GO
ALTER TABLE [dbo].[Fornecedores]
    ADD CONSTRAINT [DF_Fornecedores_Cellphone] DEFAULT (N'') FOR [Cellphone];

GO

GO
ALTER TABLE [dbo].[Fornecedores]
    ADD CONSTRAINT [DF_Fornecedores_Cep] DEFAULT (N'') FOR [Cep];

GO

GO
ALTER TABLE [dbo].[Fornecedores]
    ADD CONSTRAINT [DF_Fornecedores_City] DEFAULT (N'') FOR [City];

GO

GO
ALTER TABLE [dbo].[GatewayCreditGrants]
    ADD DEFAULT ((0)) FOR [Closed];

GO

GO
ALTER TABLE [dbo].[GatewayCreditGrants]
    ADD DEFAULT ((0)) FOR [ConsumedCents];

GO

GO
ALTER TABLE [dbo].[GatewayCreditStores]
    ADD DEFAULT ('DISABLED') FOR [State];

GO

GO
ALTER TABLE [dbo].[GatewayCreditStores]
    ADD DEFAULT ('[]') FOR [InventoryJson];

GO

GO
ALTER TABLE [dbo].[GatewayTokens]
    ADD CONSTRAINT [DF_GatewayTokens_CreatedAt] DEFAULT (sysdatetimeoffset()) FOR [CreatedAt];

GO

GO
ALTER TABLE [dbo].[IntegracaoTokens]
    ADD CONSTRAINT [DF_IntegracaoTokens_CreatedAt] DEFAULT (sysdatetimeoffset()) FOR [CreatedAt];

GO

GO
ALTER TABLE [dbo].[IntegracaoTokens]
    ADD CONSTRAINT [DF_IntegracaoTokens_PermiteEscritaProdutos] DEFAULT ((0)) FOR [PermiteEscritaProdutos];

GO

GO
ALTER TABLE [dbo].[InventarioContagens]
    ADD CONSTRAINT [DF_InventarioContagens_CriadoEm] DEFAULT (sysdatetimeoffset()) FOR [CriadoEm];

GO

GO
ALTER TABLE [dbo].[InventarioEventos]
    ADD CONSTRAINT [DF_InventarioEventos_Em] DEFAULT (sysdatetimeoffset()) FOR [OcorridoEm];

GO

GO
ALTER TABLE [dbo].[InventarioItens]
    ADD CONSTRAINT [DF_InventarioItens_Recontar] DEFAULT ((0)) FOR [Recontar];

GO

GO
ALTER TABLE [dbo].[Inventarios]
    ADD CONSTRAINT [DF_Inventarios_AbertoEm] DEFAULT (sysdatetimeoffset()) FOR [AbertoEm];

GO

GO
ALTER TABLE [dbo].[Inventarios]
    ADD CONSTRAINT [DF_Inventarios_Status] DEFAULT (N'em_contagem') FOR [Status];

GO

GO
ALTER TABLE [dbo].[Inventarios]
    ADD CONSTRAINT [DF_Inventarios_Zerar] DEFAULT ((0)) FOR [ZerarNaoContados];

GO

GO
ALTER TABLE [dbo].[LojaGatewayConfig]
    ADD CONSTRAINT [DF_LojaGatewayConfig_Enabled] DEFAULT ((1)) FOR [Enabled];

GO

GO
ALTER TABLE [dbo].[LojaGatewayConfig]
    ADD CONSTRAINT [DF_LojaGatewayConfig_UpdatedAt] DEFAULT (sysutcdatetime()) FOR [UpdatedAt];

GO

GO
ALTER TABLE [dbo].[LoteConfig]
    ADD CONSTRAINT [DF_LoteConfig_Modo] DEFAULT (N'sombra') FOR [Modo];

GO

GO
ALTER TABLE [dbo].[LoteConfig]
    ADD CONSTRAINT [DF_LoteConfig_AtualizadoEm] DEFAULT (sysdatetimeoffset()) FOR [AtualizadoEm];

GO

GO
ALTER TABLE [dbo].[LoteConsumos]
    ADD CONSTRAINT [DF_LoteConsumos_CriadoEm] DEFAULT (sysdatetimeoffset()) FOR [CriadoEm];

GO

GO
ALTER TABLE [dbo].[LoteConsumos]
    ADD CONSTRAINT [DF_LoteConsumos_Estornado] DEFAULT ((0)) FOR [Estornado];

GO

GO
ALTER TABLE [dbo].[MapeamentoProdutoFornecedor]
    ADD CONSTRAINT [DF_MapeamentoProdFornec_CreatedAt] DEFAULT (sysutcdatetime()) FOR [CreatedAt];

GO

GO
ALTER TABLE [dbo].[MapeamentoProdutoFornecedor]
    ADD CONSTRAINT [DF_MapeamentoProdFornec_UpdatedAt] DEFAULT (sysutcdatetime()) FOR [UpdatedAt];

GO

GO
ALTER TABLE [dbo].[ModuloMercadoRegistros]
    ADD CONSTRAINT [DF_ModuloMercadoRegistros_Amount] DEFAULT (N'') FOR [Amount];

GO

GO
ALTER TABLE [dbo].[ModuloMercadoRegistros]
    ADD CONSTRAINT [DF_ModuloMercadoRegistros_Meta] DEFAULT (N'') FOR [Meta];

GO

GO
ALTER TABLE [dbo].[ModuloMercadoRegistros]
    ADD CONSTRAINT [DF_ModuloMercadoRegistros_Status] DEFAULT (N'') FOR [Status];

GO

GO
ALTER TABLE [dbo].[ModuloMercadoRegistros]
    ADD CONSTRAINT [DF_ModuloMercadoRegistros_CompanyId] DEFAULT (N'empresa-principal') FOR [CompanyId];

GO

GO
ALTER TABLE [dbo].[ModuloMercadoRegistros]
    ADD CONSTRAINT [DF_ModuloMercadoRegistros_Description] DEFAULT (N'') FOR [Description];

GO

GO
ALTER TABLE [dbo].[NotasEntradaArquivo]
    ADD DEFAULT (sysutcdatetime()) FOR [CriadaEm];

GO

GO
ALTER TABLE [dbo].[NotasEntradaArquivo]
    ADD DEFAULT ((0)) FOR [Estornada];

GO

GO
ALTER TABLE [dbo].[OrdemCompra]
    ADD CONSTRAINT [DF_OC_Desconto] DEFAULT ((0)) FOR [ValorDesconto];

GO

GO
ALTER TABLE [dbo].[OrdemCompra]
    ADD CONSTRAINT [DF_OC_Frete] DEFAULT ((0)) FOR [ValorFrete];

GO

GO
ALTER TABLE [dbo].[OrdemCompra]
    ADD CONSTRAINT [DF_OC_CreatedAt] DEFAULT (sysdatetimeoffset()) FOR [CreatedAt];

GO

GO
ALTER TABLE [dbo].[OrdemCompra]
    ADD CONSTRAINT [DF_OC_Total] DEFAULT ((0)) FOR [TotalEstimado];

GO

GO
ALTER TABLE [dbo].[OrdemCompra]
    ADD CONSTRAINT [DF_OC_Status] DEFAULT ((0)) FOR [Status];

GO

GO
ALTER TABLE [dbo].[OrdemCompraItens]
    ADD CONSTRAINT [DF_OCItem_QtyRcv] DEFAULT ((0)) FOR [QuantityReceived];

GO

GO
ALTER TABLE [dbo].[PedidoItens]
    ADD CONSTRAINT [DF_PedidoItens_ItemTotal] DEFAULT ((0)) FOR [ItemTotal];

GO

GO
ALTER TABLE [dbo].[PedidoItens]
    ADD CONSTRAINT [DF_PedidoItens_Quantity] DEFAULT ((0)) FOR [Quantity];

GO

GO
ALTER TABLE [dbo].[PedidoItens]
    ADD CONSTRAINT [DF_PedidoItens_UnitPrice] DEFAULT ((0)) FOR [UnitPrice];

GO

GO
ALTER TABLE [dbo].[Pedidos]
    ADD CONSTRAINT [DF_Pedidos_CustomerCpf] DEFAULT (N'-') FOR [CustomerCpf];

GO

GO
ALTER TABLE [dbo].[Pedidos]
    ADD CONSTRAINT [DF_Pedidos_Status] DEFAULT ((0)) FOR [Status];

GO

GO
ALTER TABLE [dbo].[Pedidos]
    ADD CONSTRAINT [DF_Pedidos_CreatedAt] DEFAULT (sysdatetimeoffset()) FOR [CreatedAt];

GO

GO
ALTER TABLE [dbo].[Pedidos]
    ADD CONSTRAINT [DF_Pedidos_SellerName] DEFAULT (N'') FOR [SellerName];

GO

GO
ALTER TABLE [dbo].[Pedidos]
    ADD CONSTRAINT [DF_Pedidos_Note] DEFAULT (N'') FOR [Note];

GO

GO
ALTER TABLE [dbo].[Pedidos]
    ADD CONSTRAINT [DF_Pedidos_CompanyId] DEFAULT (N'empresa-principal') FOR [CompanyId];

GO

GO
ALTER TABLE [dbo].[Pedidos]
    ADD CONSTRAINT [DF_Pedidos_CustomerName] DEFAULT (N'Consumidor') FOR [CustomerName];

GO

GO
ALTER TABLE [dbo].[ProcessedEvents]
    ADD CONSTRAINT [DF_ProcessedEvents_ProcessedAt] DEFAULT (sysdatetimeoffset()) FOR [ProcessedAt];

GO

GO
ALTER TABLE [dbo].[ProdutoClassificacao]
    ADD DEFAULT (sysutcdatetime()) FOR [CalculadoEm];

GO

GO
ALTER TABLE [dbo].[ProdutoLotes]
    ADD CONSTRAINT [DF_ProdutoLotes_Origem] DEFAULT (N'manual') FOR [Origem];

GO

GO
ALTER TABLE [dbo].[ProdutoLotes]
    ADD CONSTRAINT [DF_ProdutoLotes_CriadoEm] DEFAULT (sysdatetimeoffset()) FOR [CriadoEm];

GO

GO
ALTER TABLE [dbo].[ProdutoLotes]
    ADD CONSTRAINT [DF_ProdutoLotes_ValidadePadrao] DEFAULT ((0)) FOR [ValidadePadrao];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_PesoBrutoKg] DEFAULT ((0)) FOR [PesoBrutoKg];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_ProductSupplier] DEFAULT (N'') FOR [ProductSupplier];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_EstoqueMinimo] DEFAULT ((0)) FOR [EstoqueMinimo];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_QtdEmbalagem] DEFAULT ((1)) FOR [QtdEmbalagem];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_Ncm] DEFAULT ('00000000') FOR [Ncm];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_CustoMedio] DEFAULT ((0)) FOR [CustoMedio];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_UnCom] DEFAULT (N'UN') FOR [UnidadeComercial];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_MarkupCadastrado] DEFAULT ((0)) FOR [MarkupCadastrado];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_DiasAlertaValidade] DEFAULT ((15)) FOR [DiasAlertaValidade];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_Lucro] DEFAULT ((0)) FOR [Lucro];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_DescontoMaxPerc] DEFAULT ((0)) FOR [DescontoMaximoPercentual];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_Gtin] DEFAULT (N'SEM GTIN') FOR [Gtin];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_ProductImageUrl] DEFAULT (N'') FOR [ProductImageUrl];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_CompanyId] DEFAULT (N'empresa-principal') FOR [CompanyId];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_AlturaCm] DEFAULT ((0)) FOR [AlturaCm];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_CstCofins] DEFAULT (N'07') FOR [CstCofins];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_ProductQnt] DEFAULT ((0)) FOR [ProductQnt];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_ProductDescription] DEFAULT (N'') FOR [ProductDescription];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_Origem] DEFAULT ((0)) FOR [OrigemMercadoria];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_ProductUnitPrice] DEFAULT ((0)) FOR [ProductUnitPrice];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_ControlaValidade] DEFAULT ((0)) FOR [ControlaValidade];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_ComissaoPerc] DEFAULT ((0)) FOR [ComissaoPercentual];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_TotalPriceOnProduct] DEFAULT ((0)) FOR [TotalPriceOnProduct];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_EstoqueMaximo] DEFAULT ((0)) FOR [EstoqueMaximo];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_MarkupPraticado] DEFAULT ((0)) FOR [MarkupPraticado];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_AliqIcms] DEFAULT ((0)) FOR [AliquotaIcms];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_CustoComImposto] DEFAULT ((0)) FOR [CustoComImposto];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_UnidadeCompra] DEFAULT (N'UN') FOR [UnidadeCompra];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_ComprimentoCm] DEFAULT ((0)) FOR [ComprimentoCm];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_Cfop] DEFAULT ('5102') FOR [Cfop];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_UnTrib] DEFAULT (N'UN') FOR [UnidadeTributavel];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_LarguraCm] DEFAULT ((0)) FOR [LarguraCm];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_CstPis] DEFAULT (N'07') FOR [CstPis];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_ProductName] DEFAULT (N'') FOR [ProductName];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_FatorConversao] DEFAULT ((1)) FOR [FatorConversao];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_ProductSalePrice] DEFAULT ((0)) FOR [ProductSalePrice];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_CustoSemImposto] DEFAULT ((0)) FOR [CustoSemImposto];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_PesoLiquidoKg] DEFAULT ((0)) FOR [PesoLiquidoKg];

GO

GO
ALTER TABLE [dbo].[Produtos]
    ADD CONSTRAINT [DF_Produtos_ProductImageName] DEFAULT (N'') FOR [ProductImageName];

GO

GO
ALTER TABLE [dbo].[Promocoes]
    ADD CONSTRAINT [DF_Promocoes_CriadoEm] DEFAULT (sysdatetimeoffset()) FOR [CriadoEm];

GO

GO
ALTER TABLE [dbo].[Promocoes]
    ADD CONSTRAINT [DF_Promocoes_Ativa] DEFAULT ((1)) FOR [Ativa];

GO

GO
ALTER TABLE [dbo].[Promocoes]
    ADD CONSTRAINT [DF_Promocoes_CriadoPor] DEFAULT (N'') FOR [CriadoPor];

GO

GO
ALTER TABLE [dbo].[Promocoes]
    ADD CONSTRAINT [DF_Promocoes_CompanyId] DEFAULT (N'empresa-principal') FOR [CompanyId];

GO

GO
ALTER TABLE [dbo].[RegrasEmissaoNfce]
    ADD CONSTRAINT [DF_RegrasEmissaoNfce_Habilitado] DEFAULT ((1)) FOR [Habilitado];

GO

GO
ALTER TABLE [dbo].[RegrasEmissaoNfce]
    ADD CONSTRAINT [DF_RegrasEmissaoNfce_Contador] DEFAULT ((0)) FOR [ContadorVendas];

GO

GO
ALTER TABLE [dbo].[RegrasEmissaoNfce]
    ADD CONSTRAINT [DF_RegrasEmissaoNfce_Cpf] DEFAULT ((1)) FOR [EmitirSempreComCpf];

GO

GO
ALTER TABLE [dbo].[RegrasEmissaoNfce]
    ADD CONSTRAINT [DF_RegrasEmissaoNfce_UpdatedAt] DEFAULT (sysutcdatetime()) FOR [UpdatedAt];

GO

GO
ALTER TABLE [dbo].[RegrasEmissaoNfce]
    ADD CONSTRAINT [DF_RegrasEmissaoNfce_Intervalo] DEFAULT ((1)) FOR [IntervaloNotas];

GO

GO
ALTER TABLE [dbo].[RegrasEmissaoNfce]
    ADD CONSTRAINT [DF_RegrasEmissaoNfce_SemanticaV2] DEFAULT ((1)) FOR [SemanticaV2];

GO

GO
ALTER TABLE [dbo].[RegrasEmissaoNfce]
    ADD CONSTRAINT [DF_RegrasEmissaoNfce_Formas] DEFAULT ('dinheiro,credito,debito,pix,fiado') FOR [FormasPagamentoHabilitadas];

GO

GO
ALTER TABLE [dbo].[Sessoes]
    ADD CONSTRAINT [DF_Sessoes_Location] DEFAULT (N'') FOR [Location];

GO

GO
ALTER TABLE [dbo].[Sessoes]
    ADD CONSTRAINT [DF_Sessoes_Platform] DEFAULT (N'desktop') FOR [Platform];

GO

GO
ALTER TABLE [dbo].[Sessoes]
    ADD CONSTRAINT [DF_Sessoes_Ip] DEFAULT (N'') FOR [Ip];

GO

GO
ALTER TABLE [dbo].[Sessoes]
    ADD CONSTRAINT [DF_Sessoes_LastActive] DEFAULT (N'Agora mesmo') FOR [LastActive];

GO

GO
ALTER TABLE [dbo].[Sessoes]
    ADD CONSTRAINT [DF_Sessoes_CreatedAt] DEFAULT (sysdatetimeoffset()) FOR [CreatedAt];

GO

GO
ALTER TABLE [dbo].[Sessoes]
    ADD CONSTRAINT [DF_Sessoes_Device] DEFAULT (N'') FOR [Device];

GO

GO
ALTER TABLE [dbo].[TreinamentoSecoes]
    ADD CONSTRAINT [DF_TreinamentoSecoes_CriadoEm] DEFAULT (sysdatetimeoffset()) FOR [CriadoEm];

GO

GO
ALTER TABLE [dbo].[TreinamentoSecoes]
    ADD CONSTRAINT [DF_TreinamentoSecoes_Ordem] DEFAULT ((0)) FOR [Ordem];

GO

GO
ALTER TABLE [dbo].[TreinamentoVideos]
    ADD CONSTRAINT [DF_TreinamentoVideos_CriadoEm] DEFAULT (sysdatetimeoffset()) FOR [CriadoEm];

GO

GO
ALTER TABLE [dbo].[TreinamentoVideos]
    ADD CONSTRAINT [DF_TreinamentoVideos_Ordem] DEFAULT ((0)) FOR [Ordem];

GO

GO
ALTER TABLE [dbo].[Usuarios]
    ADD CONSTRAINT [DF_Usuarios_CreatedAt] DEFAULT (CONVERT([nvarchar](30),sysutcdatetime(),(23))) FOR [CreatedAt];

GO

GO
ALTER TABLE [dbo].[Usuarios]
    ADD CONSTRAINT [DF_Usuarios_MustChangePassword] DEFAULT ((1)) FOR [MustChangePassword];

GO

GO
ALTER TABLE [dbo].[Usuarios]
    ADD CONSTRAINT [DF_Usuarios_PerfisAdicionais] DEFAULT (N'') FOR [PerfisAdicionais];

GO

GO
ALTER TABLE [dbo].[Usuarios]
    ADD CONSTRAINT [DF_Usuarios_Role] DEFAULT (N'atendente') FOR [Role];

GO

GO
ALTER TABLE [dbo].[Usuarios]
    ADD CONSTRAINT [DF_Usuarios_Phone] DEFAULT (N'') FOR [Phone];

GO

GO
ALTER TABLE [dbo].[Usuarios]
    ADD CONSTRAINT [DF_Usuarios_FailedLoginAttempts] DEFAULT ((0)) FOR [FailedLoginAttempts];

GO

GO
ALTER TABLE [dbo].[Usuarios]
    ADD CONSTRAINT [DF_Usuarios_Status] DEFAULT (N'ativo') FOR [Status];

GO

GO
ALTER TABLE [dbo].[Usuarios]
    ADD CONSTRAINT [DF_Usuarios_CompanyId] DEFAULT (N'empresa-principal') FOR [CompanyId];

GO

GO
ALTER TABLE [dbo].[Usuarios]
    ADD CONSTRAINT [DF_Usuarios_LastLoginAt] DEFAULT (N'-') FOR [LastLoginAt];

GO

GO
ALTER TABLE [dbo].[VendaCancelamentos]
    ADD CONSTRAINT [DF_VendaCancelamentos_OcorridoEm] DEFAULT (sysdatetimeoffset()) FOR [OcorridoEm];

GO

GO
ALTER TABLE [dbo].[VendaItens]
    ADD CONSTRAINT [DF_VendaItens_UnitPrice] DEFAULT ((0)) FOR [UnitPrice];

GO

GO
ALTER TABLE [dbo].[VendaItens]
    ADD CONSTRAINT [DF_VendaItens_Desconto] DEFAULT ((0)) FOR [Desconto];

GO

GO
ALTER TABLE [dbo].[VendaItens]
    ADD CONSTRAINT [DF_VendaItens_ItemTotal] DEFAULT ((0)) FOR [ItemTotal];

GO

GO
ALTER TABLE [dbo].[VendaItens]
    ADD CONSTRAINT [DF_VendaItens_Quantity] DEFAULT ((0)) FOR [Quantity];

GO

GO
ALTER TABLE [dbo].[VendaPagamentos]
    ADD CONSTRAINT [DF_VendaPagamentos_CashGiven] DEFAULT ((0)) FOR [CashGiven];

GO

GO
ALTER TABLE [dbo].[VendaPagamentos]
    ADD CONSTRAINT [DF_VendaPagamentos_ChangeAmount] DEFAULT ((0)) FOR [ChangeAmount];

GO

GO
ALTER TABLE [dbo].[VendaPagamentos]
    ADD CONSTRAINT [DF_VendaPagamentos_CreatedAt] DEFAULT (sysdatetimeoffset()) FOR [CreatedAt];

GO

GO
ALTER TABLE [dbo].[Vendas]
    ADD CONSTRAINT [DF_Vendas_OperatorName] DEFAULT (N'Operador') FOR [OperatorName];

GO

GO
ALTER TABLE [dbo].[Vendas]
    ADD CONSTRAINT [DF_Vendas_CustomerName] DEFAULT (N'Consumidor') FOR [CustomerName];

GO

GO
ALTER TABLE [dbo].[Vendas]
    ADD CONSTRAINT [DF_Vendas_SaleDate] DEFAULT (sysdatetimeoffset()) FOR [SaleDate];

GO

GO
ALTER TABLE [dbo].[Vendas]
    ADD CONSTRAINT [DF_Vendas_PaymentType] DEFAULT (N'-') FOR [PaymentType];

GO

GO
ALTER TABLE [dbo].[Vendas]
    ADD CONSTRAINT [DF_Vendas_CompanyId] DEFAULT (N'empresa-principal') FOR [CompanyId];

GO

GO
ALTER TABLE [dbo].[Vendas]
    ADD CONSTRAINT [DF_Vendas_TotalAmount] DEFAULT ((0)) FOR [TotalAmount];

GO

GO
ALTER TABLE [dbo].[Vendas]
    ADD CONSTRAINT [DF_Vendas_Status] DEFAULT (N'finalizada') FOR [Status];

GO

GO
ALTER TABLE [dbo].[Vendas]
    ADD CONSTRAINT [DF_Vendas_CustomerCpf] DEFAULT (N'-') FOR [CustomerCpf];

GO

GO
ALTER TABLE [dbo].[CaixaMovimentos] WITH NOCHECK
    ADD CONSTRAINT [FK_CaixaMovimentos_CaixaSessoes] FOREIGN KEY ([CaixaSessaoId]) REFERENCES [dbo].[CaixaSessoes] ([Id]);

GO

GO
ALTER TABLE [dbo].[CaixaSessoes] WITH NOCHECK
    ADD CONSTRAINT [FK_CaixaSessoes_Usuarios] FOREIGN KEY ([OperatorId]) REFERENCES [dbo].[Usuarios] ([Id]);

GO

GO
ALTER TABLE [dbo].[Categorias] WITH NOCHECK
    ADD CONSTRAINT [FK_Categorias_Pai] FOREIGN KEY ([CategoriaPaiId]) REFERENCES [dbo].[Categorias] ([Id]);

GO

GO
ALTER TABLE [dbo].[DocumentosFiscais] WITH NOCHECK
    ADD CONSTRAINT [FK_DocFiscais_Vendas] FOREIGN KEY ([VendaId]) REFERENCES [dbo].[Vendas] ([Id]);

GO

GO
ALTER TABLE [dbo].[EstoqueMovimentacoes] WITH NOCHECK
    ADD CONSTRAINT [FK_EstoqueMovimentacoes_Produto] FOREIGN KEY ([ProdutoId]) REFERENCES [dbo].[Produtos] ([Id]);

GO

GO
ALTER TABLE [dbo].[FiadoMovimentos] WITH NOCHECK
    ADD CONSTRAINT [FK_FiadoMovimentos_Clientes] FOREIGN KEY ([ClienteId]) REFERENCES [dbo].[Clientes] ([Id]) ON DELETE CASCADE;

GO

GO
ALTER TABLE [dbo].[GatewayCreditConsumptions] WITH NOCHECK
    ADD CONSTRAINT [FK_GatewayCreditGrant] FOREIGN KEY ([CompanyId], [GrantId]) REFERENCES [dbo].[GatewayCreditGrants] ([CompanyId], [GrantId]);

GO

GO
ALTER TABLE [dbo].[InventarioContagens] WITH NOCHECK
    ADD CONSTRAINT [FK_InventarioContagens_Inventario] FOREIGN KEY ([InventarioId]) REFERENCES [dbo].[Inventarios] ([Id]);

GO

GO
ALTER TABLE [dbo].[InventarioItens] WITH NOCHECK
    ADD CONSTRAINT [FK_InventarioItens_Inventario] FOREIGN KEY ([InventarioId]) REFERENCES [dbo].[Inventarios] ([Id]);

GO

GO
ALTER TABLE [dbo].[ModuloMercadoRegistros] WITH NOCHECK
    ADD CONSTRAINT [FK_ModuloMercadoRegistros_ModulosMercado] FOREIGN KEY ([ModuleId]) REFERENCES [dbo].[ModulosMercado] ([Id]) ON DELETE CASCADE;

GO

GO
ALTER TABLE [dbo].[OrdemCompra] WITH NOCHECK
    ADD CONSTRAINT [FK_OC_Supplier] FOREIGN KEY ([SupplierId]) REFERENCES [dbo].[Fornecedores] ([Id]) ON DELETE SET NULL;

GO

GO
ALTER TABLE [dbo].[OrdemCompra] WITH NOCHECK
    ADD CONSTRAINT [FK_OC_Company] FOREIGN KEY ([CompanyId]) REFERENCES [dbo].[Empresas] ([Id]);

GO

GO
ALTER TABLE [dbo].[OrdemCompraItens] WITH NOCHECK
    ADD CONSTRAINT [FK_OCItens_OC] FOREIGN KEY ([OrdemCompraId]) REFERENCES [dbo].[OrdemCompra] ([Id]) ON DELETE CASCADE;

GO

GO
ALTER TABLE [dbo].[PasswordResetTokens] WITH NOCHECK
    ADD CONSTRAINT [FK_PasswordResetTokens_Usuarios] FOREIGN KEY ([UserId]) REFERENCES [dbo].[Usuarios] ([Id]) ON DELETE CASCADE;

GO

GO
ALTER TABLE [dbo].[PedidoItens] WITH NOCHECK
    ADD CONSTRAINT [FK_PedidoItens_Pedidos] FOREIGN KEY ([PedidoId]) REFERENCES [dbo].[Pedidos] ([Id]) ON DELETE CASCADE;

GO

GO
ALTER TABLE [dbo].[Pedidos] WITH NOCHECK
    ADD CONSTRAINT [FK_Pedidos_Vendas] FOREIGN KEY ([VendaId]) REFERENCES [dbo].[Vendas] ([Id]);

GO

GO
ALTER TABLE [dbo].[Produtos] WITH NOCHECK
    ADD CONSTRAINT [FK_Produtos_Categoria] FOREIGN KEY ([CategoriaId]) REFERENCES [dbo].[Categorias] ([Id]) ON DELETE SET NULL;

GO

GO
ALTER TABLE [dbo].[Produtos] WITH NOCHECK
    ADD CONSTRAINT [FK_Produtos_Fornecedores] FOREIGN KEY ([SupplierId]) REFERENCES [dbo].[Fornecedores] ([Id]) ON DELETE SET NULL;

GO

GO
ALTER TABLE [dbo].[PromocaoProdutos] WITH NOCHECK
    ADD CONSTRAINT [FK_PromocaoProdutos_Produtos] FOREIGN KEY ([ProdutoId]) REFERENCES [dbo].[Produtos] ([Id]) ON DELETE CASCADE;

GO

GO
ALTER TABLE [dbo].[PromocaoProdutos] WITH NOCHECK
    ADD CONSTRAINT [FK_PromocaoProdutos_Promocoes] FOREIGN KEY ([PromocaoId]) REFERENCES [dbo].[Promocoes] ([Id]) ON DELETE CASCADE;

GO

GO
ALTER TABLE [dbo].[Promocoes] WITH NOCHECK
    ADD CONSTRAINT [FK_Promocoes_Categorias] FOREIGN KEY ([CategoriaId]) REFERENCES [dbo].[Categorias] ([Id]) ON DELETE SET NULL;

GO

GO
ALTER TABLE [dbo].[Sessoes] WITH NOCHECK
    ADD CONSTRAINT [FK_Sessoes_Usuarios] FOREIGN KEY ([UserId]) REFERENCES [dbo].[Usuarios] ([Id]) ON DELETE CASCADE;

GO

GO
ALTER TABLE [dbo].[TreinamentoVideos] WITH NOCHECK
    ADD CONSTRAINT [FK_TreinamentoVideos_Secao] FOREIGN KEY ([SecaoId]) REFERENCES [dbo].[TreinamentoSecoes] ([Id]) ON DELETE CASCADE;

GO

GO
ALTER TABLE [dbo].[VendaItens] WITH NOCHECK
    ADD CONSTRAINT [FK_VendaItens_Vendas] FOREIGN KEY ([VendaId]) REFERENCES [dbo].[Vendas] ([Id]) ON DELETE CASCADE;

GO

GO
ALTER TABLE [dbo].[VendaPagamentos] WITH NOCHECK
    ADD CONSTRAINT [FK_VendaPagamentos_Vendas] FOREIGN KEY ([VendaId]) REFERENCES [dbo].[Vendas] ([Id]) ON DELETE CASCADE;

GO

GO
ALTER TABLE [dbo].[Vendas] WITH NOCHECK
    ADD CONSTRAINT [FK_Vendas_CaixaSessoes] FOREIGN KEY ([CaixaSessaoId]) REFERENCES [dbo].[CaixaSessoes] ([Id]);

GO

GO
ALTER TABLE [dbo].[FiscalIaSolicitacoes] WITH NOCHECK
    ADD CONSTRAINT [CK_FiscalIaSolicitacoes_Status] CHECK ([Status]='falha' OR [Status]='concluida' OR [Status]='analisando' OR [Status]='aguardando');

GO

GO
ALTER TABLE [dbo].[GatewayCreditBudgets] WITH NOCHECK
    ADD CHECK ([MaximumCents]>(0));

GO

GO
ALTER TABLE [dbo].[GatewayCreditGrants] WITH NOCHECK
    ADD CONSTRAINT [CK_GatewayCreditAmount] CHECK ([ConsumedCents]>=(0) AND [ConsumedCents]<=[AmountCents]);

GO

GO
ALTER TABLE [dbo].[GatewayCreditGrants] WITH NOCHECK
    ADD CHECK ([AmountCents]>(0));

GO

GO
ALTER TABLE [dbo].[NotasEntradaArquivo] WITH NOCHECK
    ADD CONSTRAINT [CK_NotasEntradaArquivo_Json] CHECK (isjson([EntradaJson])=(1));

GO

GO
/* ------------------------------------------------------------------------- */
/* 1. Função de normalização                                                  */
/*                                                                            */
/* Replica exatamente o HorusMoneyFormat.ParseDecimal (C#), incluindo o       */
/* espaço não separável (U+00A0) que o ToString("C", pt-BR) emite no ICU.     */
/* ------------------------------------------------------------------------- */
CREATE   FUNCTION dbo.fn_HorusParseDecimal (@valor NVARCHAR(60))
RETURNS DECIMAL(18, 4)
AS
BEGIN
    DECLARE @n NVARCHAR(60) = LTRIM(RTRIM(ISNULL(@valor, N'0')));

    SET @n = REPLACE(@n, NCHAR(160), N'');   -- espaço não separável
    SET @n = REPLACE(@n, NCHAR(8239), N''); -- espaço estreito não separável
    SET @n = REPLACE(@n, N'R$', N'');
    SET @n = REPLACE(@n, N' ', N'');
    SET @n = REPLACE(@n, N'.', N'');         -- separador de milhar pt-BR
    SET @n = REPLACE(@n, N',', N'.');        -- separador decimal pt-BR

    IF @n = N'' OR @n = N'-' SET @n = N'0';

    RETURN TRY_CONVERT(DECIMAL(18, 4), @n);  -- NULL quando não parseável
END;
GO

GO

GO

GO

GO
ALTER TABLE [dbo].[CaixaMovimentos] WITH CHECK CHECK CONSTRAINT [FK_CaixaMovimentos_CaixaSessoes];

ALTER TABLE [dbo].[CaixaSessoes] WITH CHECK CHECK CONSTRAINT [FK_CaixaSessoes_Usuarios];

ALTER TABLE [dbo].[Categorias] WITH CHECK CHECK CONSTRAINT [FK_Categorias_Pai];

ALTER TABLE [dbo].[DocumentosFiscais] WITH CHECK CHECK CONSTRAINT [FK_DocFiscais_Vendas];

ALTER TABLE [dbo].[EstoqueMovimentacoes] WITH CHECK CHECK CONSTRAINT [FK_EstoqueMovimentacoes_Produto];

ALTER TABLE [dbo].[FiadoMovimentos] WITH CHECK CHECK CONSTRAINT [FK_FiadoMovimentos_Clientes];

ALTER TABLE [dbo].[GatewayCreditConsumptions] WITH CHECK CHECK CONSTRAINT [FK_GatewayCreditGrant];

ALTER TABLE [dbo].[InventarioContagens] WITH CHECK CHECK CONSTRAINT [FK_InventarioContagens_Inventario];

ALTER TABLE [dbo].[InventarioItens] WITH CHECK CHECK CONSTRAINT [FK_InventarioItens_Inventario];

ALTER TABLE [dbo].[ModuloMercadoRegistros] WITH CHECK CHECK CONSTRAINT [FK_ModuloMercadoRegistros_ModulosMercado];

ALTER TABLE [dbo].[OrdemCompra] WITH CHECK CHECK CONSTRAINT [FK_OC_Supplier];

ALTER TABLE [dbo].[OrdemCompra] WITH CHECK CHECK CONSTRAINT [FK_OC_Company];

ALTER TABLE [dbo].[OrdemCompraItens] WITH CHECK CHECK CONSTRAINT [FK_OCItens_OC];

ALTER TABLE [dbo].[PasswordResetTokens] WITH CHECK CHECK CONSTRAINT [FK_PasswordResetTokens_Usuarios];

ALTER TABLE [dbo].[PedidoItens] WITH CHECK CHECK CONSTRAINT [FK_PedidoItens_Pedidos];

ALTER TABLE [dbo].[Pedidos] WITH CHECK CHECK CONSTRAINT [FK_Pedidos_Vendas];

ALTER TABLE [dbo].[Produtos] WITH CHECK CHECK CONSTRAINT [FK_Produtos_Categoria];

ALTER TABLE [dbo].[Produtos] WITH CHECK CHECK CONSTRAINT [FK_Produtos_Fornecedores];

ALTER TABLE [dbo].[PromocaoProdutos] WITH CHECK CHECK CONSTRAINT [FK_PromocaoProdutos_Produtos];

ALTER TABLE [dbo].[PromocaoProdutos] WITH CHECK CHECK CONSTRAINT [FK_PromocaoProdutos_Promocoes];

ALTER TABLE [dbo].[Promocoes] WITH CHECK CHECK CONSTRAINT [FK_Promocoes_Categorias];

ALTER TABLE [dbo].[Sessoes] WITH CHECK CHECK CONSTRAINT [FK_Sessoes_Usuarios];

ALTER TABLE [dbo].[TreinamentoVideos] WITH CHECK CHECK CONSTRAINT [FK_TreinamentoVideos_Secao];

ALTER TABLE [dbo].[VendaItens] WITH CHECK CHECK CONSTRAINT [FK_VendaItens_Vendas];

ALTER TABLE [dbo].[VendaPagamentos] WITH CHECK CHECK CONSTRAINT [FK_VendaPagamentos_Vendas];

ALTER TABLE [dbo].[Vendas] WITH CHECK CHECK CONSTRAINT [FK_Vendas_CaixaSessoes];

ALTER TABLE [dbo].[FiscalIaSolicitacoes] WITH CHECK CHECK CONSTRAINT [CK_FiscalIaSolicitacoes_Status];

ALTER TABLE [dbo].[NotasEntradaArquivo] WITH CHECK CHECK CONSTRAINT [CK_NotasEntradaArquivo_Json];

GO
GO
