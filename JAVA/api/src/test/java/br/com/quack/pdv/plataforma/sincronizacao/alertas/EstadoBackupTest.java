package br.com.quack.pdv.plataforma.sincronizacao.alertas;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EstadoBackupTest {

    @TempDir
    Path pasta;

    @Test
    void caminhoVazioOuArquivoAusenteNaoTemEstado() {
        assertThat(EstadoBackup.ler("")).isEmpty();
        assertThat(EstadoBackup.ler(null)).isEmpty();
        assertThat(EstadoBackup.ler(pasta.resolve("nao-existe.json").toString())).isEmpty();
    }

    @Test
    void leSucessoEFalhaDoArquivoDoScript() throws IOException {
        Path f = pasta.resolve("backup-estado.json");
        Files.writeString(f, "{\"ultimoBackupEm\":\"2026-11-04T02:00:00Z\",\"sucesso\":true,\"erro\":null}");
        EstadoBackup.Estado ok = EstadoBackup.ler(f.toString()).orElseThrow();
        assertThat(ok.sucesso()).isTrue();
        assertThat(ok.erro()).isNull();
        assertThat(ok.ultimoBackupEm()).isEqualTo("2026-11-04T02:00:00Z");

        Files.writeString(f, "{\"ultimoBackupEm\":null,\"sucesso\":false,\"erro\":\"disco cheio\"}");
        EstadoBackup.Estado falha = EstadoBackup.ler(f.toString()).orElseThrow();
        assertThat(falha.sucesso()).isFalse();
        assertThat(falha.erro()).isEqualTo("disco cheio");
    }

    @Test
    void arquivoIlegivelContaComoFalha() throws IOException {
        Path f = pasta.resolve("quebrado.json");
        Files.writeString(f, "{nao e json");
        EstadoBackup.Estado e = EstadoBackup.ler(f.toString()).orElseThrow();
        assertThat(e.sucesso()).isFalse();
        assertThat(e.erro()).contains("ilegivel");
    }
}
