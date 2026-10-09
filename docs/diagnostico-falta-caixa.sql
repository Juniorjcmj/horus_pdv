/*
 * Diagnóstico de diferença no fechamento de caixa (Hórus/Quack PDV).
 * Rode no SQL Server de produção, um bloco por vez. Troque os valores em @CompanyId e @SessaoId.
 *
 * Regra do sistema (HorusCaixaService.ComputeExpectedCashAsync):
 *   esperado = abertura + pagamentos "dinheiro" das vendas ligadas ao turno (CaixaSessaoId) que não estão
 *              'cancelada' + reforços - sangrias
 *   (o troco NÃO entra: VendaPagamentos.Amount é o valor da venda; o troco fica em ChangeAmount)
 */
DECLARE @CompanyId NVARCHAR(40) = N'COLOQUE-O-ID-DA-EMPRESA';

/* 1) Últimos fechamentos e a diferença de cada um ------------------------------------------------ */
SELECT TOP 20 Id, OperatorId, OperatorName, OpenedAt, ClosedAt, OpeningAmount, ExpectedCashAmount,
       ClosingAmount, DifferenceAmount, DifferenceReason, ClosedByName
FROM CaixaSessoes
WHERE CompanyId = @CompanyId
ORDER BY OpenedAt DESC;

/* 2) Turnos abertos ao mesmo tempo (dois caixas simultâneos) -------------------------------------- */
SELECT a.Id AS TurnoA, a.OperatorName AS OperadorA, b.Id AS TurnoB, b.OperatorName AS OperadorB,
       a.OpenedAt AS AbriuA, a.ClosedAt AS FechouA, b.OpenedAt AS AbriuB, b.ClosedAt AS FechouB,
       CASE WHEN a.OperatorId = b.OperatorId THEN 'MESMO USUÁRIO' ELSE '' END AS Alerta
FROM CaixaSessoes a
JOIN CaixaSessoes b ON b.CompanyId = a.CompanyId AND b.Id > a.Id
 AND b.OpenedAt < ISNULL(a.ClosedAt, SYSDATETIMEOFFSET())
 AND a.OpenedAt < ISNULL(b.ClosedAt, SYSDATETIMEOFFSET())
WHERE a.CompanyId = @CompanyId AND a.OpenedAt >= DATEADD(DAY, -15, SYSDATETIMEOFFSET())
ORDER BY a.OpenedAt DESC;

/* ===== Escolha um turno com falta (Id da consulta 1) e rode os blocos abaixo ===== */
DECLARE @SessaoId NVARCHAR(60) = N'cx-COLOQUE-O-ID-DO-TURNO';
DECLARE @Abriu DATETIMEOFFSET, @Fechou DATETIMEOFFSET, @Operador NVARCHAR(200);
SELECT @Abriu = OpenedAt, @Fechou = ISNULL(ClosedAt, SYSDATETIMEOFFSET()), @Operador = OperatorName
FROM CaixaSessoes WHERE CompanyId = @CompanyId AND Id = @SessaoId;

/* 3) Composição do esperado, linha a linha ------------------------------------------------------- */
SELECT 'abertura' AS Item, OpeningAmount AS Valor FROM CaixaSessoes WHERE CompanyId = @CompanyId AND Id = @SessaoId
UNION ALL
SELECT 'vendas em dinheiro', ISNULL(SUM(vp.Amount), 0)
FROM VendaPagamentos vp JOIN Vendas v ON v.Id = vp.VendaId AND v.CompanyId = vp.CompanyId
WHERE vp.CompanyId = @CompanyId AND v.CaixaSessaoId = @SessaoId AND vp.PaymentType = 'dinheiro'
  AND ISNULL(v.Status, 'finalizada') <> 'cancelada'
UNION ALL
SELECT CASE Tipo WHEN 1 THEN 'reforços' ELSE 'sangrias (tipo ' + CAST(Tipo AS VARCHAR) + ')' END, SUM(Valor)
FROM CaixaMovimentos WHERE CompanyId = @CompanyId AND CaixaSessaoId = @SessaoId GROUP BY Tipo;

/* 4) Vendas em dinheiro do turno, com troco (confira contra os cupons) -------------------------- */
SELECT v.SaleNumber, v.SaleDate, v.SyncedAt, v.Status, v.TotalAmount, vp.PaymentType, vp.Amount,
       vp.CashGiven AS Recebido, vp.ChangeAmount AS Troco, v.OfflineReference
FROM Vendas v JOIN VendaPagamentos vp ON vp.VendaId = v.Id AND vp.CompanyId = v.CompanyId
WHERE v.CompanyId = @CompanyId AND v.CaixaSessaoId = @SessaoId AND vp.PaymentType = 'dinheiro'
ORDER BY v.SaleDate;

/* 5) Vendas que entraram NESTE turno mas foram feitas fora do horário dele (offline sincronizada depois) */
SELECT v.SaleNumber, v.SaleDate, v.SyncedAt, v.TotalAmount, v.PaymentType, v.OperatorName
FROM Vendas v
WHERE v.CompanyId = @CompanyId AND v.CaixaSessaoId = @SessaoId
  AND (v.SaleDate < @Abriu OR v.SaleDate > @Fechou);

/* 6) Vendas feitas no horário do turno pelo mesmo operador, mas ligadas a OUTRO turno ou a nenhum -- */
SELECT v.SaleNumber, v.SaleDate, v.SyncedAt, v.CaixaSessaoId, v.TotalAmount, v.PaymentType, v.Status
FROM Vendas v
WHERE v.CompanyId = @CompanyId AND v.OperatorName = @Operador
  AND v.SaleDate BETWEEN @Abriu AND @Fechou
  AND (v.CaixaSessaoId IS NULL OR v.CaixaSessaoId <> @SessaoId);

/* 7) Sangrias/reforços repetidos (mesmo tipo e valor em até 2 minutos) --------------------------- */
SELECT a.Id, b.Id AS PossivelDuplicado, a.Tipo, a.Valor, a.CreatedAt, b.CreatedAt AS CreatedAt2, a.Motivo, a.OperatorName
FROM CaixaMovimentos a
JOIN CaixaMovimentos b ON b.CompanyId = a.CompanyId AND b.CaixaSessaoId = a.CaixaSessaoId
 AND b.Id > a.Id AND b.Tipo = a.Tipo AND b.Valor = a.Valor
 AND ABS(DATEDIFF(SECOND, a.CreatedAt, b.CreatedAt)) <= 120
WHERE a.CompanyId = @CompanyId AND a.CaixaSessaoId = @SessaoId;

/* 8) Cancelamentos/devoluções feitos durante o turno (dinheiro devolvido ao cliente sai da gaveta) */
SELECT v.SaleNumber, v.SaleDate, v.CaixaSessaoId AS TurnoDaVenda, v.Status, v.TotalAmount, v.PaymentType,
       v.CanceladoEm, v.CanceladoPorOperadorNome, v.CanceladoJustificativa
FROM Vendas v
WHERE v.CompanyId = @CompanyId AND v.Status IN ('cancelada', 'estornada')
  AND (v.CanceladoEm BETWEEN @Abriu AND @Fechou OR v.CaixaSessaoId = @SessaoId);
