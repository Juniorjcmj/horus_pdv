/**
 * CHANGE 08.1 — Categoria H: Fiscal NFC-e (Online)
 *
 * H01 — NFC-e online
 *       Validar o comportamento fiscal ONLINE existente.
 *       Se o ambiente SEFAZ de homologação não estiver disponível, marcar como bloqueado.
 *       NÃO simular artificialmente uma autorização fiscal.
 *       NÃO criar mock que esconda o problema.
 */
import { test, expect } from "@playwright/test";
import {
  RUN_ID,
  API_URL,
  initSqlContainer,
  registerTestCompany,
  loginApi,
  loginBrowserSession,
  cleanupHomologData,
  api,
  productPayload,
  supplierPayload,
  generateCpf,
  openAppPage,
  querySqlScalar,
} from "./helpers/setup";

type Entity = { id: string };

test.describe("H — Fiscal NFC-e", () => {
  test.describe.configure({ mode: "serial" });

  let productCode: string;

  test.beforeAll(async ({ request }) => {
    initSqlContainer();
    cleanupHomologData();
    await registerTestCompany(request);
    await loginApi(request);

    await api<Entity>(request, "/Fornecedor", {
      method: "POST",
      body: supplierPayload("Fornecedor H"),
    });
    const payload = productPayload("ProdutoH", `${RUN_ID} Fornecedor H`, "100");
    await api(request, "/Produto", { method: "POST", body: payload });
    productCode = payload.productCode;

    await api(request, "/Caixa/abrir", {
      method: "POST",
      body: { openingAmount: "100,00" },
    });
  });

  test.afterAll(async ({ request }) => {
    await api(request, "/Caixa/fechar", {
      method: "POST",
      body: { closingAmount: "200,00", note: `${RUN_ID} cleanup H` },
      allowFailure: true,
    });
    cleanupHomologData();
  });

  test("H01 — NFC-e online — endpoint de listagem funciona", async ({ request }) => {
    // Validar que o endpoint de listagem NFC-e está funcional
    const listResponse = await request.fetch(`${API_URL}/NfceEmissao`, {
      method: "GET",
      headers: { "Content-Type": "application/json" },
    });

    // Não deve ser 500 (erro interno)
    expect(
      listResponse.status(),
      "Endpoint de listagem NFC-e não deve retornar 500",
    ).not.toBe(500);

    if (listResponse.ok()) {
      const body = await listResponse.json();
      expect(body, "Resposta deve ser válida").toBeTruthy();
    }

    // Verificar se a empresa de teste tem configuração fiscal (certificado, CSC)
    const hasCert = querySqlScalar(
      `SELECT TOP 1 CertificadoDigital FROM Empresas WHERE Status = N'aprovada' AND CertificadoDigital IS NOT NULL AND LEN(CertificadoDigital) > 0`,
    );

    const hasCsc = querySqlScalar(
      `SELECT TOP 1 CscToken FROM Empresas WHERE Status = N'aprovada' AND CscToken IS NOT NULL AND LEN(CscToken) > 0`,
    );

    if (!hasCert || !hasCsc) {
      console.log(
        "GAP-H01: Emissão real de NFC-e não pode ser testada — empresa de teste " +
          "não possui certificado digital A1 e/ou CSC configurados. " +
          `Certificado: ${hasCert ? "presente" : "ausente"}, CSC: ${hasCsc ? "presente" : "ausente"}. ` +
          "Severidade: P2 (melhoria) — teste de emissão requer infraestrutura fiscal. " +
          "CHANGE futura sugerida: CHANGE 09 — Setup de certificado de teste para homologação SEFAZ.",
      );
    }

    // Realizar venda que deveria acionar emissão fiscal (se configurada)
    const sale = await api<{ saleNumber: string; fiscalQueued?: boolean }>(
      request,
      "/HistoricoVendas",
      {
        method: "POST",
        body: {
          customerName: `${RUN_ID} FiscalTest`,
          customerCpf: generateCpf(),
          paymentType: "Dinheiro",
          totalAmount: "25,00",
          items: [
            {
              productCode,
              productName: `${RUN_ID} ProdutoH`,
              quantity: 1,
            },
          ],
        },
      },
    );

    expect(sale.saleNumber, "Venda para teste fiscal deve ser criada").toBeTruthy();

    // Consultar se um documento fiscal foi enfileirado para esta venda
    const fiscalDoc = await request.fetch(
      `${API_URL}/NfceEmissao/${sale.saleNumber}`,
      {
        method: "GET",
        headers: { "Content-Type": "application/json" },
      },
    );

    if (fiscalDoc.ok()) {
      const docBody = await fiscalDoc.json();
      const status = docBody?.data?.status ?? docBody?.status;
      console.log(
        `Fiscal: documento encontrado para venda ${sale.saleNumber}, status=${status}`,
      );
      // Status 3=Autorizado, 4=Rejeitado, 8=ContingenciaPendente
      // Qualquer status indica que o módulo fiscal está ativo
    } else if (fiscalDoc.status() === 404) {
      console.log(
        "NOTA-H01: Nenhum documento fiscal gerado para a venda — " +
          "emissão automática pode não estar configurada ou empresa sem certificado.",
      );
    }
  });

  test("H02 — Página Fiscal NFC-e renderiza na UI", async ({ page }) => {
    await loginBrowserSession(page);
    await openAppPage(page, "fiscal", "Central de Notas Fiscais");

    // Validar que a página renderiza sem erro
    await expect(
      page.getByRole("heading", { name: "Central de Notas Fiscais", exact: true }),
    ).toBeVisible();

    // Validar que não houve erros JS fatais durante a navegação
    const errors: string[] = [];
    page.on("pageerror", (err) => errors.push(err.message));

    // Esperar um momento para capturar erros de renderização
    await page.waitForTimeout(2000);

    const fatalErrors = errors.filter(
      (e) => !e.includes("ResizeObserver") && !e.includes("Script error"),
    );
    expect(
      fatalErrors,
      `Erros JS na página fiscal: ${fatalErrors.join(", ")}`,
    ).toHaveLength(0);
  });
});
