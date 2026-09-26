import { test, expect } from "@playwright/test";

test.describe("BLOCO 8 — Hash Criptográfico Canônico e Detecção de Tampering", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/");
    await page.waitForFunction(() => !!(window as any).__horus_test__);
  });

  test("Teste 27 — Determinismo: mesmo payload gera o mesmo hash canônico repetidamente", async ({
    page,
  }) => {
    const result = await page.evaluate(async () => {
      const { cryptoHash } = (window as any).__horus_test__;

      const salePayload = {
        companyId: "comp-1",
        customerName: "Consumidor Final",
        customerCpf: "11122233344",
        paymentType: "Dinheiro",
        totalAmount: 150.5,
        operatorName: "Operador Teste",
        items: [
          {
            productCode: "PROD-01",
            quantity: 2,
            unitPrice: 50.0,
            desconto: 0,
          },
          {
            productCode: "PROD-02",
            quantity: 1,
            unitPrice: 50.5,
            desconto: 0,
          },
        ],
        payments: [
          {
            paymentType: "Dinheiro",
            amount: 150.5,
            cashGiven: 200.0,
            changeAmount: 49.5,
          },
        ],
      };

      const saleHash1 = await cryptoHash.computeSalePayloadHash(salePayload);
      const saleHash2 = await cryptoHash.computeSalePayloadHash(salePayload);
      const saleHash3 = await cryptoHash.computeSalePayloadHash({ ...salePayload });

      const movementPayload = {
        tipo: "Reforco",
        valor: "50,00",
        motivo: "Fundo de troco inicial",
      };

      const movHash1 = await cryptoHash.computeCashMovementPayloadHash(movementPayload);
      const movHash2 = await cryptoHash.computeCashMovementPayloadHash(movementPayload);
      const movHash3 = await cryptoHash.computeCashMovementPayloadHash({ ...movementPayload });

      return {
        saleHash1,
        saleHash2,
        saleHash3,
        movHash1,
        movHash2,
        movHash3,
      };
    });

    expect(result.saleHash1).toBeDefined();
    expect(result.saleHash1.length).toBe(64);
    expect(result.saleHash1).toBe(result.saleHash2);
    expect(result.saleHash1).toBe(result.saleHash3);

    expect(result.movHash1).toBeDefined();
    expect(result.movHash1.length).toBe(64);
    expect(result.movHash1).toBe(result.movHash2);
    expect(result.movHash1).toBe(result.movHash3);
  });

  test("Teste 28 — Sensibilidade a valor: alteração de centavos altera completamente o hash", async ({
    page,
  }) => {
    const result = await page.evaluate(async () => {
      const { cryptoHash } = (window as any).__horus_test__;

      const baseSale = {
        customerName: "Cliente A",
        customerCpf: "",
        paymentType: "Dinheiro",
        totalAmount: 100.0,
        operatorName: "Op 1",
        items: [{ productCode: "P1", quantity: 1, unitPrice: 100.0, desconto: 0 }],
        payments: [{ paymentType: "Dinheiro", amount: 100.0, cashGiven: 100.0, changeAmount: 0 }],
      };

      const modifiedSale = {
        ...baseSale,
        totalAmount: 100.01, // Diferença de R$ 0,01
      };

      const hashSaleOriginal = await cryptoHash.computeSalePayloadHash(baseSale);
      const hashSaleModified = await cryptoHash.computeSalePayloadHash(modifiedSale);

      const baseMov = {
        tipo: "Sangria",
        valor: "100,00",
        motivo: "Recolhimento parcial",
      };

      const modifiedMov = {
        tipo: "Sangria",
        valor: "100,01",
        motivo: "Recolhimento parcial",
      };

      const hashMovOriginal = await cryptoHash.computeCashMovementPayloadHash(baseMov);
      const hashMovModified = await cryptoHash.computeCashMovementPayloadHash(modifiedMov);

      return {
        hashSaleOriginal,
        hashSaleModified,
        hashMovOriginal,
        hashMovModified,
      };
    });

    expect(result.hashSaleOriginal).not.toBe(result.hashSaleModified);
    expect(result.hashMovOriginal).not.toBe(result.hashMovModified);
  });

  test("Teste 29 — Sensibilidade a tipo: mudança semântica altera o hash", async ({ page }) => {
    const result = await page.evaluate(async () => {
      const { cryptoHash } = (window as any).__horus_test__;

      const movReforco = {
        tipo: "Reforco",
        valor: "50.00",
        motivo: "Aporte",
      };

      const movSangria = {
        tipo: "Sangria",
        valor: "50.00",
        motivo: "Aporte",
      };

      const hashReforco = await cryptoHash.computeCashMovementPayloadHash(movReforco);
      const hashSangria = await cryptoHash.computeCashMovementPayloadHash(movSangria);

      const saleDinheiro = {
        customerName: "Cliente A",
        customerCpf: "",
        paymentType: "Dinheiro",
        totalAmount: 50.0,
        operatorName: "Op 1",
        items: [{ productCode: "P1", quantity: 1, unitPrice: 50.0, desconto: 0 }],
        payments: [{ paymentType: "Dinheiro", amount: 50.0, cashGiven: 50.0, changeAmount: 0 }],
      };

      const salePix = {
        ...saleDinheiro,
        paymentType: "Pix",
        payments: [{ paymentType: "Pix", amount: 50.0, cashGiven: 50.0, changeAmount: 0 }],
      };

      const hashDinheiro = await cryptoHash.computeSalePayloadHash(saleDinheiro);
      const hashPix = await cryptoHash.computeSalePayloadHash(salePix);

      return {
        hashReforco,
        hashSangria,
        hashDinheiro,
        hashPix,
      };
    });

    expect(result.hashReforco).not.toBe(result.hashSangria);
    expect(result.hashDinheiro).not.toBe(result.hashPix);
  });

  test("Teste 30 — Canonicalização: ordenação, formatação monetária e espaços redundantes produzem o mesmo hash", async ({
    page,
  }) => {
    const result = await page.evaluate(async () => {
      const { cryptoHash } = (window as any).__horus_test__;

      // 1. CASH MOVEMENT: Formatações de moeda e case/espaço
      const movA = {
        tipo: "REFORCO  ",
        valor: "R$ 50,00",
        motivo: "  Fundo de Troco  ",
      };

      const movB = {
        tipo: "reforco",
        valor: "50,00",
        motivo: "Fundo de Troco",
      };

      const hashMovA = await cryptoHash.computeCashMovementPayloadHash(movA);
      const hashMovB = await cryptoHash.computeCashMovementPayloadHash(movB);

      // 2. VENDA: Itens fora de ordem e espaços em branco
      const saleA = {
        customerName: "  Consumidor Final  ",
        customerCpf: "11122233344",
        paymentType: "Dinheiro",
        totalAmount: "R$ 150,00",
        operatorName: "Operador A",
        items: [
          { productCode: "BBB", quantity: 1, unitPrice: 100, desconto: 0 },
          { productCode: "AAA", quantity: 1, unitPrice: 50, desconto: 0 },
        ],
        payments: [
          { paymentType: "Dinheiro", amount: 150, cashGiven: 150, changeAmount: 0 },
        ],
      };

      const saleB = {
        customerName: "Consumidor Final",
        customerCpf: "11122233344",
        paymentType: "Dinheiro",
        totalAmount: 150.0,
        operatorName: "Operador A",
        items: [
          // Ordem invertida: o algoritmo canônico deve ordenar por productCode antes de hashear
          { productCode: "AAA", quantity: 1, unitPrice: 50, desconto: 0 },
          { productCode: "BBB", quantity: 1, unitPrice: 100, desconto: 0 },
        ],
        payments: [
          { paymentType: "Dinheiro", amount: 150, cashGiven: 150, changeAmount: 0 },
        ],
      };

      const hashSaleA = await cryptoHash.computeSalePayloadHash(saleA as any);
      const hashSaleB = await cryptoHash.computeSalePayloadHash(saleB as any);

      return {
        hashMovA,
        hashMovB,
        hashSaleA,
        hashSaleB,
      };
    });

    // Hash canônico idêntico para movimentação
    expect(result.hashMovA).toBe(result.hashMovB);

    // Hash canônico idêntico para venda mesmo com ordem invertida de itens e formatação R$
    expect(result.hashSaleA).toBe(result.hashSaleB);
  });
});
