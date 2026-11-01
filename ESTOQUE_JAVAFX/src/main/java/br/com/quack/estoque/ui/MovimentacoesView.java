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
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.HBox;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToggleButton;
import javafx.geometry.Pos;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/** Ajustes avulsos (entrada, saída, uso interno, perda) e histórico de movimentações com filtro e CSV. */
public class MovimentacoesView extends Tela {
    private static final Map<String, String> TIPOS = Map.of("entrada", "Entrada", "saida", "Saída", "uso_interno",
            "Uso interno", "perda", "Perda", "inventario", "Inventário");
    private static final List<String> TIPOS_AJUSTE = List.of("entrada", "saida", "uso_interno", "perda");

    private Produto produto;
    private final Label produtoNome = new Label();
    private final Label produtoDetalhe = Ui.muted("");
    private final Label unidade = Ui.texto("", "rg-muted");
    private final javafx.beans.property.SimpleStringProperty tipo = new javafx.beans.property.SimpleStringProperty("perda");
    private final javafx.scene.layout.FlowPane sugestoes = new javafx.scene.layout.FlowPane();
    private final TextField filtroTexto = Ui.campo("Produto");
    private VBox loteBox;
    private VBox validadeBox;
    private final TextField quantidade = Ui.campo("Quantidade");
    private final TextField motivo = Ui.campo("Motivo (obrigatório)");
    private final TextField lote = Ui.campo("Nº do lote");
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

        // ---------------------------------------------------------------- lançamento
        produtoNome.getStyleClass().add("rg-section-title");
        produtoNome.setWrapText(true);
        VBox produtoInfo = new VBox(2, produtoNome, produtoDetalhe);
        HBox.setHgrow(produtoInfo, Priority.ALWAYS);
        javafx.scene.control.Button escolherBtn = Ui.botao("Escolher produto…", this::escolher);
        HBox linhaProduto = new HBox(12, escolherBtn, produtoInfo);
        linhaProduto.setAlignment(Pos.CENTER_LEFT);

