package br.com.quack.estoque.ui;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.api.ImportacaoApi.LinhaRelato;
import br.com.quack.estoque.api.ImportacaoApi.Relatorio;
import br.com.quack.estoque.api.ImportacaoApi.ResumoFiscal;
import br.com.quack.estoque.domain.Fmt;
import br.com.quack.estoque.domain.ImportacaoRelatorios;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/**
 * Importar planilha (CSV do sistema anterior): escolhe o arquivo, simula (nada é gravado), mostra totais, resumo fiscal,
 * recusados e avisos (exportáveis em CSV) e só então deixa importar. Para administrador e gerente.
 */
public class ImportarPlanilhaView extends Tela {
    /** Limite do servidor (15 MB); avisa antes de enviar. */
    private static final long MAX_BYTES = 15L * 1024 * 1024;

    private final Label arquivoEscolhido = Ui.muted("Nenhum arquivo escolhido.");
    private final FlowPane cartoes = new FlowPane(10, 10);
    private final Label resultado = new Label();
    private final TableView<ResumoFiscal> tabelaFiscal = Ui.tabela();
    private final TableView<LinhaRelato> tabelaRecusados = Ui.tabela();
    private final TableView<LinhaRelato> tabelaAvisos = Ui.tabela();
    private final Button btnImportar;
    private final Button btnRecusados;
    private final Button btnAvisos;
    private final TabPane abas;

    private String nomeArquivo;
    private byte[] conteudo;
    private Relatorio simulado;

    public ImportarPlanilhaView(AppContext ctx, Navegador nav) {
        super(ctx, nav);
        getChildren().add(Ui.cabecalho("Importar planilha",
                "Traz o cadastro de produtos do sistema anterior (CSV exportado). Primeiro simula, sem gravar; depois você confirma."));

        btnImportar = Ui.primario("Importar", this::confirmar);
        btnImportar.setDisable(true);
        btnRecusados = Ui.botao("Exportar recusados (CSV)", () -> exportar("recusados", simulado.recusados()));
        btnAvisos = Ui.botao("Exportar avisos (CSV)", () -> exportar("avisos", simulado.avisos()));
        btnRecusados.setDisable(true);
        btnAvisos.setDisable(true);
        getChildren().add(Ui.barra(Ui.botao("Escolher arquivo…", this::escolher), btnImportar, btnRecusados, btnAvisos));
        getChildren().add(arquivoEscolhido);

        resultado.setWrapText(true);
        resultado.setVisible(false);
        resultado.setManaged(false);
        getChildren().addAll(resultado, cartoes);

        Ui.col(tabelaFiscal, "CST", ResumoFiscal::cst, 70);
        Ui.col(tabelaFiscal, "CSOSN", ResumoFiscal::csosn, 80);
        Ui.col(tabelaFiscal, "CFOP", ResumoFiscal::cfop, 80);
        Ui.colNum(tabelaFiscal, "Produtos", f -> String.valueOf(f.quantidade()), 90);
        colunasLinha(tabelaRecusados);
        colunasLinha(tabelaAvisos);

        abas = new TabPane(aba("Resumo fiscal", tabelaFiscal), aba("Recusados", tabelaRecusados), aba("Avisos", tabelaAvisos));
        abas.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(abas, javafx.scene.layout.Priority.ALWAYS);
        getChildren().add(abas);
    }

    private static void colunasLinha(TableView<LinhaRelato> t) {
        Ui.colNum(t, "Linha", l -> String.valueOf(l.linha()), 60);
        Ui.col(t, "Código", LinhaRelato::codigo, 90);
        Ui.col(t, "Descrição", LinhaRelato::descricao, 300);
        Ui.col(t, "Motivo", LinhaRelato::motivo, 380);
    }

    private static Tab aba(String titulo, javafx.scene.Node conteudo) {
        Tab t = new Tab(titulo, conteudo);
        t.setClosable(false);
        return t;
    }

    @Override
    public void ativar() {
        // mantém o estado da tela entre visitas
    }

