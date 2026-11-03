package br.com.quack.pdv.produto.entrada.app;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import br.com.quack.pdv.cadastro.fornecedor.repo.FornecedorRepository;
import br.com.quack.pdv.produto.entrada.web.NfeImportRequests.ConfirmRequest;
import br.com.quack.pdv.produto.entrada.web.NfeImportRequests.ItemInput;
import br.com.quack.pdv.produto.lote.app.LoteOperacoes;
import br.com.quack.pdv.produto.produto.app.ProdutoService;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

class NfeImportServiceTest {
    private final ProdutoService produtos = mock(ProdutoService.class);
    private final NotaEntradaArquivoService arquivo = mock(NotaEntradaArquivoService.class);
    private final NfeImportService service =
            new NfeImportService(
                    produtos,
                    mock(FornecedorRepository.class),
                    arquivo,
                    mock(LoteOperacoes.class),
                    mock(JdbcTemplate.class),
                    new ObjectMapper(),
                    Clock.systemUTC(),
                    mock(PlatformTransactionManager.class));

    @Test
    void loteVazioNaoReservaNota() {
        assertThatThrownBy(() -> service.confirmar("e", new ConfirmRequest(), "u", "n"))
                .hasMessage("Nenhum item para importar.");
        verifyNoInteractions(arquivo, produtos);
    }

    @Test
    void fornecedorExigeCnpjERazaoSocialAntesDaTransacao() {
        var r = new ConfirmRequest();
        r.itens.add(new ItemInput());
        assertThatThrownBy(() -> service.confirmar("e", r, "u", "n"))
                .hasMessage("CNPJ do fornecedor inválido.");
        r.fornecedor.cnpj = "11.222.333/0001-81";
        assertThatThrownBy(() -> service.confirmar("e", r, "u", "n"))
                .hasMessage("Razão social do fornecedor é obrigatória.");
        verifyNoInteractions(arquivo, produtos);
    }

    @Test
    void estornoExigeJustificativaAntesDeLerOuAlterarSaldo() {
        assertThatThrownBy(() -> service.estornar("e", "n", "u", "n", "erro"))
                .hasMessage(
                        "Informe uma justificativa com pelo menos 5 caracteres para estornar a"
                            + " nota.");
        verifyNoInteractions(arquivo, produtos);
    }
}