        ToggleGroup grupo = new ToggleGroup();
        HBox tiposBox = new HBox(0);
        tiposBox.getStyleClass().add("rg-seg");
        for (String t : TIPOS_AJUSTE) {
            ToggleButton b = new ToggleButton(TIPOS.get(t));
            b.setUserData(t);
            b.setToggleGroup(grupo);
            b.getStyleClass().addAll("rg-seg-btn", "rg-seg-" + t);
            b.setMinWidth(Region.USE_PREF_SIZE);
            b.setFocusTraversable(false);
            tiposBox.getChildren().add(b);
            if (t.equals(tipo.get())) {
                b.setSelected(true);
            }
        }
        grupo.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) {
                a.setSelected(true); // sempre um tipo marcado
                return;
            }
            tipo.set((String) b.getUserData());
        });
        tipo.addListener((o, a, b) -> {
            atualizarCamposEntrada();
            atualizarSugestoes();
            atualizarPrevia();
        });
        quantidade.textProperty().addListener((o, a, b) -> atualizarPrevia());
        quantidade.setPrefWidth(150);
        quantidade.setOnAction(e -> motivo.requestFocus());
        motivo.setOnAction(e -> lancar());
        HBox.setHgrow(motivo, Priority.ALWAYS);
        validade.setPromptText("dd/mm/aaaa");
        validade.setPrefWidth(160);
        lote.setPrefWidth(160);

        VBox qtdBox = Ui.rotulado("Quantidade", new HBox(6, quantidade, unidade));
        ((HBox) qtdBox.getChildren().get(1)).setAlignment(Pos.CENTER_LEFT);
        VBox motivoBox = Ui.rotulado("Motivo", motivo);
        HBox.setHgrow(motivoBox, Priority.ALWAYS);
        loteBox = Ui.rotulado("Lote (opcional)", lote);
        validadeBox = Ui.rotulado("Validade (opcional)", validade);
        HBox campos = new HBox(10, qtdBox, motivoBox, loteBox, validadeBox);
        campos.setAlignment(Pos.BOTTOM_LEFT);

        sugestoes.setHgap(6);
        sugestoes.setVgap(6);
        previa.getStyleClass().add("rg-saldo-previa");
        javafx.scene.control.Button lancarBtn = Ui.primario("Lançar ajuste", this::lancar);
        HBox rodape = new HBox(12, sugestoes, Ui.espaco(), previa, lancarBtn);
        HBox.setHgrow(sugestoes, Priority.SOMETIMES);
        rodape.setAlignment(Pos.CENTER_LEFT);

        VBox form = new VBox(12, linhaProduto, Ui.rotulado("Tipo do ajuste", tiposBox), campos, rodape);
        form.getStyleClass().add("rg-card");
        getChildren().add(form);
        atualizarCamposEntrada();
        atualizarSugestoes();
        mostrarProduto();

        // ---------------------------------------------------------------- histórico
        Ui.col(tabela, "Data", m -> Fmt.dateTimeUtc(m.criadoEm()), 132);
        Ui.col(tabela, "Código", Movimentacao::produtoCodigo, 120);
        Ui.col(tabela, "Produto", this::nomeProduto, 240);
        Ui.col(tabela, "Tipo", m -> TIPOS.getOrDefault(m.tipo(), m.tipo()), 96);
        Ui.colNum(tabela, "Qtd.", m -> Fmt.qty(m.quantidade()), 70);
        Ui.colNum(tabela, "Antes", m -> Fmt.qty(m.estoqueAnterior()), 70);
        Ui.colNum(tabela, "Depois", m -> Fmt.qty(m.estoqueNovo()), 70);
        Ui.col(tabela, "Motivo", Movimentacao::motivo, 220);
        Ui.col(tabela, "Usuário", Movimentacao::usuarioNome, 130);

        filtroTexto.setPromptText("Código ou nome do produto");
        filtroTexto.setPrefWidth(240);
        filtroTexto.textProperty().addListener((o, a, b) -> aplicarFiltroTexto());
        de.setPrefWidth(150);
        ate.setPrefWidth(150);
        filtroTipo.setPrefWidth(150);
        getChildren().add(Ui.texto("Histórico", "rg-section-title"));
        getChildren().add(Ui.barra(Ui.rotulado("Tipo", filtroTipo), Ui.rotulado("De", de), Ui.rotulado("Até", ate),
                Ui.rotulado("Produto", filtroTexto), Ui.botao("Filtrar", this::carregar), Ui.botao("Exportar CSV", this::exportar)));
        VBox.setVgrow(tabela, Priority.ALWAYS);
        getChildren().addAll(tabela, total);
    }

    /** Sugestões de motivo por tipo (um clique preenche o campo). */
    private static final Map<String, List<String>> MOTIVOS = Map.of(
            "entrada", List.of("Recebimento sem nota", "Devolução de cliente", "Correção de contagem"),
            "saida", List.of("Devolução ao fornecedor", "Transferência", "Correção de contagem"),
            "uso_interno", List.of("Consumo da loja", "Limpeza", "Degustação"),
            "perda", List.of("Vencido", "Avariado", "Quebra", "Furto/extravio"));

    private void atualizarSugestoes() {
        sugestoes.getChildren().clear();
        for (String m : MOTIVOS.getOrDefault(tipo.get(), List.of())) {
            javafx.scene.control.Button b = Ui.pequeno(m, () -> {
                motivo.setText(m);
                motivo.requestFocus();
                motivo.end();
            });
            b.setFocusTraversable(false);
            sugestoes.getChildren().add(b);
        }
    }

    /** Lote e validade só fazem sentido na entrada. */
    private void atualizarCamposEntrada() {
        boolean entrada = "entrada".equals(tipo.get());
        for (VBox b : new VBox[] {loteBox, validadeBox}) {
            b.setVisible(entrada);
            b.setManaged(entrada);
        }
    }

    private void mostrarProduto() {
        if (produto == null) {
            produtoNome.setText("Nenhum produto escolhido");
            produtoDetalhe.setText("Escolha o produto (ou abra pela ficha do produto com F4).");
            unidade.setText("");
            return;
        }
        produtoNome.setText(produto.codigo() + " · " + produto.nome());
        produtoDetalhe.setText("Unidade " + produto.unidade() + " · saldo atual " + Fmt.qty(produto.estoque()) + " "
                + produto.unidade());
        unidade.setText(produto.unidade());
    }

    /** Nome do produto da movimentação; vazio na API, busca no catálogo pelo código. */
    private String nomeProduto(Movimentacao m) {
        if (m.produtoNome() != null && !m.produtoNome().isBlank()) {
            return m.produtoNome();
        }
        Produto p = ctx.catalogo.porCodigoOuGtin(m.produtoCodigo());
        return p == null ? "" : p.nome();
    }

    private void aplicarFiltroTexto() {
        String f = Fmt.normalizar(filtroTexto.getText() == null ? "" : filtroTexto.getText().trim());
        List<Movimentacao> lista = f.isEmpty() ? exibidas : exibidas.stream()
                .filter(m -> Fmt.normalizar(m.produtoCodigo() + " " + nomeProduto(m)).contains(f)).toList();
        tabela.setItems(FXCollections.observableArrayList(lista));
        total.setText(resumo(lista));
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

    {
        filtroTipo.setValue("Todos");
        filtroTipo.setConverter(rotulos());
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
            mostrarProduto();
            atualizarPrevia();
            quantidade.requestFocus();
        });
    }

    /** Escolhe o produto de fora da tela (atalho F4 / botão da ficha). */
    public void selecionar(Produto p) {
        produto = p;
        mostrarProduto();
        atualizarPrevia();
    }

    private void atualizarPrevia() {
        if (produto == null) {
            previa.setText("");
            previa.setStyle("");
            return;
        }
        String un = " " + produto.unidade();
        try {
            BigDecimal q = Fmt.parse(quantidade.getText());
            BigDecimal depois = "entrada".equals(tipo.get()) ? produto.estoque().add(q) : produto.estoque().subtract(q);
            previa.setText("Saldo " + Fmt.qty(produto.estoque()) + " → " + Fmt.qty(depois) + un);
            previa.setStyle(depois.signum() < 0 ? "-fx-text-fill: -danger;" : "");
        } catch (DomainException e) {
            previa.setText("Saldo " + Fmt.qty(produto.estoque()) + un);
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
            String t = tipo.get();
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
            aplicarFiltroTexto();
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
            return LocalDateTime.parse(iso.trim().replace(' ', 'T')).atZone(ZoneId.of("UTC"))
                    .withZoneSameInstant(ZoneId.of("America/Sao_Paulo")).toLocalDate();
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
