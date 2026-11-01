package br.com.quack.estoque.ui;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.api.ProdutoApi.Movimentacao;
import br.com.quack.estoque.domain.Csv;
import br.com.quack.estoque.domain.DomainException;
import br.com.quack.estoque.domain.Fmt;
import br.com.quack.estoque.domain.Produto;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javafx.collections.FXCollections;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/** Ajustes avulsos (entrada, saída, uso interno, perda) e histórico de movimentações com filtro e CSV. */
public class MovimentacoesView extends Tela {
    private static final Map<String, String> TIPOS = Map.of("entrada", "Entrada", "saida", "Saída", "uso_interno",
            "Uso interno", "perda", "Perda", "inventario", "Inventário");
    private static final List<String> TIPOS_AJUSTE = List.of("entrada", "saida", "uso_interno", "perda");

    private Produto produto;
    private final Label produtoSel = Ui.texto("Nenhum produto escolhido.", "rg-section-title");
    private final ComboBox<String> tipo = Ui.combo(TIPOS_AJUSTE);
    private final TextField quantidade = Ui.campo("Quantidade");
    private final TextField motivo = Ui.campo("Motivo (obrigatório)");
    private final TextField lote = Ui.campo("Lote (opcional, na entrada)");
    private final DatePicker validade = new DatePicker();
    private final Label previa = Ui.muted("");

    private final ComboBox<String> filtroTipo = Ui.combo(List.of("Todos", "entrada", "saida", "uso_interno", "perda", "inventario"));
    private final DatePicker de = new DatePicker(LocalDate.now().withDayOfMonth(1));
    private final DatePicker ate = new DatePicker(LocalDate.now());
    private final TableView<Movimentacao> tabela = Ui.tabela();
    private final Label total = Ui.muted("");
    private List<Movimentacao> exibidas = new ArrayList<>();

    public MovimentacoesView(AppContext ctx, Navegador nav) {
        super(ctx, nav);
        getChildren().add(Ui.cabecalho("Ajustes e movimentações",
                "Lance entrada, saída, uso interno ou perda avulsos e consulte o histórico (F4 abre os ajustes)."));

        tipo.setValue("perda");
        tipo.setConverter(rotulos());
        filtroTipo.setValue("Todos");
        filtroTipo.setConverter(rotulos());
        validade.setPromptText("Validade");
        validade.setMaxWidth(Double.MAX_VALUE);
        tipo.valueProperty().addListener((o, a, b) -> atualizarPrevia());
        quantidade.textProperty().addListener((o, a, b) -> atualizarPrevia());

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        g.add(Ui.rotulado("Tipo", tipo), 0, 0);
        g.add(Ui.rotulado("Quantidade", quantidade), 1, 0);
        g.add(Ui.rotulado("Motivo", motivo), 2, 0);
        g.add(Ui.rotulado("Lote", lote), 0, 1);
        g.add(Ui.rotulado("Validade (entrada)", validade), 1, 1);
        g.add(previa, 2, 1);
        for (int c = 0; c < 3; c++) {
            javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
            cc.setPercentWidth(c == 2 ? 44 : 28);
            g.getColumnConstraints().add(cc);
        }
        VBox form = new VBox(8, Ui.barra(Ui.botao("Escolher produto…", this::escolher), produtoSel), g,
                Ui.barra(Ui.primario("Lançar ajuste", this::lancar)));
        form.getStyleClass().add("rg-card");
        getChildren().add(form);

        Ui.col(tabela, "Data", m -> Fmt.dateTimeUtc(m.criadoEm()), 120);
        Ui.col(tabela, "Produto", m -> m.produtoCodigo() + " · " + m.produtoNome(), 260);
        Ui.col(tabela, "Tipo", m -> TIPOS.getOrDefault(m.tipo(), m.tipo()), 90);
        Ui.colNum(tabela, "Qtd.", m -> Fmt.qty(m.quantidade()), 70);
        Ui.colNum(tabela, "Antes", m -> Fmt.qty(m.estoqueAnterior()), 70);
        Ui.colNum(tabela, "Depois", m -> Fmt.qty(m.estoqueNovo()), 70);
        Ui.col(tabela, "Motivo", Movimentacao::motivo, 240);
        Ui.col(tabela, "Usuário", Movimentacao::usuarioNome, 130);

        getChildren().add(Ui.texto("Histórico", "rg-section-title"));
        getChildren().add(Ui.barra(Ui.rotulado("Tipo", filtroTipo), Ui.rotulado("De", de), Ui.rotulado("Até", ate),
                    Ui.botao("Filtrar", this::carregar), Ui.botao("Exportar CSV", this::exportar)));
        VBox.setVgrow(tabela, Priority.ALWAYS);
        getChildren().addAll(tabela, total);
    }

