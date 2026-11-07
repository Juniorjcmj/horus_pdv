package br.com.quack.pdv.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.quack.pdv.produto.importacao.RelatorioImportacao;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

class ImportacaoProdutosWebTest extends WebTestBase {

    private static final RelatorioImportacao RELATORIO = new RelatorioImportacao(
            new RelatorioImportacao.Totais(2, 2, 0, 0, 0, 1, 1, BigDecimal.TEN, BigDecimal.ONE),
            List.of(), List.of(), List.of(), "hash", null);

    private static MockMultipartFile arquivo() {
        return new MockMultipartFile("arquivo", "p.csv", "text/csv", "x".getBytes());
    }

    @ParameterizedTest
    @ValueSource(strings = {"atendente", "caixa", "estoquista"})
    void outrosPerfisRecebem403(String perfil) throws Exception {
        String auth = "Bearer " + tokenDe(perfil);
        mvc.perform(multipart("/api/Produto/importacao/simular").file(arquivo()).header("Authorization", auth))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/Produto/importacao/confirmar").file(arquivo()).header("Authorization", auth))
                .andExpect(status().isForbidden());
        verifyNoInteractions(importacaoProdutos);
    }

    @ParameterizedTest
    @ValueSource(strings = {"administrador", "gerente"})
    void administradorEGerenteSimulamEConfirmam(String perfil) throws Exception {
        String auth = "Bearer " + tokenDe(perfil);
        when(importacaoProdutos.simular(eq("empresa-principal"), any())).thenReturn(RELATORIO);
        when(importacaoProdutos.confirmar(eq("empresa-principal"), any(), eq("usr-1"), any())).thenReturn(RELATORIO);

        mvc.perform(multipart("/api/Produto/importacao/simular").file(arquivo()).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totais.novos").value(2));
        mvc.perform(multipart("/api/produtos/importacao/confirmar").file(arquivo()).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hashArquivo").value("hash"));
    }

    @Test
    void semOCampoArquivoResponde400() throws Exception {
        mvc.perform(multipart("/api/Produto/importacao/simular")
                        .file(new MockMultipartFile("outro", "p.csv", "text/csv", "x".getBytes()))
                        .header("Authorization", "Bearer " + tokenDe("gerente")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void importacaoAceitaCorpoDe16MbMasAsOutrasRotasContinuamEm2Mb() throws Exception {
        String auth = "Bearer " + tokenDe("gerente");
        when(importacaoProdutos.simular(eq("empresa-principal"), any())).thenReturn(RELATORIO);
        byte[] cincoMb = new byte[5 * 1024 * 1024];

        // 5 MB passa na rota de importacao (limite do filtro: 16 MB)
        mvc.perform(multipart("/api/Produto/importacao/simular")
                        .file(new MockMultipartFile("arquivo", "p.csv", "text/csv", cincoMb))
                        .header("Authorization", auth))
                .andExpect(status().isOk());
        // a mesma carga numa rota qualquer e barrada pelo limite geral de 2 MB
        mvc.perform(post("/api/Produto").header("Authorization", auth).contentType("application/json").content(cincoMb))
                .andExpect(status().isPayloadTooLarge());
    }
}
