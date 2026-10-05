/*
 * Arquivo: API/NETCORE/DataBase/Migrations/38_treinamento.sql
 * Objetivo: página de aprendizado (vídeos de treinamento organizados em seções). O conteúdo é GLOBAL da
 *           plataforma (igual para todas as empresas): todos os usuários assistem, e só o administrador geral
 *           (empresa-principal) cria/edita seções e vídeos. Semeia as seções "Primeiros passos" e "Caixa"
 *           com os primeiros vídeos, apenas na criação das tabelas (apagar depois não recria).
 *
 * Idempotente: pode ser executado no boot repetidamente sem erro.
 */

SET NOCOUNT ON;
GO

IF OBJECT_ID(N'TreinamentoSecoes', N'U') IS NULL
BEGIN
    CREATE TABLE TreinamentoSecoes (
        Id          NVARCHAR(40)    NOT NULL,
        Nome        NVARCHAR(120)   NOT NULL,
        Descricao   NVARCHAR(400)   NULL,
        Ordem       INT             NOT NULL CONSTRAINT DF_TreinamentoSecoes_Ordem DEFAULT 0,
        CriadoEm    DATETIMEOFFSET  NOT NULL CONSTRAINT DF_TreinamentoSecoes_CriadoEm DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT PK_TreinamentoSecoes PRIMARY KEY (Id)
    );

    CREATE TABLE TreinamentoVideos (
        Id          NVARCHAR(40)    NOT NULL,
        SecaoId     NVARCHAR(40)    NOT NULL,
        Titulo      NVARCHAR(160)   NOT NULL,
        Descricao   NVARCHAR(600)   NULL,
        Instrucoes  NVARCHAR(MAX)   NULL,           /* passos em texto, um por linha */
        YoutubeId   NVARCHAR(20)    NOT NULL,
        Ordem       INT             NOT NULL CONSTRAINT DF_TreinamentoVideos_Ordem DEFAULT 0,
        CriadoEm    DATETIMEOFFSET  NOT NULL CONSTRAINT DF_TreinamentoVideos_CriadoEm DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT PK_TreinamentoVideos PRIMARY KEY (Id),
        CONSTRAINT FK_TreinamentoVideos_Secao FOREIGN KEY (SecaoId) REFERENCES TreinamentoSecoes (Id) ON DELETE CASCADE
    );

    CREATE INDEX IX_TreinamentoVideos_Secao ON TreinamentoVideos (SecaoId, Ordem);

    /* Semente: no mesmo bloco da criação, então só roda uma vez (apagar depois não recria). */
    INSERT INTO TreinamentoSecoes (Id, Nome, Descricao, Ordem) VALUES
        (N'trs-primeiros-passos', N'Primeiros passos', N'Como acessar e começar a usar o Quack Sistemas.', 1),
        (N'trs-caixa', N'Caixa', N'Tudo sobre a frente de caixa: abertura, vendas, ajustes e fechamento.', 2);

    INSERT INTO TreinamentoVideos (Id, SecaoId, Titulo, Descricao, Instrucoes, YoutubeId, Ordem) VALUES
        (N'trv-entrar-sistema', N'trs-primeiros-passos', N'Como entrar no Quack Sistemas',
         N'Passo a passo para acessar o sistema com seu usuário e senha.',
         N'Acesse o endereço do sistema no navegador.' + CHAR(10) + N'Informe seu e-mail e sua senha.' + CHAR(10) + N'Clique em Entrar.' + CHAR(10) + N'Em caso de dúvida na senha, fale com o administrador da sua loja.',
         N'uEj6GUdI0BI', 1),
        (N'trv-abertura-caixa', N'trs-caixa', N'Abertura de caixa',
         N'Como abrir o caixa informando o fundo de troco.',
         N'Abra a tela Caixa.' + CHAR(10) + N'Informe o valor do fundo de troco.' + CHAR(10) + N'Confirme a abertura do caixa.' + CHAR(10) + N'Pronto: o PDV já pode registrar vendas.',
         N'SzV2ejoasmw', 1),
        (N'trv-venda-simples', N'trs-caixa', N'Fazer uma venda simples',
         N'Registrando uma venda do início ao fim.',
         N'Na tela de Vendas, busque o produto pelo nome ou código de barras.' + CHAR(10) + N'Informe a quantidade e adicione o item.' + CHAR(10) + N'Clique em Pagamento, escolha a forma de pagamento e informe o valor.' + CHAR(10) + N'Confirme a venda e entregue o troco, se houver.',
         N'VWoaz46u564', 2),
        (N'trv-venda-fiado', N'trs-caixa', N'Venda fiado',
         N'Como vender no fiado (crediário) para um cliente cadastrado.',
         N'Adicione os produtos à venda.' + CHAR(10) + N'No pagamento, escolha Fiado.' + CHAR(10) + N'Selecione o cliente.' + CHAR(10) + N'Confirme a venda: o valor fica lançado no fiado do cliente.',
         N'uBAkCw_WxEw', 3),
        (N'trv-alterar-valor', N'trs-caixa', N'Alteração do valor do produto no caixa',
         N'Mudando o preço de um item na venda com a senha de um gerente.',
         N'Clique no ícone de preço do item.' + CHAR(10) + N'Informe o novo valor.' + CHAR(10) + N'Peça ao gerente para digitar a senha de autorização.' + CHAR(10) + N'O item passa a valer o novo preço apenas nesta venda.',
         N'4ZAPZEemohI', 4),
        (N'trv-vendas-caixa', N'trs-caixa', N'Relatório de vendas do caixa',
         N'Consultando as vendas feitas no caixa atual.',
         N'Abra Vendas do Caixa na tela do PDV.' + CHAR(10) + N'Use os filtros (forma de pagamento, valor) para localizar vendas.' + CHAR(10) + N'O total no topo acompanha os filtros aplicados.',
         N'EXcpcR43h9o', 5),
        (N'trv-cancelar-venda', N'trs-caixa', N'Cancelamento de venda',
         N'Como cancelar uma venda já finalizada a partir do caixa.',
         N'Abra Vendas do Caixa e localize a venda.' + CHAR(10) + N'Escolha a opção de cancelar.' + CHAR(10) + N'Informe o motivo e confirme.' + CHAR(10) + N'O estoque e os valores do caixa são ajustados.',
         N'otCFMlPVScQ', 6),
        (N'trv-sangria-reforco', N'trs-caixa', N'Sangria e reforço',
         N'Retirando dinheiro do caixa (sangria) e colocando troco (reforço).',
         N'Abra a tela Caixa.' + CHAR(10) + N'Escolha Sangria (retirada) ou Reforço (entrada de dinheiro).' + CHAR(10) + N'Informe o valor e o motivo.' + CHAR(10) + N'Confirme: o movimento entra no relatório de fechamento.',
         N'j6MFC1eT9Zc', 7),
        (N'trv-fechamento-caixa', N'trs-caixa', N'Fechamento de caixa',
         N'Conferindo valores e fechando o caixa no fim do turno.',
         N'Abra a tela Caixa e clique em Fechar caixa.' + CHAR(10) + N'Informe o dinheiro contado na gaveta.' + CHAR(10) + N'Confira o resumo por forma de pagamento, sangrias e reforços.' + CHAR(10) + N'Confirme o fechamento e imprima o relatório.',
         N'OE_mPHYK8cE', 8);
END;
GO
