# Design: Múltiplas Formas de Pagamento no PDV

## Context

Atualmente o Hórus PDV assume uma única forma de pagamento por transação:
- A tabela `Vendas` armazena `PaymentType NVARCHAR(30)` e `TotalAmount NVARCHAR(30)`.
- O endpoint `POST /api/HistoricoVendas` recebe `VendaRequest` contendo apenas `PaymentType` e `TotalAmount`.
- A apuração de caixa em `CaixaAB.ObterTotaisPorFormaPagamentoAsync` faz um simples `GROUP BY PaymentType` sobre a tabela `Vendas`.
- A montagem da NFC-e em `DocumentoFiscalAB.MontarRequisicaoEmissaoAsync` gera um único `PagamentoFiscal` contendo o valor total da venda.
- No frontend (`SalesStartPage.tsx`), o modal de finalização possui um select único de `paymentType` e cálculo de troco restrito a uma única entrega de dinheiro.

Ver motivação completa em `proposal.md` e requisitos em `specs/pdv/multiplas-formas-pagamento/spec.md`.

## Goals / Non-Goals

**Goals:**
- Permitir rateio do valor total de uma venda em 2 ou mais parcelas com formas de pagamento distintas (ex.: R$ 200 no PIX + R$ 100 no Débito).
- Garantir conciliação exata do caixa (dinheiro na gaveta vs. transações eletrônicas).
- Suportar cálculo de troco quando a fração em dinheiro for maior que a respectiva quota restante.
- Enviar as frações discriminadas no XML da NFC-e via múltiplos nós `<detPag>` para a SEFAZ.
- Manter 100% de retrocompatibilidade com vendas passadas e requisições legadas de pagamento único.

**Non-Goals:**
- Integração de TEF dedicado via DLL / pinpad físico em tempo real (as formas continuam sendo informadas pelo operador do PDV).
- Parcelamento a prazo tipo crediário com emissão de boletos ou carnês futuros (o foco é liquidação imediata no checkout).

## Decisions

### 1. Modelagem de Dados: Tabela `VendaPagamentos` (1:N)
Criaremos a tabela `VendaPagamentos` vinculada à venda:
```sql
CREATE TABLE VendaPagamentos (
    Id NVARCHAR(40) NOT NULL CONSTRAINT PK_VendaPagamentos PRIMARY KEY,
    CompanyId NVARCHAR(40) NOT NULL,
    VendaId NVARCHAR(40) NOT NULL,
    PaymentType NVARCHAR(30) NOT NULL,
    Amount DECIMAL(18, 2) NOT NULL,
    CashGiven DECIMAL(18, 2) NOT NULL CONSTRAINT DF_VendaPagamentos_CashGiven DEFAULT 0,
    ChangeAmount DECIMAL(18, 2) NOT NULL CONSTRAINT DF_VendaPagamentos_ChangeAmount DEFAULT 0,
    CreatedAt DATETIMEOFFSET NOT NULL CONSTRAINT DF_VendaPagamentos_CreatedAt DEFAULT SYSDATETIMEOFFSET(),
    CONSTRAINT FK_VendaPagamentos_Vendas FOREIGN KEY (VendaId) REFERENCES Vendas (Id) ON DELETE CASCADE
);
CREATE INDEX IX_VendaPagamentos_Company_Venda ON VendaPagamentos (CompanyId, VendaId);
CREATE INDEX IX_VendaPagamentos_Company_Payment ON VendaPagamentos (CompanyId, PaymentType);
```
- **Por que não coluna JSON na tabela `Vendas`?** Porque colunas JSON dificultam agrupamentos de alta performance, índices e queries diretas do fechamento de caixa (`GROUP BY PaymentType`).
- **Retrocompatibilidade em `Vendas`**: Para vendas com mais de 1 pagamento, `Vendas.PaymentType` receberá a string `"Múltiplo"` (ou a descrição consolidada), enquanto `VendaPagamentos` detalha as linhas exatas. Uma migração preencherá `VendaPagamentos` para as vendas já existentes.

### 2. Contrato da API: `VendaRequest` extensível
No backend:
```csharp
public class VendaPagamentoRequest
{
    public string PaymentType { get; set; } = string.Empty;
    public decimal Amount { get; set; }
    public decimal CashGiven { get; set; }
    public decimal ChangeAmount { get; set; }
}

public class VendaRequest
{
    // Campos legados mantidos para backward compatibility
    public string PaymentType { get; set; } = string.Empty;
    public string TotalAmount { get; set; } = string.Empty;
    ...
    // Nova propriedade
    public List<VendaPagamentoRequest> Payments { get; set; } = [];
}
```
Se `Payments` contiver itens:
- O backend valida se `Payments.Sum(p => p.Amount) == totalAmount`.
- Persiste atomicamente na mesma transação da venda as linhas em `VendaPagamentos`.
Se `Payments` vier vazio (chamada legada):
- O backend cria automaticamente 1 registro em `VendaPagamentos` usando `request.PaymentType` e `totalAmount`.

### 3. Conferência de Caixa (`CaixaAB`)
Atualizar `ObterTotaisPorFormaPagamentoAsync`:
```sql
SELECT vp.PaymentType, SUM(vp.Amount) AS Total
FROM VendaPagamentos vp
INNER JOIN Vendas v ON v.Id = vp.VendaId AND v.CompanyId = vp.CompanyId
WHERE vp.CompanyId = @CompanyId AND v.SaleDate >= @Desde AND v.SaleDate <= @Ate
GROUP BY vp.PaymentType;
```
Dessa forma, cada centavo do PIX, do Débito e do Dinheiro soma exatamente no seu respectivo acumulador de fechamento.

### 4. Emissão Fiscal de NFC-e (`DocumentoFiscalAB`)
Em `MontarRequisicaoEmissaoAsync`:
- O método consulta `VendaPagamentos WHERE VendaId = @VendaId`.
- Mapeia cada parcela para `PagamentoFiscal`:
  - `Tipo = MapearFormaPagamento(p.PaymentType)` (01 Dinheiro, 03 Crédito, 04 Débito, 17 PIX).
  - `Valor = p.Amount`.
- Preenche `EmissaoNfceRequest.Pagamentos` com todas as parcelas e `ValorTroco` com a soma de `ChangeAmount`.
- O `ZeusFiscalProvider` já suporta gerar múltiplos nós `<detPag>` automaticamente a partir dessa lista.

### 5. Frontend UX no Checkout do PDV
O modal de finalização em `SalesStartPage.tsx`:
- Exibe o valor total da venda e o saldo pendente.
- Permite selecionar uma forma e informar o valor da parcela (sugerindo automaticamente o saldo restante).
- Botão "Adicionar Pagamento" insere a linha na lista de pagamentos daquela venda.
- Se a forma for "Dinheiro" e o cliente entregar valor a mais, exibe o troco dessa parcela.
- Permite remover uma parcela antes de confirmar.
- O botão "Confirmar Venda" é liberado assim que o saldo pendente zerar.

## Risks / Trade-offs

- **[Risco] Vendas antigas no banco de dados sem linhas em `VendaPagamentos`**  
  → *Mitigação*: A migração SQL fará um `INSERT INTO VendaPagamentos ... SELECT ... FROM Vendas` populando os pagamentos das vendas históricas com base no `PaymentType` e `TotalAmount` existentes.

- **[Risco] Erro de precisão decimal ao dividir centavos**  
  → *Mitigação*: O backend valida a igualdade de duas casas decimais com `Math.Round(..., 2, MidpointRounding.AwayFromZero)`.
