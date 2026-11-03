package br.com.quack.estoque.service;

import br.com.quack.estoque.persistence.JsonFiles;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Rascunhos locais em %APPDATA%\QuackEstoque\rascunhos: a conferência de uma entrada por NF-e e as contagens ainda
 * não enviadas ficam aqui para sobreviver a sessão expirada, queda da API ou fechamento do aplicativo.
 */
public final class Rascunhos {
    private final Path dir;

    public Rascunhos(Path dir) {
        this.dir = dir;
    }

    public void salvar(String nome, Object conteudo) {
        try {
            JsonFiles.writeAtomic(dir.resolve(nome + ".json"), conteudo);
        } catch (IOException e) {
            System.err.println("Rascunho \"" + nome + "\" não gravado: " + e.getMessage());
        }
    }

    /** null se não existe ou está ilegível. */
    public JsonNode ler(String nome) {
        Path f = dir.resolve(nome + ".json");
        if (!Files.exists(f)) {
            return null;
        }
        try {
            return JsonFiles.MAPPER.readTree(f.toFile());
        } catch (IOException e) {
            return null;
        }
    }

    public boolean existe(String nome) {
        return Files.exists(dir.resolve(nome + ".json"));
    }

    public void apagar(String nome) {
        try {
            Files.deleteIfExists(dir.resolve(nome + ".json"));
        } catch (IOException ignored) {
            // sem permissão: o rascunho fica e será sobrescrito
        }
    }
}
