package br.com.quack.pdv.produto.entrada.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import br.com.quack.pdv.cadastro.fornecedor.repo.FornecedorRepository;
import br.com.quack.pdv.produto.entrada.web.NfeImportRequests.*;
import br.com.quack.pdv.produto.lote.app.LoteOperacoes;
import br.com.quack.pdv.produto.produto.app.ProdutoService;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

class EntradaManualServiceTest {
    private final ProdutoService produtos = mock(ProdutoService.class);
    private final EntradaManualService service =
            new EntradaManualService(
                    produtos,
                    mock(FornecedorRepository.class),
                    mock(NotaEntradaArquivoService.class),
                    mock(LoteOperacoes.class),
                    new ObjectMapper(),
                    mock(PlatformTransactionManager.class));

    private EntradaManualRequest pedido() {
        var r = new EntradaManualRequest();
        r.operacaoId = "aaaaaaaa-bbbb-cccc-dddd-000000000001";
        r.dataCompra = "2026-11-02";
        r.itens = List.of(new ManualItem());
        return r;
    }

    @Test
    void rejeitaIdentificacaoAntesDeAbrirTransacao() {
        var r = pedido();
        r.operacaoId = "abc";
        assertThatThrownBy(() -> service.confirmar("e", r, "u", "n"))
                .hasMessage("Identificação da entrada inválida. Abra uma nova entrada.");
        verifyNoInteractions(produtos);
    }

    @Test
    void rejeitaDataInexistente() {
        var r = pedido();
        r.dataCompra = "2026-02-30";
        assertThatThrownBy(() -> service.confirmar("e", r, "u", "n"))
                .hasMessage("Informe uma data de compra válida.");
    }

    @Test
    void rejeitaListaVazia() {
        var r = pedido();
        r.itens = List.of();
        assertThatThrownBy(() -> service.confirmar("e", r, "u", "n"))
                .hasMessage("Adicione entre 1 e 200 produtos à entrada.");
    }

    @Test
    void rejeitaTipoDocumento() {
        var r = pedido();
        r.tipoDocumento = "nota";
        assertThatThrownBy(() -> service.confirmar("e", r, "u", "n"))
                .hasMessage("Tipo de comprovante inválido.");
    }

    @Test
    void positivoRespeitaLimitesEscalaENumerosPtBr() {
        assertThat(EntradaManualService.positivo("1.234,5678", "Quantidade", "1000000"))
                .isEqualByComparingTo("1234.5678");
        for (String n : List.of("0", "-1", "1,00001", "1000001", "abc")) {
            assertThatThrownBy(() -> EntradaManualService.positivo(n, "Quantidade", "1000000"))
                    .hasMessageContaining("Quantidade deve ser maior que zero");
        }
    }

    @Test
    void comprovanteValidaAssinaturaENome() {
        var c = new ComprovanteInput();
        c.nome = "pasta/teste.pdf";
        c.base64 = "JVBERi0=";
        var a = EntradaManualService.prepararComprovante(c);
        assertThat(a.nome()).isEqualTo("teste.pdf");
        assertThat(a.tipo()).isEqualTo("application/pdf");
        c.base64 = "YWJj";
        assertThatThrownBy(() -> EntradaManualService.prepararComprovante(c))
                .hasMessage("Selecione uma imagem JPG/PNG, PDF ou XML válido.");
    }

    @Test
    void fingerprintConfereComEntradaPersistidaPeloDotNet() throws Exception {
        var mapper = new ObjectMapper();
        var cenarios =
                mapper.readTree(
                        getClass().getResourceAsStream("/contrato/cenarios/entrada-manual.json"));
        for (var c : cenarios) {
            if (c.path("nome").asText().equals("manual-reenvio-da-operacao-do-seed")) {
                var r =
                        mapper.treeToValue(
                                c.path("requisicao").path("corpo"), EntradaManualRequest.class);
                assertThat(EntradaSuporte.hash(JsonWeb.escrever(r.paraMapa())))
                        .isEqualTo(
                                "5787435820AB52D17F90C87E5FFD9D14F5CDE4706366DBB4102E4D02F19DBD42");
                return;
            }
        }
        fail("Cenário de replay do seed não encontrado");
    }
}
