import { test, expect } from "./browser";
import { accessKey, CNPJ, CONSULTATION, fiscalDetail, fiscalXml, PROTOCOL, QR_BASE, saleReceipt } from "./fixtures";

test("snapshot preserva KG fracionado, desconto, total fiscal, pagamento e troco do XML", async ({ page }) => {
  const result = await page.evaluate(async xml => {
    const { parseDanfeXml } = await import("/src/utils/danfeXml.ts" as string);
    return parseDanfeXml(xml);
  }, fiscalXml());

  expect(result).toMatchObject({
    chave: accessKey(), numero: "123", serie: "1", ambiente: "2", emitidoEm: "2026-10-10T14:15:16-03:00",
    contingencia: false, autorizado: true, protocolo: PROTOCOL, autorizadoEm: "2026-10-10T14:15:20-03:00",
    emitente: { nome: "Emitente do XML & Filhos", cnpj: CNPJ, endereco: "Rua Fiscal, 10, Centro, Rio de Janeiro - RJ, 20000000" },
    consumidor: { tipo: "CPF", documento: "11122233344", nome: "Cliente XML", endereco: "Rua Entrega, 30, Centro, Rio de Janeiro - RJ" },
    itens: [{ codigo: "P-XML", descricao: "Café do XML <Especial>", quantidade: 0.5, unidade: "KG", unitario: 20, total: 10 }],
    total: 10, desconto: 2, pagar: 8, pagamentos: [{ forma: "Dinheiro", valor: 10 }], troco: 2,
    tributos: null, consulta: CONSULTATION,
  });
  expect(result.qrCode).toBe(`${QR_BASE}?p=${accessKey()}|2|2|000001|FISCAL-ORIGINAL`);
});

