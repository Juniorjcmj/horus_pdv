package br.com.quack.pdv.plataforma.sincronizacao.catalogo;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Classificacao de uma tabela. {@code colunaEmpresa} e a coluna que guarda a empresa ({@code CompanyId}; em
 * {@code Empresas} e o proprio {@code Id}); nas filhas sem empresa e {@code null} e valem {@code mae} e
 * {@code colunaLigacao}. {@code excecoes} sobrepoe a classe padrao de coluna; {@code regras} sao regras por linha.
 */
public record TabelaSincronizada(
        String nome,
        ClasseTabela classe,
        String colunaEmpresa,
        String mae,
        String colunaLigacao,
        Map<String, ClasseColuna> excecoes,
        List<RegraLinha> regras) {

    public TabelaSincronizada {
        excecoes = Map.copyOf(excecoes);
        regras = List.copyOf(regras);
    }

    /**
     * Regra por linha: quando {@code coluna} vale {@code valor}, todas as colunas da linha tem a classe indicada.
     * Ex.: DocumentosFiscais com Modelo = 55 (NF-e do painel) e autoria da nuvem.
     */
    public record RegraLinha(String coluna, Object valor, ClasseColuna classeDasColunas) {}

    public boolean sincroniza() { return classe != ClasseTabela.FORA; }

    public boolean filhaSemEmpresa() { return mae != null; }

    /** Classe padrao das colunas sem excecao. */
    public ClasseColuna classePadraoDeColuna() {
        return classe == ClasseTabela.EDITAVEL ? ClasseColuna.EDITAVEL : ClasseColuna.LOJA;
    }

    /** Classe de uma coluna para uma linha. {@code linha} pode ser nulo (so a classe estatica). */
    public ClasseColuna classeDaColuna(String coluna, Map<String, Object> linha) {
        if (linha != null) {
            for (RegraLinha r : regras) {
                Object atual = linha.get(r.coluna());
                if (atual != null && r.valor().toString().equals(atual.toString())) return r.classeDasColunas();
            }
        }
        return excecoes.getOrDefault(coluna, classePadraoDeColuna());
    }

    public Optional<String> escopoEmpresa() { return Optional.ofNullable(colunaEmpresa); }
}