    private static javafx.util.StringConverter<String> rotulos() {
        return new javafx.util.StringConverter<>() {
            @Override
            public String toString(String s) {
                return s == null ? "" : TIPOS.getOrDefault(s, s);
            }

            @Override
            public String fromString(String s) {
                return s;
            }
        };
    }

    @Override
    public void ativar() {
        carregar();
        rodar(() -> {
            ctx.garantirCatalogo(false);
            return true;
        }, ok -> {
        });
    }

    private void escolher() {
        ProdutoPicker.escolher(janela(), ctx, false, p -> {
            produto = p;
            produtoSel.setText(p.codigo() + " · " + p.nome() + "  (saldo " + Fmt.qty(p.estoque()) + " " + p.unidade() + ")");
            atualizarPrevia();
            quantidade.requestFocus();
        });
    }

    /** Escolhe o produto de fora da tela (atalho F4 / botão da ficha). */
    public void selecionar(Produto p) {
        produto = p;
        produtoSel.setText(p.codigo() + " · " + p.nome() + "  (saldo " + Fmt.qty(p.estoque()) + " " + p.unidade() + ")");
        atualizarPrevia();
    }

    private void atualizarPrevia() {
        if (produto == null) {
            previa.setText("");
            return;
        }
        try {
            BigDecimal q = Fmt.parse(quantidade.getText());
            BigDecimal depois = "entrada".equals(tipo.getValue()) ? produto.estoque().add(q) : produto.estoque().subtract(q);
            previa.setText("Saldo: " + Fmt.qty(produto.estoque()) + " → " + Fmt.qty(depois));
            previa.setStyle(depois.signum() < 0 ? "-fx-text-fill: -danger;" : "");
        } catch (DomainException e) {
            previa.setText("Saldo atual: " + Fmt.qty(produto.estoque()));
            previa.setStyle("");
        }
    }

    private void lancar() {
        try {
            if (produto == null) {
                throw new DomainException("Escolha o produto.");
            }
            BigDecimal q = Ui.numero(quantidade, "a quantidade");
            if (q.signum() <= 0) {
                throw new DomainException("A quantidade deve ser maior que zero.");
            }
            String mot = motivo.getText() == null ? "" : motivo.getText().trim();
            if (mot.length() < 3) {
                throw new DomainException("Informe o motivo do ajuste (obrigatório).");
            }
            String t = tipo.getValue();
            String val = "entrada".equals(t) && validade.getValue() != null ? validade.getValue().toString() : null;
            String lt = "entrada".equals(t) ? lote.getText() : null;
            Produto alvo = produto;
            rodar(() -> {
                ctx.produtos.ajustar(alvo.id(), t, q, mot, val, lt);
                ctx.garantirCatalogo(true);
                return ctx.catalogo.porId(alvo.id());
            }, atualizado -> {
                BigDecimal antes = alvo.estoque();
                sucesso("Ajuste lançado: " + Fmt.qty(antes) + " → " + Fmt.qty(atualizado == null ? antes : atualizado.estoque())
                        + " " + alvo.unidade() + ".");
                if (atualizado != null) {
                    selecionar(atualizado);
                }
                quantidade.clear();
                motivo.clear();
                lote.clear();
                validade.setValue(null);
                carregar();
            });
        } catch (DomainException e) {
            Toasts.error(janela(), e.getMessage());
        }
    }

