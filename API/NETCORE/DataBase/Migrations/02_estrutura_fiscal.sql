/*
 * Arquivo: API/NETCORE/DataBase/Migrations/02_estrutura_fiscal.sql
 * Objetivo: adicionar os campos fiscais em Produtos, Empresas e Clientes e criar
 *           as tabelas de controle de documentos fiscais (NFC-e modelo 65).
 * Pré-requisito: 01_migracao_valores.sql aplicado (Produtos.ProductQnt já é DECIMAL).
 *
 * Idempotente (IF COL_LENGTH / IF OBJECT_ID em todo bloco) — o HorusDatabaseInitializer
 * executa este arquivo em todo boot, igual ao DataBase/Resumo.sql.
 *
 * Referências de leiaute: NF-e/NFC-e v4.00 + NT 2025.002 (RTC — IBS/CBS/IS).
 */

USE HorusPdv;
GO

/* ------------------------------------------------------------------------- */
/* 1. Produtos — dados fiscais do item                                        */
/* ------------------------------------------------------------------------- */
IF COL_LENGTH(N'Produtos', N'Ncm') IS NULL
BEGIN
    -- I05 / I05c: NCM com 8 dígitos, CEST 7 quando aplicável (ST)
    ALTER TABLE Produtos ADD Ncm  CHAR(8)  NOT NULL CONSTRAINT DF_Produtos_Ncm  DEFAULT '00000000';
    ALTER TABLE Produtos ADD Cest CHAR(7)  NULL;

    -- I08: CFOP padrão de venda no varejo presencial
    ALTER TABLE Produtos ADD Cfop CHAR(4)  NOT NULL CONSTRAINT DF_Produtos_Cfop DEFAULT '5102';

    -- N11: origem da mercadoria (0 nacional .. 8 importação direta s/ similar)
    ALTER TABLE Produtos ADD OrigemMercadoria TINYINT NOT NULL CONSTRAINT DF_Produtos_Origem DEFAULT 0;

    -- I09 / I13: unidade comercial e tributável (UN, KG, LT, MT, CX...)
    ALTER TABLE Produtos ADD UnidadeComercial  NVARCHAR(6) NOT NULL CONSTRAINT DF_Produtos_UnCom  DEFAULT N'UN';
    ALTER TABLE Produtos ADD UnidadeTributavel NVARCHAR(6) NOT NULL CONSTRAINT DF_Produtos_UnTrib DEFAULT N'UN';

    -- I03 / I12: GTIN. 'SEM GTIN' é o literal aceito pela SEFAZ quando não há código
    ALTER TABLE Produtos ADD Gtin NVARCHAR(14) NOT NULL CONSTRAINT DF_Produtos_Gtin DEFAULT N'SEM GTIN';

    -- ICMS: CSOSN para CRT 1/4 (Simples), CST para CRT 3 (regime normal)
    ALTER TABLE Produtos ADD CsosnIcms NVARCHAR(4) NULL;   -- ex.: '102', '500'
    ALTER TABLE Produtos ADD CstIcms   NVARCHAR(3) NULL;   -- ex.: '00', '60'
    ALTER TABLE Produtos ADD AliquotaIcms DECIMAL(7,4) NOT NULL CONSTRAINT DF_Produtos_AliqIcms DEFAULT 0;

    -- PIS/COFINS
    ALTER TABLE Produtos ADD CstPis    NVARCHAR(3) NOT NULL CONSTRAINT DF_Produtos_CstPis    DEFAULT N'07';
    ALTER TABLE Produtos ADD CstCofins NVARCHAR(3) NOT NULL CONSTRAINT DF_Produtos_CstCofins DEFAULT N'07';

    -- RTC / NT 2025.002 — grupo UB por item
    ALTER TABLE Produtos ADD CstIbsCbs  NVARCHAR(3) NULL;  -- CSTIBSCBS, ex.: '000'
    ALTER TABLE Produtos ADD CClassTrib NVARCHAR(6) NULL;  -- classificação tributária RTC
END;
GO

