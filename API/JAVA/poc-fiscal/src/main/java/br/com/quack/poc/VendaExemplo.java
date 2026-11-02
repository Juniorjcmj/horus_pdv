package br.com.quack.poc;

import br.com.swconsultoria.nfe.dom.ConfiguracoesNfe;
import br.com.swconsultoria.nfe.dom.enuns.DocumentoEnum;
import br.com.swconsultoria.nfe.dom.enuns.EstadosEnum;
import br.com.swconsultoria.nfe.dom.enuns.ServicosEnum;
import br.com.swconsultoria.nfe.exception.NfeException;
import br.com.swconsultoria.nfe.schema_4.enviNFe.ObjectFactory;
import br.com.swconsultoria.nfe.schema_4.enviNFe.TCIBS;
import br.com.swconsultoria.nfe.schema_4.enviNFe.TEnderEmi;
import br.com.swconsultoria.nfe.schema_4.enviNFe.TEnviNFe;
import br.com.swconsultoria.nfe.schema_4.enviNFe.TIBSCBSMonoTot;
import br.com.swconsultoria.nfe.schema_4.enviNFe.TNFe;
import br.com.swconsultoria.nfe.schema_4.enviNFe.TNFe.InfNFe;
import br.com.swconsultoria.nfe.schema_4.enviNFe.TNFe.InfNFe.Det;
import br.com.swconsultoria.nfe.schema_4.enviNFe.TTribNFe;
import br.com.swconsultoria.nfe.schema_4.enviNFe.TUfEmi;
import br.com.swconsultoria.nfe.util.ChaveUtil;
import br.com.swconsultoria.nfe.util.WebServiceUtil;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Venda de exemplo que espelha o que o ZeusFiscalProvider.cs da API .NET envia: emitente do Simples Nacional (CRT 1),
 * dois itens (NCM, CFOP, CSOSN, PIS/COFINS e grupo IBS/CBS com CST e cClassTrib), desconto, consumidor sem CPF e
 * pagamento em dinheiro + PIX. Os valores sao fixos para o teste ser deterministico.
 */
public final class VendaExemplo {

    public static final String CNPJ_EMITENTE = "11222333000181";

    public record Item(String codigo, String descricao, String ncm, String cfop, String unidade, BigDecimal qtd,
                       BigDecimal valorUnitario, BigDecimal desconto, String csosn, String cstIbsCbs, String cClassTrib) {
        BigDecimal bruto() { return qtd.multiply(valorUnitario).setScale(2, RoundingMode.HALF_UP); }

        BigDecimal liquido() { return bruto().subtract(desconto); }
    }

    public final TEnviNFe envio = new ObjectFactory().createTEnviNFe();
    public String chave;
    public BigDecimal valorNota;

