/**
 * HOMOLOGAÇÃO — Categoria B: Produtos (CRUD e busca)
 *
 * B01 — Cadastrar produto via API
 * B02 — Buscar produto pelo código (barcode)
 * B03 — Editar produto e verificar alteração
 * B04 — Excluir produto
 */
import { test, expect } from "@playwright/test";
import {
  RUN_ID,
  initSqlContainer,
  registerTestCompany,
  loginApi,
  cleanupHomologData,
  api,
  productPayload,
  supplierPayload,
} from "./helpers/setup";

type Entity = { id: string };
type Product = Entity & { productCode: string; productName: string; productQnt: string };

test.describe("B — Produtos CRUD", () => {
  test.describe.configure({ mode: "serial" });

  let productId: string;
  let productCode: string;
  let supplierId: string;
  const supplierLabel = "Fornecedor B";

  test.beforeAll(async ({ request }) => {
    initSqlContainer();
    cleanupHomologData();
    await registerTestCompany(request);
    await loginApi(request);

    const supplier = await api<Entity>(request, "/Fornecedor", {
      method: "POST",
      body: supplierPayload(supplierLabel),
    });
    supplierId = supplier.id;
  });

  test.afterAll(async ({ request }) => {
    if (productId) await api(request, `/Produto/${productId}`, { method: "DELETE", allowFailure: true });
    if (supplierId) await api(request, `/Fornecedor/${supplierId}`, { method: "DELETE", allowFailure: true });
    cleanupHomologData();
  });

  test("B01 — Cadastrar produto via API", async ({ request }) => {
    const payload = productPayload("ProdutoB", `${RUN_ID} ${supplierLabel}`, "50");
    const product = await api<Product>(request, "/Produto", {
      method: "POST",
      body: payload,
    });
    expect(product.id).toBeTruthy();
    productId = product.id;
    productCode = payload.productCode;
  });

  test("B02 — Buscar produto pelo código (barcode)", async ({ request }) => {
    const products = await api<Product[]>(request, "/Produto");
    const found = products.find((p) => p.productCode === productCode);
    expect(found, `Produto ${productCode} deve existir na listagem`).toBeTruthy();
    expect(found!.productName).toContain(`${RUN_ID} ProdutoB`);
  });

  test("B03 — Editar produto e verificar alteração", async ({ request }) => {
    const newName = `${RUN_ID} ProdutoB Editado`;
    await api(request, `/Produto/${productId}`, {
      method: "PUT",
      body: {
        ...productPayload("ProdutoB Editado", `${RUN_ID} ${supplierLabel}`, "50"),
        productCode,
        productName: newName,
      },
    });

    const products = await api<Product[]>(request, "/Produto");
    const updated = products.find((p) => p.productCode === productCode);
    expect(updated?.productName).toBe(newName);
  });

  test("B04 — Excluir produto", async ({ request }) => {
    await api(request, `/Produto/${productId}`, { method: "DELETE" });

    const products = await api<Product[]>(request, "/Produto");
    const found = products.find((p) => p.productCode === productCode);
    expect(found, "Produto excluído não deve aparecer na listagem").toBeFalsy();
    productId = "";
  });
});
