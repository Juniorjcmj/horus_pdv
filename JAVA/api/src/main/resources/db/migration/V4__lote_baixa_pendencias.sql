-- Fila de nova tentativa da baixa/estorno de lotes de uma venda (D13 da change api-java-vendas).
-- Tabela SOMENTE da API Java: a API .NET nao a conhece e nenhuma tabela existente muda.
-- Uma linha por (empresa, venda, tipo). Estado: pendente | concluida | revisao (passou do limite de tentativas).
IF OBJECT_ID(N'dbo.LoteBaixaPendencias', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.LoteBaixaPendencias (
        Id                 NVARCHAR(40)      NOT NULL,
        CompanyId          NVARCHAR(40)      NOT NULL,
        VendaId            NVARCHAR(60)      NOT NULL,
        Tipo               NVARCHAR(10)      NOT NULL,   -- consumo | estorno
        ItensJson          NVARCHAR(MAX)     NULL,       -- consumo: [{"c":"codigo","q":"1.5"}]; estorno: NULL
        Estado             NVARCHAR(10)      NOT NULL CONSTRAINT DF_LoteBaixaPend_Estado DEFAULT N'pendente',
        Tentativas         INT               NOT NULL CONSTRAINT DF_LoteBaixaPend_Tent DEFAULT 0,
        ProximaTentativaEm DATETIMEOFFSET(7) NOT NULL,
        UltimoErro         NVARCHAR(500)     NULL,
        CriadoEm           DATETIMEOFFSET(7) NOT NULL CONSTRAINT DF_LoteBaixaPend_Criado DEFAULT SYSDATETIMEOFFSET(),
        AtualizadoEm       DATETIMEOFFSET(7) NOT NULL CONSTRAINT DF_LoteBaixaPend_Atual DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT PK_LoteBaixaPendencias PRIMARY KEY CLUSTERED (Id),
        CONSTRAINT UQ_LoteBaixaPendencias_Venda UNIQUE (CompanyId, VendaId, Tipo)
    );
    CREATE NONCLUSTERED INDEX IX_LoteBaixaPendencias_Fila
        ON dbo.LoteBaixaPendencias (Estado, ProximaTentativaEm);
END
