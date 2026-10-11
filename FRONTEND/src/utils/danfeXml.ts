/** Retrato do documento fiscal: o DANFE não consulta cadastro nem refaz cálculos tributários. */
export type DanfeSnapshot = {
  chave: string; numero: string; serie: string; ambiente: string; emitidoEm: string;
  contingencia: boolean; autorizado: boolean; protocolo: string | null; autorizadoEm: string | null;
  emitente: { nome: string; fantasia: string; cnpj: string; endereco: string };
  consumidor: { tipo: string; documento: string; nome: string; endereco: string } | null;
  itens: { codigo: string; descricao: string; quantidade: number; unidade: string; unitario: number; total: number }[];
  total: number; desconto: number; frete: number; seguro: number; outros: number; pagar: number;
  pagamentos: { forma: string; valor: number }[]; troco: number; tributos: number | null;
  qrCode: string; consulta: string; mensagens: string[];
};

const PAGAMENTOS: Record<string, string> = {
  "01": "Dinheiro", "02": "Cheque", "03": "Cartão de crédito", "04": "Cartão de débito",
  "05": "Crédito loja", "10": "Vale alimentação", "11": "Vale refeição", "12": "Vale presente",
  "13": "Vale combustível", "15": "Boleto bancário", "16": "Depósito bancário", "17": "PIX",
  "18": "Transferência bancária", "19": "Programa de fidelidade", "20": "PIX estático",
  "21": "Crédito em loja", "22": "Pagamento eletrônico não informado", "90": "Sem pagamento", "99": "Outros",
};
const child = (parent: Element | undefined | null, tag: string): Element | undefined =>
  parent ? Array.from(parent.children).find(e => e.localName === tag) : undefined;
const text = (parent: Element | undefined | null, tag: string) => child(parent, tag)?.textContent?.trim() ?? "";
function amount(parent: Element | undefined | null, tag: string, optional = false): number {
  const value = text(parent, tag);
  if (!value && optional) return 0;
  if (!/^\d+(?:\.\d+)?$/.test(value) || !Number.isFinite(Number(value)))
    throw new Error(`O documento recebido contém ${tag} inválido. Consulte a administração.`);
  return Number(value);
}
function address(parent: Element | undefined) {
  if (!parent) return "";
  return [text(parent, "xLgr"), text(parent, "nro"), text(parent, "xCpl"), text(parent, "xBairro"),
    [text(parent, "xMun"), text(parent, "UF")].filter(Boolean).join(" - "), text(parent, "CEP")]
    .filter(Boolean).join(", ");
}
function consultation(value: string, required: boolean) {
  if (!value && !required) return "";
  try {
    const url = new URL(value);
    if ((url.protocol !== "https:" && url.protocol !== "http:") || url.username || url.password) throw new Error();
    return value;
  } catch { throw new Error("O documento recebido não contém o endereço fiscal de consulta válido. Consulte a administração."); }
}
function date(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(value) || !Number.isFinite(Date.parse(value)))
    throw new Error("O documento recebido não contém a data fiscal válida. Consulte a administração.");
  return value;
}