test("impressão usa exclusivamente dados fiscais e o QR original, apesar de cadastro e DTO divergentes", async ({ page }) => {
  const result = await page.evaluate(async ({ receipt, detail }) => {
    const { buildDanfePrintHtml, generateQrCodeSvg } = await import("/src/utils/danfePrint.ts" as string);
    const { parseDanfeXml } = await import("/src/utils/danfeXml.ts" as string);
    const money = (value: number) => value.toLocaleString("pt-BR", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    const html = buildDanfePrintHtml(receipt, detail, money);
    const document = new DOMParser().parseFromString(html, "text/html");
    return {
      text: document.body.textContent,
      rows: Array.from(document.querySelectorAll(".line")).map(row => Array.from(row.children).map(cell => cell.textContent)),
      item: document.querySelector(".item")?.textContent,
      qr: document.querySelector(".qr-box")?.innerHTML,
      originalQr: generateQrCodeSvg(parseDanfeXml(detail.xmlDocumento).qrCode),
      injectedElement: !!document.querySelector("Especial"),
    };
  }, { receipt: saleReceipt(), detail: fiscalDetail() });

  expect(result.item).toContain("0,5 KG × R$ 20,00");
  expect(result.rows).toEqual(expect.arrayContaining([
    ["Valor Total", "R$ 10,00"], ["Desconto", "R$ 2,00"], ["Valor a Pagar", "R$ 8,00"], ["Dinheiro", "R$ 10,00"], ["Troco", "R$ 2,00"],
  ]));
  expect(result.text).toContain("Café do XML <Especial>");
  expect(result.text).toContain("NFC-e nº 000.000.123 Série 1");
  expect(result.text).toContain(`Protocolo de autorização: ${PROTOCOL}`);
  expect(result.text).toContain("10/10/2026");
  expect(result.text).toContain("SEM VALOR FISCAL");
  expect(result.text).not.toMatch(/cadastro|999|2040|PROTOCOLO-DTO|Tributos totais/i);
  expect(result.qr).toBe(result.originalQr);
  expect(result.injectedElement).toBe(false);
});

test("cStat 120 mantém todos os xMsg de autorização e mensagens complementares", async ({ page }) => {
  const xml = fiscalXml({ cStat: "120", messages: ["Alerta fiscal A", "Alerta fiscal B"] });
  const result = await page.evaluate(async ({ xml, receipt, detail }) => {
    const { parseDanfeXml } = await import("/src/utils/danfeXml.ts" as string);
    const { buildDanfePrintHtml } = await import("/src/utils/danfePrint.ts" as string);
    const snapshot = parseDanfeXml(xml);
    const printed = new DOMParser().parseFromString(buildDanfePrintHtml(receipt, detail, (n: number) => n.toFixed(2)), "text/html");
    return { authorized: snapshot.autorizado, messages: snapshot.mensagens, printedMessages: Array.from(printed.querySelectorAll(".message")).map(el => el.textContent) };
  }, { xml, receipt: saleReceipt(), detail: fiscalDetail(xml) });
  const messages = ["Mensagem fiscal original", "Alerta fiscal A", "Alerta fiscal B", "Informação complementar original"];
  expect(result.authorized).toBe(true);
  expect(result.messages).toEqual(messages);
  expect(result.printedMessages).toEqual(messages);
});

test("contingência original assinada com QR imprime sem fabricar protocolo ou data de autorização", async ({ page }) => {
  const xml = fiscalXml({ contingency: true, authorized: false });
  const result = await page.evaluate(async ({ receipt, detail }) => {
    const { parseDanfeXml } = await import("/src/utils/danfeXml.ts" as string);
    const { buildDanfePrintHtml } = await import("/src/utils/danfePrint.ts" as string);
    const snapshot = parseDanfeXml(detail.xmlDocumento);
    const printed = new DOMParser().parseFromString(buildDanfePrintHtml(receipt, detail, (n: number) => n.toFixed(2)), "text/html");
    return { snapshot, text: printed.body.textContent, qrCount: printed.querySelectorAll(".qr svg").length, warnings: Array.from(printed.querySelectorAll(".warning")).map(el => el.textContent) };
  }, { receipt: saleReceipt(), detail: fiscalDetail(xml, { status: 8, chaveAcesso: accessKey("9") }) });
  expect(result.snapshot).toMatchObject({ contingencia: true, autorizado: false, protocolo: null, autorizadoEm: null });
  expect(result.qrCount).toBe(1);
  expect(result.warnings.filter((text: string) => text.includes("EMITIDA EM CONTINGÊNCIA"))).toHaveLength(2);
  expect(result.text).toContain("Pendente de autorização");
  expect(result.text).not.toMatch(/Protocolo de autorização|Data de Autorização|PROTOCOLO-DTO/);
});

for (const scenario of [
  { name: "sem assinatura", xml: fiscalXml({ contingency: true, authorized: false, signed: false }) },
  { name: "sem QR fiscal", xml: fiscalXml({ contingency: true, authorized: false, qr: false }) },
  { name: "com QR javascript", xml: fiscalXml({ contingency: true, authorized: false }).replace(/<qrCode>[\s\S]*?<\/qrCode>/, "<qrCode><![CDATA[javascript:alert(1)]]></qrCode>") },
]) {
  test(`contingência ${scenario.name} é recusada antes de abrir impressão`, async ({ page }) => {
    const result = await page.evaluate(async ({ receipt, detail }) => {
      const { openDanfePrintWindow } = await import("/src/utils/danfePrint.ts" as string);
      let popups = 0;
      window.open = () => { popups++; return null; };
      try { openDanfePrintWindow(receipt, detail, (n: number) => n.toFixed(2)); return { error: "", popups }; }
      catch (error) { return { error: String(error), popups }; }
    }, { receipt: saleReceipt(), detail: fiscalDetail(scenario.xml, { status: 8, chaveAcesso: accessKey("9") }) });
    expect(result.error).not.toBe("");
    expect(result.popups).toBe(0);
  });
}

for (const scenario of [
  { name: "XML ausente", xml: "" },
  { name: "XML malformado", xml: "<NFe><infNFe>" },
  { name: "modelo 55", xml: fiscalXml().replace("<mod>65</mod>", "<mod>55</mod>") },
  { name: "protocolo de outra chave", xml: fiscalXml({ protocolKey: "9".repeat(44) }) },
  { name: "protocolo divergente em contingência", xml: fiscalXml({ contingency: true, protocolKey: "9".repeat(44) }), key: accessKey("9") },
  { name: "total vNF ausente", xml: fiscalXml().replace("<vNF>8.00</vNF>", "") },
  { name: "quantidade inválida", xml: fiscalXml().replace("<qCom>0.5000</qCom>", "<qCom>meio</qCom>") },
  { name: "sem protocolo autorizado", xml: fiscalXml().replace(`<nProt>${PROTOCOL}</nProt>`, "") },
  { name: "DTD e entidade", xml: `<!DOCTYPE nfeProc [<!ENTITY total "8.00">]>${fiscalXml()}` },
]) {
  test(`DANFE recusa ${scenario.name} e nunca preenche a falha com dados da venda`, async ({ page }) => {
    const error = await page.evaluate(async ({ receipt, detail }) => {
      const { buildDanfePrintHtml } = await import("/src/utils/danfePrint.ts" as string);
      try { buildDanfePrintHtml(receipt, detail, (n: number) => n.toFixed(2)); return ""; }
      catch (error) { return String(error); }
    }, { receipt: saleReceipt(), detail: fiscalDetail(scenario.xml, { chaveAcesso: scenario.key ?? accessKey() }) });
    expect(error).not.toBe("");
  });
}

test("DTO autorizado não torna um XML pendente autorizado", async ({ page }) => {
  const error = await page.evaluate(async ({ receipt, detail }) => {
    const { buildDanfePrintHtml } = await import("/src/utils/danfePrint.ts" as string);
    try { buildDanfePrintHtml(receipt, detail, (n: number) => n.toFixed(2)); return ""; }
    catch (error) { return String(error); }
  }, { receipt: saleReceipt(), detail: fiscalDetail(fiscalXml({ contingency: true, authorized: false }), { chaveAcesso: accessKey("9"), status: 3 }) });
  expect(error).not.toBe("");
});

test("empresa e chave de venda divergentes impedem impressão", async ({ page }) => {
  const errors = await page.evaluate(async ({ receipt, detail }) => {
    const { buildDanfePrintHtml } = await import("/src/utils/danfePrint.ts" as string);
    return [
      () => buildDanfePrintHtml({ ...receipt, company: { ...receipt.company, cnpj: "98765432000198" } }, detail, String),
      () => buildDanfePrintHtml(receipt, { ...detail, chaveAcesso: "9".repeat(44) }, String),
      () => buildDanfePrintHtml(receipt, { ...detail, status: 6 }, String),
    ].map(run => { try { run(); return ""; } catch (error) { return String(error); } });
  }, { receipt: saleReceipt(), detail: fiscalDetail() });
  expect(errors).toHaveLength(3);
  expect(errors.every(error => !!error)).toBe(true);
});

test("consumidor e tributos ausentes permanecem ausentes mesmo quando o recibo contém CPF", async ({ page }) => {
  const printed = await page.evaluate(async ({ receipt, detail }) => {
    const { buildDanfePrintHtml } = await import("/src/utils/danfePrint.ts" as string);
    return new DOMParser().parseFromString(buildDanfePrintHtml(receipt, detail, String), "text/html").body.textContent;
  }, { receipt: saleReceipt(), detail: fiscalDetail(fiscalXml({ consumer: false })) });
  expect(printed).toContain("CONSUMIDOR NÃO IDENTIFICADO");
  expect(printed).not.toContain("99999999999");
  expect(printed).not.toContain("Tributos totais");
});