/* ------------------------------------------------------------------------- */
/* 2. Empresas — emitente, certificado e credenciais SEFAZ                    */
/* ------------------------------------------------------------------------- */
IF COL_LENGTH(N'Empresas', N'Crt') IS NULL
BEGIN
    -- C21: 1 Simples, 2 Simples excesso sublimite, 3 Regime Normal, 4 MEI
    ALTER TABLE Empresas ADD Crt TINYINT NOT NULL CONSTRAINT DF_Empresas_Crt DEFAULT 1;
    ALTER TABLE Empresas ADD CnaeFiscal NVARCHAR(7) NOT NULL CONSTRAINT DF_Empresas_Cnae DEFAULT N'';

    -- C10: código do município IBGE (Rio de Janeiro = 3304557)
    ALTER TABLE Empresas ADD CodigoMunicipioIbge NVARCHAR(7) NOT NULL CONSTRAINT DF_Empresas_CMun DEFAULT N'3304557';
    ALTER TABLE Empresas ADD CodigoUfIbge TINYINT NOT NULL CONSTRAINT DF_Empresas_CUf DEFAULT 33; -- RJ

    -- 1 = Produção, 2 = Homologação. Nasce em homologação de propósito.
    ALTER TABLE Empresas ADD AmbienteFiscal TINYINT NOT NULL CONSTRAINT DF_Empresas_Amb DEFAULT 2;

    -- CSC / Token do QR Code da NFC-e, obtido no portal da SEFAZ-RJ, por ambiente.
    -- Valor cifrado em repouso via HorusSecretProtector (AES-GCM).
    ALTER TABLE Empresas ADD CscId          NVARCHAR(6)   NULL;
    ALTER TABLE Empresas ADD CscCifrado     NVARCHAR(500) NULL;

    -- Certificado digital A1 (.pfx): guardado como base64 cifrado via HorusSecretProtector
    -- (mesmo padrão do EmailSmtpPassword), não em bytes crus — por isso NVARCHAR(MAX) e
    -- não VARBINARY(MAX).
    ALTER TABLE Empresas ADD CertificadoPfxCifrado  NVARCHAR(MAX) NULL;
    ALTER TABLE Empresas ADD CertificadoSenhaCifrada NVARCHAR(500) NULL;
    ALTER TABLE Empresas ADD CertificadoThumbprint NVARCHAR(80)    NULL;
    ALTER TABLE Empresas ADD CertificadoValidoAte  DATETIMEOFFSET  NULL;

    -- Responsável técnico (grupo ZD, obrigatório na v4.00)
    ALTER TABLE Empresas ADD RespTecCnpj    NVARCHAR(14)  NULL;
    ALTER TABLE Empresas ADD RespTecContato NVARCHAR(120) NULL;
    ALTER TABLE Empresas ADD RespTecEmail   NVARCHAR(180) NULL;
    ALTER TABLE Empresas ADD RespTecFone    NVARCHAR(20)  NULL;
END;
GO

/* ------------------------------------------------------------------------- */
/* 3. Clientes — destinatário                                                 */
/* ------------------------------------------------------------------------- */
IF COL_LENGTH(N'Clientes', N'IndIeDest') IS NULL
BEGIN
    -- E16a: 1 contribuinte ICMS, 2 isento, 9 não contribuinte
    ALTER TABLE Clientes ADD IndIeDest TINYINT NOT NULL CONSTRAINT DF_Clientes_IndIeDest DEFAULT 9;
    ALTER TABLE Clientes ADD InscricaoEstadual NVARCHAR(20) NULL;
    ALTER TABLE Clientes ADD CodigoMunicipioIbge NVARCHAR(7) NULL;
END;
GO

/* ------------------------------------------------------------------------- */
/* 4. FiscalSequencias — numeração por empresa, modelo e série                */
/*                                                                            */
/* Desacoplado de Vendas.SaleNumber. Cada terminal de caixa recebe sua        */
/* própria série para permitir emissão simultânea sem colisão de nNF.         */
/* ------------------------------------------------------------------------- */
IF OBJECT_ID(N'FiscalSequencias', N'U') IS NULL
BEGIN
    CREATE TABLE FiscalSequencias
    (
        CompanyId      NVARCHAR(40) NOT NULL,
        Modelo         SMALLINT     NOT NULL,   -- 65 = NFC-e, 55 = NF-e
        Serie          INT          NOT NULL,
        Ambiente       TINYINT      NOT NULL,   -- numeração é independente por ambiente
        ProximoNumero  INT          NOT NULL CONSTRAINT DF_FiscalSequencias_Proximo DEFAULT 1,
        AtualizadoEm   DATETIMEOFFSET NOT NULL CONSTRAINT DF_FiscalSequencias_Atualizado DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT PK_FiscalSequencias PRIMARY KEY (CompanyId, Modelo, Serie, Ambiente)
    );
END;
GO

