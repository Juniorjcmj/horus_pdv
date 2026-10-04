-- Migration 37: nova semântica das regras de emissão de NFC-e.
--   ANTES: FormasPagamentoHabilitadas = formas que entravam no intervalo (1 a cada N); as desmarcadas nunca emitiam.
--   AGORA: FormasPagamentoHabilitadas = formas que SEMPRE emitem (ex.: PIX); todas as demais seguem o intervalo.
--
-- Para não mudar o comportamento de quem tinha "todas as formas marcadas + intervalo N > 1" (antes: 1 a cada N de
-- todas as vendas), a lista dessas empresas é esvaziada: nenhuma forma "sempre emite" e todas seguem o intervalo.
-- A coluna-marcador SemanticaV2 garante que a conversão rode uma única vez (a migração roda a cada boot).

IF COL_LENGTH(N'RegrasEmissaoNfce', N'SemanticaV2') IS NULL
BEGIN
    ALTER TABLE RegrasEmissaoNfce
        ADD SemanticaV2 BIT NOT NULL CONSTRAINT DF_RegrasEmissaoNfce_SemanticaV2 DEFAULT (1);

    UPDATE RegrasEmissaoNfce
       SET FormasPagamentoHabilitadas = N'',
           UpdatedAt = SYSUTCDATETIME()
     WHERE IntervaloNotas > 1
       AND FormasPagamentoHabilitadas LIKE N'%dinheiro%'
       AND FormasPagamentoHabilitadas LIKE N'%credito%'
       AND FormasPagamentoHabilitadas LIKE N'%debito%'
       AND FormasPagamentoHabilitadas LIKE N'%pix%'
       AND FormasPagamentoHabilitadas LIKE N'%fiado%';
END
