import type { SaleReceipt } from "../../src/components/Admin/ReceiptPreviewModal";
import type { FiscalDocumentDetailDto } from "../../src/services/api/fiscalService";

export const CNPJ = "12345678000195";
export const PROTOCOL = "133260000000123";
export const QR_BASE = "https://consulta.exemplo.invalid/nfce/qrcode";
export const CONSULTATION = "https://consulta.exemplo.invalid/nfce/consulta";

export function accessKey(tpEmis = "1") {
  const base = `332610${CNPJ}65001000000123${tpEmis}12345678`;
  let sum = 0, weight = 2;
  for (let index = base.length - 1; index >= 0; index--) {
    sum += Number(base[index]) * weight;
    weight = weight === 9 ? 2 : weight + 1;
  }
  const digit = 11 - sum % 11;
  return base + (digit >= 10 ? 0 : digit);
}

type XmlOptions = {
  contingency?: boolean;
  authorized?: boolean;
  signed?: boolean;
  qr?: boolean;
  cStat?: string;
  protocolKey?: string;
  messages?: string[];
  consumer?: boolean;
};

/** Synthetic document: browser tests check structure and rendering, never sign or send it to SEFAZ. */
export function fiscalXml(options: XmlOptions = {}) {
  const tpEmis = options.contingency ? "9" : "1";
  const key = accessKey(tpEmis);
  const qr = `${QR_BASE}?p=${key}|2|2|000001|FISCAL-ORIGINAL`;
  const signature = options.signed === false ? "" : `<Signature xmlns="http://www.w3.org/2000/09/xmldsig#"><SignedInfo><CanonicalizationMethod Algorithm="http://www.w3.org/TR/2001/REC-xml-c14n-20010315"/><SignatureMethod Algorithm="http://www.w3.org/2000/09/xmldsig#rsa-sha1"/><Reference URI="#NFe${key}"><Transforms><Transform Algorithm="http://www.w3.org/2000/09/xmldsig#enveloped-signature"/><Transform Algorithm="http://www.w3.org/TR/2001/REC-xml-c14n-20010315"/></Transforms><DigestMethod Algorithm="http://www.w3.org/2000/09/xmldsig#sha1"/><DigestValue>dGVzdC1kaWdlc3Q=</DigestValue></Reference></SignedInfo><SignatureValue>dGVzdC1zaWduYXR1cmU=</SignatureValue><KeyInfo><X509Data><X509Certificate>dGVzdC1jZXJ0aWZpY2F0ZQ==</X509Certificate></X509Data></KeyInfo></Signature>`;
  const nfe = `<NFe xmlns="http://www.portalfiscal.inf.br/nfe"><infNFe versao="4.00" Id="NFe${key}">
    <ide><cUF>33</cUF><cNF>12345678</cNF><natOp>VENDA</natOp><mod>65</mod><serie>1</serie><nNF>123</nNF><dhEmi>2026-10-10T14:15:16-03:00</dhEmi><tpNF>1</tpNF><idDest>1</idDest><cMunFG>3304557</cMunFG><tpImp>4</tpImp><tpEmis>${tpEmis}</tpEmis><cDV>${key.slice(-1)}</cDV><tpAmb>2</tpAmb><finNFe>1</finNFe><indFinal>1</indFinal><indPres>1</indPres><procEmi>0</procEmi><verProc>TEST</verProc>${options.contingency ? "<dhCont>2026-10-10T14:15:16-03:00</dhCont><xJust>Falha de comunicação em teste isolado.</xJust>" : ""}</ide>
    <emit><CNPJ>${CNPJ}</CNPJ><xNome>Emitente do XML &amp; Filhos</xNome><xFant>Loja fiscal original</xFant><enderEmit><xLgr>Rua Fiscal</xLgr><nro>10</nro><xBairro>Centro</xBairro><cMun>3304557</cMun><xMun>Rio de Janeiro</xMun><UF>RJ</UF><CEP>20000000</CEP></enderEmit><IE>12345678</IE><CRT>1</CRT></emit>
    ${options.consumer === false ? "" : "<dest><CPF>11122233344</CPF><xNome>Cliente XML</xNome><enderDest><xLgr>Rua Destinatário</xLgr><nro>20</nro><xBairro>Centro</xBairro><xMun>Rio de Janeiro</xMun><UF>RJ</UF></enderDest><indIEDest>9</indIEDest></dest><entrega><CPF>11122233344</CPF><xLgr>Rua Entrega</xLgr><nro>30</nro><xBairro>Centro</xBairro><xMun>Rio de Janeiro</xMun><UF>RJ</UF></entrega>"}
    <det nItem="1"><prod><cProd>P-XML</cProd><cEAN>SEM GTIN</cEAN><xProd>Café do XML &lt;Especial&gt;</xProd><NCM>09012100</NCM><CFOP>5102</CFOP><uCom>KG</uCom><qCom>0.5000</qCom><vUnCom>20.0000000000</vUnCom><vProd>10.00</vProd><cEANTrib>SEM GTIN</cEANTrib><uTrib>KG</uTrib><qTrib>0.5000</qTrib><vUnTrib>20.0000000000</vUnTrib><vDesc>2.00</vDesc><indTot>1</indTot></prod><imposto><ICMS><ICMSSN102><orig>0</orig><CSOSN>102</CSOSN></ICMSSN102></ICMS></imposto></det>
    <total><ICMSTot><vBC>0.00</vBC><vICMS>0.00</vICMS><vICMSDeson>0.00</vICMSDeson><vFCP>0.00</vFCP><vBCST>0.00</vBCST><vST>0.00</vST><vFCPST>0.00</vFCPST><vFCPSTRet>0.00</vFCPSTRet><vProd>10.00</vProd><vFrete>0.00</vFrete><vSeg>0.00</vSeg><vDesc>2.00</vDesc><vII>0.00</vII><vIPI>0.00</vIPI><vIPIDevol>0.00</vIPIDevol><vPIS>0.00</vPIS><vCOFINS>0.00</vCOFINS><vOutro>0.00</vOutro><vNF>8.00</vNF></ICMSTot></total><transp><modFrete>9</modFrete></transp>
    <pag><detPag><tPag>01</tPag><vPag>10.00</vPag></detPag><vTroco>2.00</vTroco></pag><infAdic><infAdFisco>Mensagem fiscal original</infAdFisco><infCpl>Informação complementar original</infCpl></infAdic>
  </infNFe><infNFeSupl>${options.qr === false ? "" : `<qrCode><![CDATA[${qr}]]></qrCode>`}<urlChave>${CONSULTATION}</urlChave></infNFeSupl>${signature}</NFe>`;
  if (options.authorized === false) return nfe;
  const messages = (options.messages ?? []).map((message, index) => `<msgs><cMsg>${index + 1}</cMsg><xMsg>${message}</xMsg></msgs>`).join("");
  return `<nfeProc xmlns="http://www.portalfiscal.inf.br/nfe" versao="4.00">${nfe}<protNFe versao="4.00"><infProt><tpAmb>2</tpAmb><verAplic>TEST</verAplic><chNFe>${options.protocolKey ?? key}</chNFe><dhRecbto>2026-10-10T14:15:20-03:00</dhRecbto><nProt>${PROTOCOL}</nProt><digVal>dGVzdC1kaWdlc3Q=</digVal><cStat>${options.cStat ?? "100"}</cStat><xMotivo>Autorizado o uso da NF-e</xMotivo>${messages}</infProt></protNFe></nfeProc>`;
}

