package br.com.quack.estoque;

import static br.com.quack.estoque.E2eSupport.log;
import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.api.CadastroApi;
import br.com.quack.estoque.domain.Produto;
import br.com.quack.estoque.persistence.JsonFiles;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Ficha do produto: trocar o fornecedor (lista) grava de verdade e a lista recarregada mostra o novo. */
@EnabledIfSystemProperty(named = "quack.e2e.url", matches = ".+")
class FornecedorProdutoE2ETest {
    @TempDir
    static Path home;

    private static CadastroApi.Fornecedor fornecedor(E2eSupport e, String nome) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("companyName", nome + " Ltda");
        f.put("fantasyName", nome);
        for (String k : new String[] {"cnpj", "telephone", "email", "cep", "city", "state", "address", "neighborhood",
            "streetComplement", "number", "referencePoint", "cellphone"}) {
            f.put(k, "");
        }
        return e.ctx.cadastros.salvarFornecedor(null, f);
    }

    private static Produto buscar(E2eSupport e, String id) {
        return e.ctx.produtos.listar().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void trocarFornecedorPelaFicha() {
        E2eSupport e = new E2eSupport(home);
        e.loginAdmin();
        CadastroApi.Fornecedor a = fornecedor(e, "Forn A " + e.sufixo);
        CadastroApi.Fornecedor b = fornecedor(e, "Forn B " + e.sufixo);
        ObjectNode n = JsonFiles.MAPPER.createObjectNode();
        n.put("productName", "Ficha Forn " + e.sufixo);
        n.put("productCode", "FF-" + e.sufixo);
        n.put("productQnt", "3");
        n.put("productUnitPrice", "1,0000");
        n.put("productSalePrice", "2,00");
        n.put("unidadeComercial", "UN");
        n.put("productSupplier", a.nome());
        Produto p = e.ctx.produtos.criar(n);
        log("criado com fornecedor: '" + buscar(e, p.id()).fornecedor() + "'");
        assertEquals(a.nome(), buscar(e, p.id()).fornecedor());

        // mesmo corpo que a ficha monta ao salvar
        Produto visto = buscar(e, p.id());
        ObjectNode body = visto.raw().deepCopy();
        body.put("estoqueConsultado", body.path("productQnt").asText("0"));
        body.put("productSupplier", b.nome());
        Produto salvo = e.ctx.produtos.atualizar(p.id(), body);
        log("resposta do PUT: '" + salvo.fornecedor() + "' · relido: '" + buscar(e, p.id()).fornecedor() + "'");
        assertEquals(b.nome(), salvo.fornecedor(), "resposta do PUT");
        assertEquals(b.nome(), buscar(e, p.id()).fornecedor(), "gravado");

        // tirar o fornecedor (opção vazia da lista)
        ObjectNode sem = buscar(e, p.id()).raw().deepCopy();
        sem.put("estoqueConsultado", sem.path("productQnt").asText("0"));
        sem.put("productSupplier", "");
        e.ctx.produtos.atualizar(p.id(), sem);
        log("sem fornecedor relido: '" + buscar(e, p.id()).fornecedor() + "'");
        assertEquals("", buscar(e, p.id()).fornecedor(), "fornecedor removido");
    }
}