export function parseDanfeXml(xml: string | null | undefined): DanfeSnapshot {
  if (!xml || xml.length > 5 * 1024 * 1024 || /<!DOCTYPE|<!ENTITY/i.test(xml))
    throw new Error("Não foi possível preparar o documento de impressão. Consulte a administração e tente novamente.");
  const document = new DOMParser().parseFromString(xml, "application/xml");
  if (document.getElementsByTagName("parsererror").length) throw new Error("O documento recebido está incompleto. Tente imprimir novamente.");
  const root = document.documentElement;
  const nfe = root.localName === "NFe" ? root : root.localName === "nfeProc" ? child(root, "NFe") : undefined;
  const info = child(nfe, "infNFe");
  if (!info || info.namespaceURI !== "http://www.portalfiscal.inf.br/nfe" || info.getAttribute("versao") !== "4.00")
    throw new Error("O documento recebido não é uma NFC-e válida para impressão.");
  const ide = child(info, "ide"), emit = child(info, "emit"), total = child(child(info, "total"), "ICMSTot");
  const chave = (info.getAttribute("Id") ?? "").replace(/^NFe/, "");
  if (text(ide, "mod") !== "65" || !/^\d{44}$/.test(chave)) throw new Error("Este documento exige outro modelo de impressão. Consulte a administração.");
  const contingencia = text(ide, "tpEmis") === "9";
  const infProt = child(child(root, "protNFe"), "infProt");
  const autorizado = !!infProt && ["100", "120", "150"].includes(text(infProt, "cStat")) && text(infProt, "chNFe") === chave;
  if (infProt && !autorizado) throw new Error("O protocolo recebido não autoriza esta NFC-e. Consulte a administração.");
  if (!autorizado && !contingencia) throw new Error("A impressão está sendo preparada. Aguarde e use Imprimir última venda.");
  if (contingencia && !nfe?.getElementsByTagNameNS("http://www.w3.org/2000/09/xmldsig#", "Signature").length)
    throw new Error("O documento de contingência está incompleto. Consulte a administração.");
  const itens = Array.from(info.children).filter(e => e.localName === "det").map(det => {
    const p = child(det, "prod");
    const unidade = text(p, "uCom");
    if (!text(p, "cProd") || !text(p, "xProd") || !unidade) throw new Error("O documento contém um item incompleto. Consulte a administração.");
    return { codigo: text(p, "cProd"), descricao: text(p, "xProd"), quantidade: amount(p, "qCom"), unidade,
      unitario: amount(p, "vUnCom"), total: amount(p, "vProd") };
  });
  if (!itens.length) throw new Error("O documento recebido não contém itens para impressão.");
  const dest = child(info, "dest"), entrega = child(info, "entrega");
  const tipo = text(dest, "CPF") ? "CPF" : text(dest, "CNPJ") ? "CNPJ" : "Id. Estrangeiro";
  const documento = text(dest, "CPF") || text(dest, "CNPJ") || text(dest, "idEstrangeiro");
  const suplementar = child(nfe, "infNFeSupl"), pag = child(info, "pag"), adicional = child(info, "infAdic");
  const alertas = Array.from(infProt?.getElementsByTagNameNS("http://www.portalfiscal.inf.br/nfe", "xMsg") ?? []).map(e => e.textContent?.trim() ?? "");
  const mensagens = [text(adicional, "infAdFisco"), ...alertas, text(adicional, "infCpl")].filter(Boolean);
  const tributos = text(total, "vTotTrib") ? amount(total, "vTotTrib") : null;
  const protocolo = autorizado ? text(infProt, "nProt") : null;
  if (autorizado && !protocolo) throw new Error("O protocolo de impressão está incompleto. Tente novamente.");
  return {
    chave, numero: text(ide, "nNF"), serie: text(ide, "serie"), ambiente: text(ide, "tpAmb"),
    emitidoEm: date(text(ide, "dhEmi")), contingencia, autorizado, protocolo,
    autorizadoEm: autorizado ? date(text(infProt, "dhRecbto")) : null,
    emitente: { nome: text(emit, "xNome"), fantasia: text(emit, "xFant"), cnpj: text(emit, "CNPJ"), endereco: address(child(emit, "enderEmit")) },
    consumidor: documento ? { tipo, documento, nome: text(dest, "xNome"), endereco: address(entrega || child(dest, "enderDest")) } : null,
    itens, total: amount(total, "vProd"), desconto: amount(total, "vDesc", true), frete: amount(total, "vFrete", true),
    seguro: amount(total, "vSeg", true), outros: amount(total, "vOutro", true), pagar: amount(total, "vNF"),
    pagamentos: Array.from(pag?.children ?? []).filter(e => e.localName === "detPag").map(p =>
      ({ forma: text(p, "xPag") || PAGAMENTOS[text(p, "tPag")] || `Pagamento ${text(p, "tPag")}`, valor: amount(p, "vPag") })),
    troco: amount(pag, "vTroco", true), tributos,
    qrCode: consultation(text(suplementar, "qrCode"), true), consulta: consultation(text(suplementar, "urlChave"), true), mensagens,
  };
}

export function formatFiscalDocument(value: string, tipo: string) {
  if (tipo === "CNPJ" && /^\d{14}$/.test(value)) return value.replace(/^(\d{2})(\d{3})(\d{3})(\d{4})(\d{2})$/, "$1.$2.$3/$4-$5");
  if (tipo === "CPF" && /^\d{11}$/.test(value)) return value.replace(/^(\d{3})(\d{3})(\d{3})(\d{2})$/, "$1.$2.$3-$4");
  return value;
}