    private void escolher() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Planilha de produtos");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Planilha CSV", "*.csv"));
        File f = fc.showOpenDialog(janela());
        if (f != null) {
            carregarArquivo(f);
        }
    }

    /** Lê o arquivo e simula a importação (nada é gravado). Público para os testes. */
    public void carregarArquivo(File f) {
        byte[] bytes;
        try {
            if (f.length() > MAX_BYTES) {
                erro(new IllegalStateException("O arquivo deve ter no máximo 15 MB."));
                return;
            }
            bytes = Files.readAllBytes(f.toPath());
        } catch (IOException e) {
            erro(new IllegalStateException("Não foi possível ler o arquivo: " + e.getMessage()));
            return;
        }
        limpar();
        nomeArquivo = f.getName();
        conteudo = bytes;
        arquivoEscolhido.setText(nomeArquivo + " (" + (bytes.length / 1024) + " KB)");
        rodar("Simulando a importação…", () -> ctx.importacao.simular(nomeArquivo, conteudo), this::mostrarSimulacao);
    }

    private void limpar() {
        simulado = null;
        conteudo = null;
        btnImportar.setDisable(true);
        btnRecusados.setDisable(true);
        btnAvisos.setDisable(true);
        cartoes.getChildren().clear();
        resultado.setVisible(false);
        resultado.setManaged(false);
        tabelaFiscal.getItems().clear();
        tabelaRecusados.getItems().clear();
        tabelaAvisos.getItems().clear();
    }

    private void mostrarSimulacao(Relatorio r) {
        simulado = r;
        preencher(r);
        btnImportar.setDisable(!ImportacaoRelatorios.podeImportar(r.totais()));
        mensagem("Simulação concluída: nada foi gravado. Confira os números e clique em Importar.", "rg-warn-box");
    }

    private void preencher(Relatorio r) {
        var t = r.totais();
        cartoes.getChildren().setAll(
                cartao("Linhas lidas", String.valueOf(t.lidas())),
                cartao("Novos", String.valueOf(t.novos())),
                cartao("Atualizados", String.valueOf(t.atualizados())),
                cartao("Recusados", String.valueOf(t.recusados())),
                cartao("Avisos", String.valueOf(t.avisos())),
                cartao("Categorias novas", String.valueOf(t.categoriasNovas())),
                cartao("Produtos com saldo", String.valueOf(t.produtosComSaldo())),
                cartao("Quantidade a lançar", Fmt.qty(t.quantidadeTotal())),
                cartao("Valor do estoque (custo)", Fmt.money(t.valorEstoque())));
        tabelaFiscal.setItems(FXCollections.observableArrayList(r.resumoFiscal()));
        tabelaRecusados.setItems(FXCollections.observableArrayList(r.recusados()));
        tabelaAvisos.setItems(FXCollections.observableArrayList(r.avisos()));
        abas.getTabs().get(1).setText("Recusados (" + r.recusados().size() + ")");
        abas.getTabs().get(2).setText("Avisos (" + r.avisos().size() + ")");
        btnRecusados.setDisable(r.recusados().isEmpty());
        btnAvisos.setDisable(r.avisos().isEmpty());
    }

    private static VBox cartao(String rotulo, String valor) {
        VBox c = new VBox(2, Ui.texto(valor, "h3"), Ui.muted(rotulo));
        c.getStyleClass().add("rg-card");
        c.setMinWidth(150);
        return c;
    }

    private void mensagem(String texto, String classe) {
        resultado.setText(texto);
        resultado.getStyleClass().removeAll("rg-warn-box", "rg-ok-box");
        resultado.getStyleClass().add(classe);
        resultado.setVisible(true);
        resultado.setManaged(true);
    }

    private void confirmar() {
        if (simulado == null || conteudo == null) {
            return;
        }
        Modal.confirmar(janela(), "Importar planilha", ImportacaoRelatorios.perguntaDeConfirmacao(simulado.totais()),
                "Importar", this::gravar);
    }

    /** Grava tudo (uma transação no servidor). Público para os testes. */
    public void gravar() {
        btnImportar.setDisable(true);
        rodar("Importando os produtos… isso pode levar alguns minutos", () -> ctx.importacao.confirmar(nomeArquivo, conteudo),
                r -> {
                    preencher(r);
                    simulado = r;
                    mensagem("Importação concluída: " + (r.totais().novos() + r.totais().atualizados())
                            + " produtos gravados. A nuvem recebe tudo em alguns minutos.", "rg-ok-box");
                    nav.recarregarCatalogo();
                });
    }

    private void exportar(String nome, List<LinhaRelato> linhas) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Salvar " + nome);
        fc.setInitialFileName("importacao-" + nome + ".csv");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Planilha CSV", "*.csv"));
        File destino = fc.showSaveDialog(janela());
        if (destino == null) {
            return;
        }
        try {
            ImportacaoRelatorios.salvar(destino.toPath(), linhas);
            sucesso("Arquivo salvo: " + destino.getName());
        } catch (IOException e) {
            erro(e);
        }
    }

    /** Estado para os testes. */
    public boolean importarHabilitado() {
        return !btnImportar.isDisable();
    }

    public Relatorio relatorioAtual() {
        return simulado;
    }
}
