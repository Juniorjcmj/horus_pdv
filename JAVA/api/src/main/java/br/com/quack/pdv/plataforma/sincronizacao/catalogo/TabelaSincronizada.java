package br.com.quack.pdv.plataforma.sincronizacao.catalogo;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Classificacao de uma tabela. {@code colunaEmpresa} e a coluna que guarda a empresa ({@code CompanyId}; em
 * {@code Empresas} e o proprio {@code Id}); nas filhas sem empresa e {@code null} e valem {@code mae} e
 * {@code colunaLigacao}. {@code excecoes} sobrepoe a classe padrao de coluna; {@code regras} sao regras por linha;
 * {@code aceites} sao transicoes que a nuvem pode fazer numa tabela LOJA (ver {@link AceiteDaNuvem}).
 * {@code insercaoDaNuvem} e {@code transicoes} (change api-java-operacoes-estoque-nuvem, O4): a nuvem pode inserir linhas
 * nesta tabela LOJA e fazer as transicoes listadas, que descem para a loja dentro de uma operacao de estoque.
 */
public record TabelaSincronizada(
        String nome,
        ClasseTabela classe,
        String colunaEmpresa,
        String mae,
        String colunaLigacao,
        Map<String, ClasseColuna> excecoes,
        List<RegraLinha> regras,
        List<AceiteDaNuvem> aceites,
        boolean insercaoDaNuvem,
        List<TransicaoDaNuvem> transicoes) {

    public TabelaSincronizada {
        excecoes = Map.copyOf(excecoes);
        regras = List.copyOf(regras);
        aceites = List.copyOf(aceites);
        transicoes = List.copyOf(transicoes);
    }

    public TabelaSincronizada(String nome, ClasseTabela classe, String colunaEmpresa, String mae, String colunaLigacao,
                              Map<String, ClasseColuna> excecoes, List<RegraLinha> regras, List<AceiteDaNuvem> aceites) {
        this(nome, classe, colunaEmpresa, mae, colunaLigacao, excecoes, regras, aceites, false, List.of());
    }

    public TabelaSincronizada(String nome, ClasseTabela classe, String colunaEmpresa, String mae, String colunaLigacao,
                              Map<String, ClasseColuna> excecoes, List<RegraLinha> regras) {
        this(nome, classe, colunaEmpresa, mae, colunaLigacao, excecoes, regras, List.of(), false, List.of());
    }

    /** Copia desta tabela que aceita insercao da nuvem e as {@code transicoes} dadas (operacoes de estoque, O4). */
    public TabelaSincronizada comOperacoesDaNuvem(List<TransicaoDaNuvem> transicoes) {
        return new TabelaSincronizada(nome, classe, colunaEmpresa, mae, colunaLigacao, excecoes, regras, aceites, true,
                transicoes);
    }

    /**
     * Regra por linha: quando {@code coluna} vale {@code valor}, todas as colunas da linha tem a classe indicada.
     * Ex.: DocumentosFiscais com Modelo = 55 (NF-e do painel) e autoria da nuvem.
     */
    public record RegraLinha(String coluna, Object valor, ClasseColuna classeDasColunas) {}

    /**
     * Transicao aceita da nuvem em tabela LOJA: quando a linha tem {@code colunaGatilho} igual a um dos {@code valores},
     * a nuvem e autora das {@code colunas} e a mudanca desce por {@code forcar} (sem quarentena). Ex.: o worker fiscal
     * da .NET marca {@code Vendas.Status} como cancelada/estornada ao cancelar ou devolver a nota na nuvem.
     */
    public record AceiteDaNuvem(String colunaGatilho, Set<String> valores, List<String> colunas) {
        public AceiteDaNuvem {
            valores = Set.copyOf(valores);
            colunas = List.copyOf(colunas);
        }
    }

    /**
     * Transicao de uma linha ja existente na loja que a nuvem pode fazer (O4): a linha da nuvem passa a ter
     * {@code colunaDestino = valorDestino} (estado de destino, texto do fio) e a loja so a aplica se a linha dela tem
     * {@code colunaLoja = valorLoja}; {@code colunas} sao as que descem.
     */
    public record TransicaoDaNuvem(String nome, String colunaDestino, String valorDestino, String colunaLoja,
                                   String valorLoja, List<String> colunas) {
        public TransicaoDaNuvem {
            colunas = List.copyOf(colunas);
        }

        /** A linha viva da nuvem esta no estado de destino desta transicao? */
        public boolean destinoAtingido(Map<String, String> linha) {
            return valorDestino.equals(linha.get(colunaDestino));
        }
    }

    /** Transicao aceita pelo nome, se a tabela a tiver. */
    public Optional<TransicaoDaNuvem> transicao(String nomeDaTransicao) {
        return transicoes.stream().filter(t -> t.nome().equals(nomeDaTransicao)).findFirst();
    }

    /** Alguma operacao de estoque da nuvem toca esta tabela (insercao ou transicao)? */
    public boolean participaDeOperacaoDaNuvem() { return insercaoDaNuvem || !transicoes.isEmpty(); }

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

    /** A linha e de autoria da nuvem por inteiro (regra por linha com classe NUVEM). */
    public boolean autoriaDaNuvem(Map<String, Object> linha) {
        for (RegraLinha r : regras) {
            Object atual = linha.get(r.coluna());
            if (r.classeDasColunas() == ClasseColuna.NUVEM && atual != null
                    && r.valor().toString().equals(atual.toString())) return true;
        }
        return false;
    }

    /** Aceite que se aplica a esta linha, se houver. */
    public Optional<AceiteDaNuvem> aceiteDaNuvem(Map<String, Object> linha) {
        for (AceiteDaNuvem a : aceites) {
            Object v = linha.get(a.colunaGatilho());
            if (v != null && a.valores().contains(v.toString())) return Optional.of(a);
        }
        return Optional.empty();
    }

    /** Colunas NUVEM ou CIFRADA declaradas (fora regra por linha). */
    public boolean temColunasDaNuvem() {
        return excecoes.values().stream().anyMatch(c -> c == ClasseColuna.NUVEM || c == ClasseColuna.CIFRADA);
    }

    /**
     * A nuvem guarda no espelho (SyncEspelho) o que veio da loja: tabelas EDITAVEL (base dos comandos) e tabelas com
     * colunas ou linhas de autoria da nuvem (para saber que a linha e da loja quando a nuvem a altera depois).
     */
    public boolean usaEspelho() {
        return sincroniza() && classe != ClasseTabela.ACRESCIMO
                && (classe == ClasseTabela.EDITAVEL || temColunasDaNuvem() || !regras.isEmpty());
    }
}