    /** nNF e cNF fixos; tpEmis 1 (normal) ou 9 (contingencia offline). */
    public static VendaExemplo montar(ConfiguracoesNfe config, int numero, boolean contingenciaOffline, String cnpj) throws NfeException {
        VendaExemplo v = new VendaExemplo();
        EstadosEnum uf = config.getEstado();
        ZonedDateTime agora = ZonedDateTime.now(ZoneId.of("America/Sao_Paulo")).withNano(0);
        String tpEmis = contingenciaOffline ? "9" : "1";

        List<Item> itens = List.of(
                new Item("7891000100103", "LEITE INTEGRAL 1L", "04012010", "5102", "UN", new BigDecimal("3"),
                        new BigDecimal("5.49"), new BigDecimal("1.00"), "102", "000", "000001"),
                new Item("7894900011517", "REFRIGERANTE COLA 2L", "22021000", "5405", "UN", new BigDecimal("2"),
                        new BigDecimal("9.90"), BigDecimal.ZERO.setScale(2), "500", "000", "000001"));
        BigDecimal totalProdutos = itens.stream().map(Item::bruto).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalDesconto = itens.stream().map(Item::desconto).reduce(BigDecimal.ZERO, BigDecimal::add);
        v.valorNota = totalProdutos.subtract(totalDesconto);

        ChaveUtil chaveUtil = new ChaveUtil(uf, cnpj, "65", 1, numero, tpEmis, "12345678",
                LocalDateTime.from(agora));
        v.chave = chaveUtil.getChaveNF().replaceFirst("^NFe", "");

        TEnviNFe envi = v.envio;
        envi.setVersao("4.00");
        envi.setIdLote("1");
        envi.setIndSinc("1");

        TNFe nfe = new TNFe();
        InfNFe inf = new InfNFe();
        inf.setId("NFe" + v.chave);
        inf.setVersao("4.00");

        InfNFe.Ide ide = new InfNFe.Ide();
        ide.setCUF(uf.getCodigoUF());
        ide.setCNF("12345678");
        ide.setNatOp("VENDA AO CONSUMIDOR");
        ide.setMod("65");
        ide.setSerie("1");
        ide.setNNF(String.valueOf(numero));
        ide.setDhEmi(agora.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")));
        ide.setTpNF("1");
        ide.setIdDest("1");
        ide.setCMunFG("3550308");
        ide.setTpImp("4");
        ide.setTpEmis(tpEmis);
        ide.setCDV(chaveUtil.getDigitoVerificador());
        ide.setTpAmb(config.getAmbiente().getCodigo());
        ide.setFinNFe("1");
        ide.setIndFinal("1");
        ide.setIndPres("1");
        ide.setProcEmi("0");
        ide.setVerProc("QUACK-POC-1.0");
        if (contingenciaOffline) {
            ide.setDhCont(agora.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")));
            ide.setXJust("Contingencia offline: SEFAZ indisponivel no momento da venda");
        }
        inf.setIde(ide);

        InfNFe.Emit emit = new InfNFe.Emit();
        emit.setCNPJ(cnpj);
        emit.setXNome("QUACK PDV COMERCIO LTDA");
        emit.setXFant("QUACK PDV");
        TEnderEmi end = new TEnderEmi();
        end.setXLgr("AVENIDA PAULISTA");
        end.setNro("1578");
        end.setXBairro("BELA VISTA");
        end.setCMun("3550308");
        end.setXMun("SAO PAULO");
        end.setUF(TUfEmi.SP);
        end.setCEP("01310200");
        end.setCPais("1058");
        end.setXPais("BRASIL");
        emit.setEnderEmit(end);
        emit.setIE("111222333444");
        emit.setCRT("1");
        inf.setEmit(emit);

        // consumidor sem CPF: nao ha grupo dest

        ObjectFactory of = new ObjectFactory();
        BigDecimal totalIbs = BigDecimal.ZERO;
        BigDecimal totalCbs = BigDecimal.ZERO;
        BigDecimal baseIbsCbs = BigDecimal.ZERO;
        int n = 1;
        for (Item item : itens) {
            Det det = new Det();
            det.setNItem(String.valueOf(n++));

            Det.Prod prod = new Det.Prod();
            prod.setCProd(item.codigo());
            prod.setCEAN(item.codigo());
            prod.setXProd(item.descricao());
            prod.setNCM(item.ncm());
            prod.setCFOP(item.cfop());
            prod.setUCom(item.unidade());
            prod.setQCom(dec(item.qtd(), 4));
            prod.setVUnCom(dec(item.valorUnitario(), 10));
            prod.setVProd(dec(item.bruto(), 2));
            prod.setCEANTrib(item.codigo());
            prod.setUTrib(item.unidade());
            prod.setQTrib(dec(item.qtd(), 4));
            prod.setVUnTrib(dec(item.valorUnitario(), 10));
            if (item.desconto().signum() > 0) prod.setVDesc(dec(item.desconto(), 2));
            prod.setIndTot("1");
            det.setProd(prod);

            Det.Imposto imposto = new Det.Imposto();
            Det.Imposto.ICMS icms = new Det.Imposto.ICMS();
            if ("102".equals(item.csosn())) {
                Det.Imposto.ICMS.ICMSSN102 sn = new Det.Imposto.ICMS.ICMSSN102();
                sn.setOrig("0");
                sn.setCSOSN("102");
                icms.setICMSSN102(sn);
            } else {
                Det.Imposto.ICMS.ICMSSN500 sn = new Det.Imposto.ICMS.ICMSSN500();
                sn.setOrig("0");
                sn.setCSOSN("500");
                icms.setICMSSN500(sn);
            }
            imposto.getContent().add(of.createTNFeInfNFeDetImpostoICMS(icms));

            Det.Imposto.PIS pis = new Det.Imposto.PIS();
            Det.Imposto.PIS.PISNT pisNt = new Det.Imposto.PIS.PISNT();
            pisNt.setCST("07");
            pis.setPISNT(pisNt);
            imposto.getContent().add(of.createTNFeInfNFeDetImpostoPIS(pis));

            Det.Imposto.COFINS cofins = new Det.Imposto.COFINS();
            Det.Imposto.COFINS.COFINSNT cofinsNt = new Det.Imposto.COFINS.COFINSNT();
            cofinsNt.setCST("07");
            cofins.setCOFINSNT(cofinsNt);
            imposto.getContent().add(of.createTNFeInfNFeDetImpostoCOFINS(cofins));

            // Reforma tributaria: CST IBS/CBS e cClassTrib do cadastro do produto + grupo gIBSCBS
            BigDecimal bc = item.liquido();
            BigDecimal ibsUf = bc.multiply(new BigDecimal("0.0010")).setScale(2, RoundingMode.HALF_UP);
            BigDecimal ibsMun = BigDecimal.ZERO.setScale(2);
            BigDecimal cbs = bc.multiply(new BigDecimal("0.0090")).setScale(2, RoundingMode.HALF_UP);
            TTribNFe trib = new TTribNFe();
            trib.setCST(item.cstIbsCbs());
            trib.setCClassTrib(item.cClassTrib());
            TCIBS g = new TCIBS();
            g.setVBC(dec(bc, 2));
            TCIBS.GIBSUF gUf = new TCIBS.GIBSUF();
            gUf.setPIBSUF("0.1000");
            gUf.setVIBSUF(dec(ibsUf, 2));
            g.setGIBSUF(gUf);
            TCIBS.GIBSMun gMun = new TCIBS.GIBSMun();
            gMun.setPIBSMun("0.0000");
            gMun.setVIBSMun(dec(ibsMun, 2));
            g.setGIBSMun(gMun);
            g.setVIBS(dec(ibsUf.add(ibsMun), 2));
            TCIBS.GCBS gCbs = new TCIBS.GCBS();
            gCbs.setPCBS("0.9000");
            gCbs.setVCBS(dec(cbs, 2));
            g.setGCBS(gCbs);
            trib.setGIBSCBS(g);
            imposto.getContent().add(of.createTNFeInfNFeDetImpostoIBSCBS(trib));
            det.setImposto(imposto);
            inf.getDet().add(det);

            baseIbsCbs = baseIbsCbs.add(bc);
            totalIbs = totalIbs.add(ibsUf.add(ibsMun));
            totalCbs = totalCbs.add(cbs);
        }

        InfNFe.Total total = new InfNFe.Total();
        InfNFe.Total.ICMSTot icmsTot = new InfNFe.Total.ICMSTot();
        icmsTot.setVBC("0.00");
        icmsTot.setVICMS("0.00");
        icmsTot.setVICMSDeson("0.00");
        icmsTot.setVFCP("0.00");
        icmsTot.setVBCST("0.00");
        icmsTot.setVST("0.00");
        icmsTot.setVFCPST("0.00");
        icmsTot.setVFCPSTRet("0.00");
        icmsTot.setVProd(dec(totalProdutos, 2));
        icmsTot.setVFrete("0.00");
        icmsTot.setVSeg("0.00");
        icmsTot.setVDesc(dec(totalDesconto, 2));
        icmsTot.setVII("0.00");
        icmsTot.setVIPI("0.00");
        icmsTot.setVIPIDevol("0.00");
        icmsTot.setVPIS("0.00");
        icmsTot.setVCOFINS("0.00");
        icmsTot.setVOutro("0.00");
        icmsTot.setVNF(dec(v.valorNota, 2));
        total.setICMSTot(icmsTot);

        // Em 2026 IBS/CBS nao compoem o total do documento (mesma regra do ZeusFiscalProvider)
        TIBSCBSMonoTot ibsTot = new TIBSCBSMonoTot();
        ibsTot.setVBCIBSCBS(dec(baseIbsCbs, 2));
        TIBSCBSMonoTot.GIBS gibs = new TIBSCBSMonoTot.GIBS();
        TIBSCBSMonoTot.GIBS.GIBSUF totUf = new TIBSCBSMonoTot.GIBS.GIBSUF();
        totUf.setVDif("0.00");
        totUf.setVDevTrib("0.00");
        totUf.setVIBSUF(dec(totalIbs, 2));
        gibs.setGIBSUF(totUf);
        TIBSCBSMonoTot.GIBS.GIBSMun totMun = new TIBSCBSMonoTot.GIBS.GIBSMun();
        totMun.setVDif("0.00");
        totMun.setVDevTrib("0.00");
        totMun.setVIBSMun("0.00");
        gibs.setGIBSMun(totMun);
        gibs.setVIBS(dec(totalIbs, 2));
        gibs.setVCredPres("0.00");
        gibs.setVCredPresCondSus("0.00");
        ibsTot.setGIBS(gibs);
        TIBSCBSMonoTot.GCBS gcbs = new TIBSCBSMonoTot.GCBS();
        gcbs.setVDif("0.00");
        gcbs.setVDevTrib("0.00");
        gcbs.setVCBS(dec(totalCbs, 2));
        gcbs.setVCredPres("0.00");
        gcbs.setVCredPresCondSus("0.00");
        ibsTot.setGCBS(gcbs);
        total.setIBSCBSTot(ibsTot);
        inf.setTotal(total);

        InfNFe.Transp transp = new InfNFe.Transp();
        transp.setModFrete("9");
        inf.setTransp(transp);

        // pagamento: dinheiro (01) com troco + PIX (17)
        BigDecimal pagoDinheiro = new BigDecimal("20.00");
        BigDecimal pagoPix = v.valorNota.subtract(new BigDecimal("15.00"));
        BigDecimal troco = pagoDinheiro.add(pagoPix).subtract(v.valorNota);
        InfNFe.Pag pag = new InfNFe.Pag();
        InfNFe.Pag.DetPag dinheiro = new InfNFe.Pag.DetPag();
        dinheiro.setIndPag("0");
        dinheiro.setTPag("01");
        dinheiro.setVPag(dec(pagoDinheiro, 2));
        pag.getDetPag().add(dinheiro);
        InfNFe.Pag.DetPag pix = new InfNFe.Pag.DetPag();
        pix.setIndPag("0");
        pix.setTPag("17");
        pix.setVPag(dec(pagoPix, 2));
        pag.getDetPag().add(pix);
        pag.setVTroco(dec(troco, 2));
        inf.setPag(pag);

        InfNFe.InfAdic adic = new InfNFe.InfAdic();
        adic.setInfCpl("PoC fiscal Quack PDV - ambiente de teste");
        inf.setInfAdic(adic);

        nfe.setInfNFe(inf);
        envi.getNFe().add(nfe);
        return v;
    }

    /** URL base do QR Code e da consulta por chave da UF/ambiente da configuracao. */
    public static String urlQrCode(ConfiguracoesNfe config) throws NfeException {
        return WebServiceUtil.getUrl(config, DocumentoEnum.NFCE, ServicosEnum.URL_QRCODE);
    }

    public static String urlConsulta(ConfiguracoesNfe config) throws NfeException {
        return WebServiceUtil.getUrl(config, DocumentoEnum.NFCE, ServicosEnum.URL_CONSULTANFCE);
    }

    private static String dec(BigDecimal v, int casas) {
        return v.setScale(casas, RoundingMode.HALF_UP).toPlainString();
    }
}
