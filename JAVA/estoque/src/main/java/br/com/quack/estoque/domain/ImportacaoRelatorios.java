package br.com.quack.estoque.domain;

import br.com.quack.estoque.api.ImportacaoApi.LinhaRelato;
import br.com.quack.estoque.api.ImportacaoApi.Totais;
import java.util.List;

/** Textos e CSV do relatório da importação de planilha (sem tela, para poder testar). */
public final class ImportacaoRelatorios {
    private ImportacaoRelatorios() {
    }

    /** CSV (padrão brasileiro, ver {@link Csv}) com linha, código, descrição e motivo de cada linha do relatório. */
    public static String csv(List<LinhaRelato> linhas) {
        return Csv.build(List.of("Linha", "Código", "Descrição", "Motivo"),
                linhas.stream().map(l -> List.of(String.valueOf(l.linha()), l.codigo(), l.descricao(), l.motivo())).toList());
    }

    public static void salvar(java.nio.file.Path destino, List<LinhaRelato> linhas) throws java.io.IOException {
        Csv.save(destino, List.of("Linha", "Código", "Descrição", "Motivo"),
                linhas.stream().map(l -> List.of(String.valueOf(l.linha()), l.codigo(), l.descricao(), l.motivo())).toList());
    }

    /** Pergunta antes de gravar: quantos produtos entram e em quantos o estoque inicial é lançado. */
    public static String perguntaDeConfirmacao(Totais t) {
        int produtos = t.novos() + t.atualizados();
        return "Importar " + produtos + " produtos e lançar o estoque inicial de " + t.produtosComSaldo() + " produtos?";
    }

    /** Só vale importar quando alguma linha foi aceita. */
    public static boolean podeImportar(Totais t) {
        return t != null && t.novos() + t.atualizados() > 0;
    }
}
