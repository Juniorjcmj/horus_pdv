package br.com.quack.estoque;

import static br.com.quack.estoque.E2eSupport.falha;
import static br.com.quack.estoque.E2eSupport.log;
import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.api.ApiException;
import br.com.quack.estoque.domain.ConversaoUnidade;
import br.com.quack.estoque.domain.Fmt;
import br.com.quack.estoque.domain.Produto;
import br.com.quack.estoque.persistence.JsonFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Entradas de mercadoria pela camada de serviço do app: entrada manual (conversão, idempotência), estorno e NF-e por XML. */
@EnabledIfSystemProperty(named = "quack.e2e.url", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EntradasE2ETest {
    @TempDir
    static Path home;
    @TempDir
    static Path homeEstoquista;

    E2eSupport e;
    Produto produto;
    String operacaoId = UUID.randomUUID().toString();
    String notaManualId;

    @BeforeAll
    void preparar() {
        e = new E2eSupport(home);
        e.loginAdmin();
        produto = e.criarProduto("Ent Cx12", "ENT", "10", "4,00", "9,00", null, "0", "0");
    }

    private ObjectNode entradaManual() {
        ObjectNode req = JsonFiles.MAPPER.createObjectNode();
        req.put("operacaoId", operacaoId);
        req.put("dataCompra", java.time.LocalDate.now().toString());
        req.put("tipoDocumento", "cupom");
        req.put("numeroDocumento", "CP-" + e.sufixo);
        req.put("observacao", "compra no atacarejo (teste)");
        ArrayNode itens = req.putArray("itens");
        ObjectNode i = itens.addObject();
        BigDecimal qtd = new BigDecimal("5");
        BigDecimal fator = new BigDecimal("12");
        BigDecimal custoCaixa = new BigDecimal("48.00");
        i.put("numeroItem", 1);
        i.put("produtoExistenteId", produto.id());
        i.put("produtoExistenteNome", produto.nome());
        i.put("productCode", produto.codigo());
        i.put("productName", produto.nome());
        i.put("unidadeComercial", produto.unidade());
        i.put("quantidadeCompra", Fmt.qtyApi(qtd));
        i.put("unidadeCompra", "CX");
        i.put("fatorConversao", Fmt.qtyApi(fator));
        i.put("custoCompra", Fmt.cost4Api(custoCaixa));
        i.put("quantidade", Fmt.qtyApi(ConversaoUnidade.quantidadeEstoque(qtd, fator)));
        i.put("precoCusto", Fmt.cost4Api(ConversaoUnidade.custoUnitario(custoCaixa, fator)));
        i.put("alterarPrecoVenda", false);
        i.put("precoVenda", Fmt.moneyApi(produto.preco()));
        return req;
    }

    @Test
    @Order(1)
    void entradaManualConverteUnidadeEAumentaOEstoque() {
        e.ctx.produtos.entradaManual(entradaManual());
        Produto depois = e.produto(produto.id());
        assertEquals(70, depois.estoque().intValue(), "10 + 5 CX x 12 = 70");
        assertEquals(0, new BigDecimal("4.00").compareTo(depois.custo()), "custo da caixa 48,00 / 12 = 4,00 por unidade");
        log("entrada manual: estoque " + produto.estoque() + " -> " + depois.estoque());
    }

    @Test
    @Order(2)
    void reenviarAMesmaOperacaoNaoDuplica() {
        e.ctx.produtos.entradaManual(entradaManual());
        assertEquals(70, e.produto(produto.id()).estoque().intValue(), "mesmo OperacaoId: estoque inalterado");
    }

    @Test
    @Order(3)
    void entradaApareceNaListaComoCupomComItens() {
        JsonNode r = e.ctx.entradas.listarNotas("CP-" + e.sufixo, 1, 10);
        assertEquals(1, r.get("notas").size());
        JsonNode nota = r.get("notas").get(0);
        notaManualId = nota.get("id").asText();
        assertEquals("manual", nota.get("origem").asText());
        assertEquals(1, nota.get("quantidadeItens").asInt());
        assertFalse(nota.get("estornada").asBoolean());
        JsonNode det = e.ctx.entradas.obterNota(notaManualId);
        assertEquals("cupom", det.get("entrada").get("tipoDocumento").asText());
        JsonNode item = det.get("entrada").get("itens").get(0);
        assertEquals(produto.codigo(), item.get("productCode").asText());
        assertTrue(item.get("quantidade").asText().startsWith("60"), "entrada de 60 un: " + item.get("quantidade"));
    }

    @Test
    @Order(4)
    void gerenteEstornaEEstoqueVolta() {
        e.ctx.entradas.estornar(notaManualId, "estorno de teste automatizado");
        assertEquals(10, e.produto(produto.id()).estoque().intValue(), "o estoque volta ao que era");
        JsonNode nota = e.ctx.entradas.listarNotas("CP-" + e.sufixo, 1, 10).get("notas").get(0);
        assertTrue(nota.get("estornada").asBoolean());
        assertEquals(400, falha(() -> e.ctx.entradas.estornar(notaManualId, "x")).status(), "justificativa curta");
    }

    @Test
    @Order(5)
    void nfePorXmlComVinculoENovoProduto() throws Exception {
        String b64 = Base64.getEncoder().encodeToString(xmlNovo().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JsonNode preview = e.ctx.entradas.previewXml(b64);
        assertEquals(2, preview.get("itens").size(), "o XML de exemplo tem 2 itens");
        assertEquals("11222333000181", preview.get("fornecedor").get("cnpj").asText());
        JsonNode doc = preview.get("documento");
        assertFalse(doc.get("xmlBase64").asText().isBlank());

        Produto existente = e.criarProduto("Nfe Vinculado", "NFV", "6", "10,00", "20,00", null, "0", "0");
        ObjectNode req = JsonFiles.MAPPER.createObjectNode();
        req.set("documento", doc);
        ObjectNode forn = req.putObject("fornecedor");
        JsonNode f = preview.get("fornecedor");
        for (String c : new String[] {"cnpj", "companyName", "fantasyName", "cep", "city", "state", "address", "neighborhood", "number", "telephone"}) {
            forn.put(c, f.path(c).asText(""));
        }
        ArrayNode itens = req.putArray("itens");
        JsonNode i1 = preview.get("itens").get(0);
        ObjectNode a = itens.addObject(); // item 1 vinculado a um produto existente, 3 CX x 6 = 18 un
        BigDecimal qtd = new BigDecimal("3");
        BigDecimal fator = new BigDecimal("6");
        BigDecimal custoCaixa = new BigDecimal("30.00");
        a.put("numeroItem", i1.get("numeroItem").asInt());
        a.put("produtoExistenteId", existente.id());
        a.put("productCode", existente.codigo());
        a.put("productName", existente.nome());
        a.put("unidadeComercial", existente.unidade());
        a.put("gtin", "SEM GTIN");
        a.put("ncm", "00000000");
        a.put("quantidade", Fmt.qtyApi(ConversaoUnidade.quantidadeEstoque(qtd, fator)));
        a.put("precoCusto", Fmt.cost4Api(ConversaoUnidade.custoUnitario(custoCaixa, fator)));
        a.put("precoVenda", Fmt.moneyApi(existente.preco()));
        JsonNode i2 = preview.get("itens").get(1);
        ObjectNode b = itens.addObject(); // item 2: cadastrado como produto novo
        String codigoNovo = "NFN-" + e.sufixo;
        b.put("numeroItem", i2.get("numeroItem").asInt());
        b.put("productCode", codigoNovo);
        b.put("productName", "Produto novo da NF-e " + e.sufixo);
        b.put("unidadeComercial", "UN");
        b.put("gtin", "SEM GTIN");
        b.put("ncm", "00000000");
        b.put("quantidade", "2");
        b.put("precoCusto", "7,5000");
        b.put("precoVenda", "12,90");

        {
            JsonNode r = e.ctx.entradas.confirmar(req);
            assertEquals(1, r.get("produtosCriados").asInt());
            assertEquals(1, r.get("produtosAtualizados").asInt());
            assertEquals(24, e.produto(existente.id()).estoque().intValue(), "6 + 3 CX x 6");
            Produto novo = e.ctx.produtos.listar().stream().filter(p -> p.codigo().equals(codigoNovo)).findFirst().orElseThrow();
            assertEquals(2, novo.estoque().intValue());
            assertEquals(0, new BigDecimal("12.90").compareTo(novo.preco()));
            // preço de venda atualizado depois da entrada (PATCH com a versão atual)
            Produto atual = e.produto(existente.id());
            e.ctx.produtos.atualizarPreco(atual.id(), "24,90", atual.versaoPreco());
            assertEquals(0, new BigDecimal("24.90").compareTo(e.produto(existente.id()).preco()));
            log("NF-e importada: 1 produto criado, 1 atualizado");
        }
        // A mesma nota de novo: a API recusa com a mensagem de nota já lançada e não duplica o estoque.
        ApiException repetida = falha(() -> e.ctx.entradas.confirmar(req));
        assertEquals(400, repetida.status());
        assertTrue(repetida.getMessage().toLowerCase().contains("já"), "mensagem da API: " + repetida.getMessage());
        assertEquals(24, e.produto(existente.id()).estoque().intValue(), "estoque intacto na repetição");
        log("NF-e repetida: " + repetida.getMessage());
    }

    @Test
    @Order(6)
    void estoquistaFazEntradaManualMasNaoEstorna() {
        AppContext est = e.comoEstoquista(homeEstoquista);
        assertTrue(est.entradas.listarNotas("", 1, 5).has("notas"), "estoquista consulta entradas");
        assertEquals(403, falha(() -> est.entradas.estornar("qualquer", "tentativa de estorno")).status());
        ObjectNode req = entradaManual();
        req.put("operacaoId", UUID.randomUUID().toString());
        req.put("numeroDocumento", "EST-" + e.sufixo);
        est.produtos.entradaManual(req);
        assertEquals(70, e.produto(produto.id()).estoque().intValue(), "10 + 60 (estoquista) depois do estorno do gerente");
    }

    /** DV da chave de acesso (módulo 11, pesos 2 a 9 da direita para a esquerda). */
    static String chaveComDv(String base43) {
        int soma = 0;
        int peso = 2;
        for (int i = base43.length() - 1; i >= 0; i--) {
            soma += (base43.charAt(i) - '0') * peso;
            peso = peso == 9 ? 2 : peso + 1;
        }
        int resto = soma % 11;
        int dv = resto < 2 ? 0 : 11 - resto;
        return base43 + dv;
    }

    /** XML de exemplo com número e chave novos (e DV correto), para a mesma nota não ser "repetida" a cada execução. */
    static String xmlNovo() throws Exception {
        String xml = Files.readString(Path.of(EntradasE2ETest.class.getResource("/br/com/quack/estoque/nfe-exemplo.xml").toURI()));
        long n = 100_000_000L + (System.currentTimeMillis() % 800_000_000L);
        String nnf = String.format("%09d", n);
        String base = "33" + "2610" + "11222333000181" + "65" + "002" + nnf + "1" + String.format("%08d", n % 100_000_000L);
        String chave = chaveComDv(base);
        String antiga = "33261011222333000181650020000000081000000089";
        return xml.replace(antiga, chave).replace("<nNF>8</nNF>", "<nNF>" + n + "</nNF>");
    }
}