export function saleReceipt(overrides: Partial<SaleReceipt> = {}): SaleReceipt {
  return {
    saleNumber: "VENDA-LOCAL", issuedAt: "2040-01-01T01:01:01Z",
    company: { corporateName: "Cadastro adulterado", fantasyName: "Loja de cadastro", cnpj: CNPJ, address: "Rua Cadastro" },
    customerCpf: "99999999999", paymentType: "pix", paymentLabel: "Pagamento do cadastro", operatorName: "Operador cadastro",
    subtotal: 999, cashGiven: 1000, change: 1,
    items: [{ id: "catalog", code: "P-CADASTRO", name: "Produto do cadastro", quantity: 999, unitPrice: 999, total: 999 }],
    payments: [{ paymentType: "pix", paymentLabel: "PIX do cadastro", amount: 999 }],
    ...overrides,
  };
}

export function fiscalDetail(xml = fiscalXml(), overrides: Partial<FiscalDocumentDetailDto> = {}): FiscalDocumentDetailDto {
  return {
    id: "fiscal-test", saleNumber: "VENDA-LOCAL", serie: 99, numeroNf: 999, status: 3, chaveAcesso: accessKey(),
    protocolo: "PROTOCOLO-DTO-ADULTERADO", motivoStatus: null, dhAutorizacao: "2040-01-01T01:01:01Z",
    criadoEm: "2040-01-01T01:01:01Z", tentativas: 0, qrCodeUrl: "https://cadastro.exemplo.invalid/qr-fabricado", xmlDocumento: xml,
    ...overrides,
  };
}