    private void carregar() {
        String t = "Todos".equals(filtroTipo.getValue()) ? null : filtroTipo.getValue();
        LocalDate d1 = de.getValue();
        LocalDate d2 = ate.getValue();
        rodar(() -> ctx.produtos.movimentacoes(t, 2000), lista -> {
            exibidas = filtrarPeriodo(lista, d1, d2);
            tabela.setItems(FXCollections.observableArrayList(exibidas));
            total.setText(resumo(exibidas));
        });
    }

    /** Filtra pelo dia (hora de Brasília); CriadoEm vem em UTC. */
    static List<Movimentacao> filtrarPeriodo(List<Movimentacao> lista, LocalDate de, LocalDate ate) {
        List<Movimentacao> out = new ArrayList<>();
        for (Movimentacao m : lista) {
            LocalDate dia = dia(m.criadoEm());
            if ((de == null || dia == null || !dia.isBefore(de)) && (ate == null || dia == null || !dia.isAfter(ate))) {
                out.add(m);
            }
        }
        return out;
    }

    private static LocalDate dia(String iso) {
        try {
            return LocalDateTime.parse(iso).atZone(ZoneId.of("UTC")).withZoneSameInstant(ZoneId.of("America/Sao_Paulo")).toLocalDate();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String resumo(List<Movimentacao> lista) {
        BigDecimal soma = lista.stream().map(Movimentacao::quantidade).reduce(BigDecimal.ZERO, BigDecimal::add);
        return lista.size() + " movimentações · quantidade total " + Fmt.qty(soma);
    }

    static List<String> cabecalhoCsv() {
        return List.of("Data", "Código", "Produto", "Tipo", "Quantidade", "Saldo anterior", "Saldo novo", "Motivo", "Usuário");
    }

    static List<List<String>> linhasCsv(List<Movimentacao> lista) {
        List<List<String>> out = new ArrayList<>();
        for (Movimentacao m : lista) {
            out.add(List.of(Fmt.dateTimeUtc(m.criadoEm()), m.produtoCodigo(), m.produtoNome(), TIPOS.getOrDefault(m.tipo(), m.tipo()),
                    Fmt.qty(m.quantidade()), Fmt.qty(m.estoqueAnterior()), Fmt.qty(m.estoqueNovo()), m.motivo(), m.usuarioNome()));
        }
        return out;
    }

    private void exportar() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Exportar movimentações");
        fc.setInitialFileName("movimentacoes.csv");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File f = fc.showSaveDialog(janela());
        if (f == null) {
            return;
        }
        try {
            Csv.save(f.toPath(), cabecalhoCsv(), linhasCsv(exibidas));
            sucesso("CSV gravado em " + f.getName() + ".");
        } catch (IOException e) {
            Toasts.error(janela(), "Não foi possível gravar o arquivo: " + e.getMessage());
        }
    }

    /** Histórico de um produto numa janela (botão "Movimentações" da lista de produtos). */
    public static void abrirDoProduto(Window dono, AppContext ctx, Produto p) {
        TableView<Movimentacao> t = Ui.tabela();
        t.setPrefHeight(360);
        Ui.col(t, "Data", m -> Fmt.dateTimeUtc(m.criadoEm()), 120);
        Ui.col(t, "Tipo", m -> TIPOS.getOrDefault(m.tipo(), m.tipo()), 90);
        Ui.colNum(t, "Qtd.", m -> Fmt.qty(m.quantidade()), 70);
        Ui.colNum(t, "Antes", m -> Fmt.qty(m.estoqueAnterior()), 70);
        Ui.colNum(t, "Depois", m -> Fmt.qty(m.estoqueNovo()), 70);
        Ui.col(t, "Motivo", Movimentacao::motivo, 220);
        Ui.col(t, "Usuário", Movimentacao::usuarioNome, 120);
        Label total = Ui.muted("Carregando…");
        VBox corpo = new VBox(8, t, total);
        Fx.async(() -> ctx.produtos.movimentacoes(p.id()), lista -> {
            t.setItems(FXCollections.observableArrayList(lista));
            total.setText(resumo(lista));
        }, e -> total.setText(Fx.message(e)));
        Modal.informativo(dono, "Movimentações de " + p.nome(), "Saldo atual: " + Fmt.qty(p.estoque()) + " " + p.unidade(), corpo,
                780, 0).mostrar();
    }
}