/* ------------------------------------------------------------------------- */
/* 5. DocumentosFiscais — documento + fila de transmissão (outbox)            */
/*                                                                            */
/* Esta tabela É a fila. Status/ProximaTentativaEm controlam o worker; não há */
/* fila em memória, para que a contingência sobreviva a restart do processo.  */
/*                                                                            */
/* Status: 0 rascunho | 1 assinado | 2 transmitindo | 3 autorizado            */
/*         4 rejeitado | 5 denegado | 6 cancelado | 7 inutilizado             */
/*         8 contingencia_pendente                                            */
/* ------------------------------------------------------------------------- */
IF OBJECT_ID(N'DocumentosFiscais', N'U') IS NULL
BEGIN
    CREATE TABLE DocumentosFiscais
    (
        Id             NVARCHAR(40)  NOT NULL CONSTRAINT PK_DocumentosFiscais PRIMARY KEY,
        CompanyId      NVARCHAR(40)  NOT NULL,
        VendaId        NVARCHAR(40)  NULL,
        Modelo         SMALLINT      NOT NULL CONSTRAINT DF_DocFiscais_Modelo DEFAULT 65,
        Serie          INT           NOT NULL,
        NumeroNf       INT           NOT NULL,
        Ambiente       TINYINT       NOT NULL,
        ChaveAcesso    CHAR(44)      NULL,
        Status         TINYINT       NOT NULL CONSTRAINT DF_DocFiscais_Status DEFAULT 0,

        -- B26: 1 normal, 9 contingência offline NFC-e
        TpEmis         TINYINT       NOT NULL CONSTRAINT DF_DocFiscais_TpEmis DEFAULT 1,
        DhContingencia DATETIMEOFFSET NULL,
        JustContingencia NVARCHAR(256) NULL,

        Protocolo      NVARCHAR(20)  NULL,
        DhAutorizacao  DATETIMEOFFSET NULL,
        CodigoStatus   INT           NULL,        -- cStat da SEFAZ
        MotivoStatus   NVARCHAR(500) NULL,        -- xMotivo

        XmlAssinado    NVARCHAR(MAX) NULL,
        XmlProtocolado NVARCHAR(MAX) NULL,        -- nfeProc, o que se guarda por 5 anos
        XmlCancelamento NVARCHAR(MAX) NULL,

        Tentativas       INT NOT NULL CONSTRAINT DF_DocFiscais_Tentativas DEFAULT 0,
        ProximaTentativaEm DATETIMEOFFSET NULL,
        UltimoErro       NVARCHAR(1000) NULL,

        CriadoEm       DATETIMEOFFSET NOT NULL CONSTRAINT DF_DocFiscais_CriadoEm DEFAULT SYSDATETIMEOFFSET(),
        AtualizadoEm   DATETIMEOFFSET NOT NULL CONSTRAINT DF_DocFiscais_AtualizadoEm DEFAULT SYSDATETIMEOFFSET(),

        CONSTRAINT UQ_DocFiscais_Numeracao UNIQUE (CompanyId, Modelo, Serie, Ambiente, NumeroNf),
        CONSTRAINT FK_DocFiscais_Vendas FOREIGN KEY (VendaId) REFERENCES Vendas (Id)
    );

    -- Chave de acesso é única globalmente, mas só quando já existe
    CREATE UNIQUE INDEX UQ_DocFiscais_Chave
        ON DocumentosFiscais (ChaveAcesso) WHERE ChaveAcesso IS NOT NULL;

    -- Índice que o worker usa para varrer a fila
    CREATE INDEX IX_DocFiscais_Fila
        ON DocumentosFiscais (Status, ProximaTentativaEm)
        INCLUDE (CompanyId, Id) WHERE Status IN (1, 2, 8);

    CREATE INDEX IX_DocFiscais_Company_Data
        ON DocumentosFiscais (CompanyId, CriadoEm DESC);
END;
GO

/* ------------------------------------------------------------------------- */
/* 6. Semente: série 1 em homologação para as empresas existentes             */
/* ------------------------------------------------------------------------- */
INSERT INTO FiscalSequencias (CompanyId, Modelo, Serie, Ambiente, ProximoNumero)
SELECT e.Id, 65, 1, 2, 1
  FROM Empresas e
 WHERE NOT EXISTS (
        SELECT 1 FROM FiscalSequencias f
         WHERE f.CompanyId = e.Id AND f.Modelo = 65 AND f.Serie = 1 AND f.Ambiente = 2);
GO
