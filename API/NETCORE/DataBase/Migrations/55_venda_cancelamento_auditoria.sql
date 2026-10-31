/*
 * Arquivo: API/NETCORE/DataBase/Migrations/55_venda_cancelamento_auditoria.sql
 * Objetivo: trilha de auditoria completa dos cancelamentos de venda. Uma linha por TENTATIVA (autorizada ou
 *           negada): quem pediu, qual gerente autorizou (ou foi informado), motivo, terminal, IP, caixa do turno
 *           e uma fotografia da venda no momento (itens, pagamentos, cliente, notas fiscais) em JSON.
 *           Append-only: nenhuma rotina do sistema atualiza ou apaga estas linhas.
 *
 * Idempotente: pode ser executado no boot repetidamente sem erro.
 */

SET NOCOUNT ON;
GO

IF OBJECT_ID(N'VendaCancelamentos', N'U') IS NULL
BEGIN
    CREATE TABLE VendaCancelamentos (
        Id                  NVARCHAR(40)    NOT NULL,
        CompanyId           NVARCHAR(40)    NOT NULL,
        OcorridoEm          DATETIMEOFFSET  NOT NULL CONSTRAINT DF_VendaCancelamentos_OcorridoEm DEFAULT SYSDATETIMEOFFSET(),
        Resultado           NVARCHAR(20)    NOT NULL,   /* 'cancelada' | 'negada' */
        Via                 NVARCHAR(20)    NOT NULL,   /* 'venda' (sem nota) | 'nfce' | 'nfe' (cancelada na SEFAZ) */
        MotivoRecusa        NVARCHAR(500)   NULL,       /* por que foi negada (senha errada, venda já cancelada...) */
        VendaId             NVARCHAR(40)    NULL,
        SaleNumber          NVARCHAR(40)    NULL,
        OperadorId          NVARCHAR(40)    NOT NULL,
        OperadorNome        NVARCHAR(180)   NOT NULL,
        SupervisorId        NVARCHAR(40)    NULL,       /* gerente informado (validado só quando Resultado = 'cancelada') */
        SupervisorNome      NVARCHAR(180)   NULL,
        Justificativa       NVARCHAR(500)   NULL,
        Terminal            NVARCHAR(80)    NULL,       /* deviceId do caixa que pediu */
        Ip                  NVARCHAR(64)    NULL,
        CaixaSessaoId       NVARCHAR(60)    NULL,
        CaixaSessaoStatus   NVARCHAR(20)    NULL,       /* 'aberta' | 'fechada': cancelar venda de turno fechado muda o fechamento */
        TotalVenda          DECIMAL(15,2)   NULL,
        ClienteNome         NVARCHAR(180)   NULL,
        ClienteCpf          NVARCHAR(30)    NULL,
        ItensEstornados     INT             NULL,
        FiadoEstornado      DECIMAL(15,2)   NULL,
        Snapshot            NVARCHAR(MAX)   NULL,       /* JSON: itens, pagamentos, notas fiscais */
        CONSTRAINT PK_VendaCancelamentos PRIMARY KEY (Id)
    );
END;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_VendaCancelamentos_Company' AND object_id = OBJECT_ID(N'VendaCancelamentos'))
BEGIN
    CREATE INDEX IX_VendaCancelamentos_Company ON VendaCancelamentos (CompanyId, OcorridoEm DESC);
END;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_VendaCancelamentos_Venda' AND object_id = OBJECT_ID(N'VendaCancelamentos'))
BEGIN
    CREATE INDEX IX_VendaCancelamentos_Venda ON VendaCancelamentos (CompanyId, SaleNumber);
END;
GO
