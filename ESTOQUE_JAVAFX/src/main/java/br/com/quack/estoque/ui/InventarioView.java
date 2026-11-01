package br.com.quack.estoque.ui;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.domain.DomainException;
import br.com.quack.estoque.domain.Fmt;
import br.com.quack.estoque.domain.Perfil;
import br.com.quack.estoque.domain.Produto;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Inventário: lista, abertura (loja, categorias ou produtos), contagem com leitor e divergências/aplicação. */
public class InventarioView extends Tela {
    private final Perfil perfil;
    private final StackPane palco = new StackPane();
    private final VBox lista = new VBox(12);
    private final TableView<JsonNode> tabela = Ui.tabela();
    private ContagemPanel contagem;
    private DivergenciasPanel divergencias;

    public InventarioView(AppContext ctx, Navegador nav) {
        super(ctx, nav);
        this.perfil = ctx.session.perfil();
        getChildren().add(palco);
        VBox.setVgrow(palco, Priority.ALWAYS);

        Button novo = Ui.primario("Novo inventário", this::abrirNovo);
        lista.getChildren().add(Ui.cabecalho("Inventário", "Conte a prateleira, compare com o sistema e aplique o acerto (F6 abre a contagem).", novo));
        Ui.colNum(tabela, "Nº", n -> String.valueOf(n.path("numero").asInt()), 50);
        Ui.col(tabela, "Descrição", n -> n.path("descricao").asText(""), 260);
        Ui.col(tabela, "Escopo", n -> switch (n.path("escopo").asText("")) {
            case "loja" -> "Loja toda";
            case "categorias" -> "Categorias";
            default -> "Produtos";
        }, 90);
        Ui.col(tabela, "Situação", n -> rotuloStatus(n.path("status").asText("")), 100);
        Ui.colNum(tabela, "Contados", n -> n.path("contados").asInt() + " / " + n.path("itens").asInt(), 90);
        Ui.col(tabela, "Aberto por", n -> n.path("abertoPor").asText(""), 130);
        Ui.col(tabela, "Aberto em", n -> Fmt.dateTime(n.path("abertoEm").asText("")), 120);
        Button contar = Ui.primario("Contar", () -> {
            JsonNode n = tabela.getSelectionModel().getSelectedItem();
            if (n != null) {
                abrirContagem(n);
            }
        });
        Button diverg = Ui.botao("Divergências", () -> {
            JsonNode n = tabela.getSelectionModel().getSelectedItem();
            if (n != null) {
                abrirDivergencias(n);
            }
        });
        Button cancelar = Ui.perigo("Cancelar inventário", () -> {
            JsonNode n = tabela.getSelectionModel().getSelectedItem();
            if (n != null) {
                cancelar(n);
            }
        });
        cancelar.setVisible(perfil.aplicarOuCancelarInventario());
        cancelar.setManaged(perfil.aplicarOuCancelarInventario());
        Button trilha = Ui.botao("Trilha", () -> {
            JsonNode n = tabela.getSelectionModel().getSelectedItem();
            if (n != null) {
                verTrilha(n);
            }
        });
        trilha.setVisible(perfil.aplicarOuCancelarInventario());
        trilha.setManaged(perfil.aplicarOuCancelarInventario());
        lista.getChildren().addAll(Ui.barra(contar, diverg, trilha, cancelar), tabela);
        tabela.setOnMouseClicked(e -> {
            JsonNode n = tabela.getSelectionModel().getSelectedItem();
            if (e.getClickCount() == 2 && n != null) {
                if ("em_contagem".equals(n.path("status").asText())) {
                    abrirContagem(n);
                } else {
                    abrirDivergencias(n);
                }
            }
        });
        VBox.setVgrow(tabela, Priority.ALWAYS);
        palco.getChildren().add(lista);
    }

    static String rotuloStatus(String s) {
        return switch (s) {
            case "em_contagem" -> "Em contagem";
            case "aplicado" -> "Aplicado";
            case "cancelado" -> "Cancelado";
            default -> s;
        };
    }

    @Override
    public void ativar() {
        if (contagem != null && contagem.visivel()) {
            contagem.focar();
            return;
        }
        voltarParaLista();
    }

    /** F6: abre direto a contagem do inventário em andamento (ou a lista, se houver mais de um). */
    public void contarAtual() {
        rodar(() -> ctx.inventarios.listar("em_contagem"), data -> {
            List<JsonNode> abertos = new ArrayList<>();
            data.forEach(abertos::add);
            if (abertos.size() == 1) {
                abrirContagem(abertos.get(0));
            } else if (abertos.isEmpty()) {
                Toasts.info(janela(), "Não há inventário em contagem. Abra um novo.");
            }
        });
    }

    private void mostrar(Node n) {
        palco.getChildren().setAll(n);
    }

    private void voltarParaLista() {
        mostrar(lista);
        carregarLista();
    }

    private void carregarLista() {
        rodar(() -> ctx.inventarios.listar(null), data -> {
            List<JsonNode> itens = new ArrayList<>();
            if (data != null) {
                data.forEach(itens::add);
            }
            tabela.setItems(FXCollections.observableArrayList(itens));
        });
    }

    // ------------------------------------------------------------------ abrir

    private void abrirNovo() {
        TextField descricao = Ui.campo("Ex.: Inventário de bebidas — outubro");
        ToggleGroup g = new ToggleGroup();
        RadioButton loja = new RadioButton("Loja toda");
        RadioButton cats = new RadioButton("Departamentos / categorias");
        RadioButton prods = new RadioButton("Lista de produtos");
        loja.setToggleGroup(g);
        cats.setToggleGroup(g);
        prods.setToggleGroup(g);
        loja.setSelected(true);
        javafx.scene.control.ListView<br.com.quack.estoque.api.CadastroApi.Categoria> listaCats = new javafx.scene.control.ListView<>();
        listaCats.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        listaCats.setPrefHeight(150);
        listaCats.setCellFactory(l -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(br.com.quack.estoque.api.CadastroApi.Categoria c, boolean empty) {
                super.updateItem(c, empty);
                setText(empty || c == null ? null : c.caminho());
            }
        });
        listaCats.setVisible(false);
        listaCats.setManaged(false);
        javafx.scene.control.ListView<Produto> listaProds = new javafx.scene.control.ListView<>();
        listaProds.setPrefHeight(150);
        listaProds.setCellFactory(l -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Produto p, boolean empty) {
                super.updateItem(p, empty);
                setText(empty || p == null ? null : p.codigo() + " · " + p.nome());
            }
        });
        Button addProd = Ui.pequeno("Adicionar produto…", () -> ProdutoPicker.escolher(janela(), ctx, false, p -> {
            if (!listaProds.getItems().contains(p)) {
                listaProds.getItems().add(p);
            }
        }));
        Button rmProd = Ui.pequeno("Remover selecionado", () -> listaProds.getItems().remove(listaProds.getSelectionModel().getSelectedItem()));
        var acoesProd = Ui.barra(addProd, rmProd);
        listaProds.setVisible(false);
        listaProds.setManaged(false);
        acoesProd.setVisible(false);
        acoesProd.setManaged(false);
        g.selectedToggleProperty().addListener((o, a, b) -> {
            listaCats.setVisible(b == cats);
            listaCats.setManaged(b == cats);
            listaProds.setVisible(b == prods);
            listaProds.setManaged(b == prods);
            acoesProd.setVisible(b == prods);
            acoesProd.setManaged(b == prods);
        });
        Fx.async(ctx.cadastros::categorias, cs -> listaCats.setItems(FXCollections.observableArrayList(ordenarCats(cs))), e -> {
        });
        VBox corpo = new VBox(8, Ui.rotulado("Descrição", descricao), Ui.texto("O que será contado?", "rg-form-label"), loja, cats, listaCats,
                prods, listaProds, acoesProd,
                Ui.caixa("Só pode haver um inventário em contagem por escopo. Conte cada produto de uma vez: o que mexer no estoque entre a "
                        + "primeira e a última contagem do mesmo produto é tratado como posterior à contagem.", "rg-warn-box"));
        Modal[] m = new Modal[1];
        m[0] = Modal.formularioRolavel(janela(), "Novo inventário", null, corpo, "Abrir inventário", 560, 520, () -> {
            String escopo = g.getSelectedToggle() == cats ? "categorias" : g.getSelectedToggle() == prods ? "produtos" : "loja";
            List<String> catIds = listaCats.getSelectionModel().getSelectedItems().stream().map(c -> c.id()).toList();
            List<String> prodIds = listaProds.getItems().stream().map(Produto::id).toList();
            if (escopo.equals("categorias") && catIds.isEmpty()) {
                m[0].erro("Selecione ao menos uma categoria.");
                return false;
            }
            if (escopo.equals("produtos") && prodIds.isEmpty()) {
                m[0].erro("Adicione ao menos um produto.");
                return false;
            }
            String desc = descricao.getText() == null ? "" : descricao.getText().trim();
            m[0].ocupado(true);
            Fx.async(() -> ctx.inventarios.abrir(desc, escopo, catIds, prodIds, ctx.terminal()), r -> {
                m[0].ocupado(false);
                m[0].fechar();
                sucesso("Inventário #" + r.path("numero").asInt() + " aberto com " + r.path("itens").asInt() + " produtos.");
                carregarLista();
            }, t -> {
                m[0].ocupado(false);
                m[0].erro(Fx.message(t)); // sobreposição: a API informa o número do inventário aberto
            });
            return false;
        });
        m[0].mostrar();
        descricao.requestFocus();
    }

    private static List<br.com.quack.estoque.api.CadastroApi.Categoria> ordenarCats(List<br.com.quack.estoque.api.CadastroApi.Categoria> cs) {
        return CadastrosView.ordenar(cs.stream().filter(br.com.quack.estoque.api.CadastroApi.Categoria::ativa).toList());
    }

    private void cancelar(JsonNode n) {
        if (!"em_contagem".equals(n.path("status").asText())) {
            Toasts.error(janela(), "Só é possível cancelar um inventário em contagem.");
            return;
        }
        Modal.pedirTexto(janela(), "Cancelar inventário #" + n.path("numero").asInt(), "Motivo do cancelamento", "", "Cancelar inventário",
                motivo -> rodar(() -> {
                    ctx.inventarios.cancelar(n.path("id").asText(), motivo, ctx.terminal());
                    return true;
                }, ok -> {
                    sucesso("Inventário cancelado. O estoque não foi alterado.");
                    carregarLista();
                }));
    }

    // ------------------------------------------------------------------ painéis

    private void abrirContagem(JsonNode n) {
        if (!"em_contagem".equals(n.path("status").asText())) {
            Toasts.error(janela(), "Este inventário está " + rotuloStatus(n.path("status").asText()).toLowerCase() + " e não aceita contagens.");
            return;
        }
        contagem = new ContagemPanel(ctx, n.path("id").asText(), n.path("numero").asInt(), n.path("descricao").asText(), this::voltarParaLista,
                () -> abrirDivergencias(n));
        mostrar(contagem);
        contagem.iniciar();
    }

    private void abrirDivergencias(JsonNode n) {
        divergencias = new DivergenciasPanel(ctx, n.path("id").asText(), n.path("numero").asInt(), this::voltarParaLista,
                () -> abrirContagem(n));
        mostrar(divergencias);
        divergencias.carregar();
    }

    private void verTrilha(JsonNode n) {
        rodar(() -> ctx.inventarios.trilha(n.path("id").asText()), t -> DivergenciasPanel.mostrarTrilha(janela(), n.path("numero").asInt(), t));
    }

    // =====================================================================
    // Contagem
    // =====================================================================

    /** Contagem contínua: bipa → quantidade (padrão 1) → local → Enter; fila local se a API cair. */
    static final class ContagemPanel extends VBox {
        private record Pendente(String produtoId, String nome, java.math.BigDecimal quantidade, String local) {
        }

        private final AppContext ctx;
        private final String inventarioId;
        private final TextField leitura = Ui.campo("Bipe o código de barras ou digite o código / GTIN e tecle Enter");
        private final TextField quantidade = new TextField("1");
        private final ComboBox<String> local = Ui.combo(List.of("Prateleira", "Depósito", "Câmara fria", "Loja"));
        private final CheckBox direto = Ui.check("Registrar 1 a cada leitura (produtos em UN)", false);
        private final Label produtoAtual = Ui.texto("Aguardando leitura…", "rg-section-title");
        private final Label total = Ui.texto("", "rg-count-big");
        private final Label aviso = Ui.texto("", "rg-hint");
        private final Label progresso = Ui.muted("");
        private final TableView<br.com.quack.estoque.api.InventarioApi.Contagem> ultimos = Ui.tabela();
        private final List<Pendente> pendentes = new ArrayList<>();
        private final java.util.Set<String> escopo = new java.util.HashSet<>();
        private Produto atual;
        private java.math.BigDecimal quantidadeSugerida = java.math.BigDecimal.ONE;
        private javafx.animation.Timeline reenvio;
        private boolean enviando;

        ContagemPanel(AppContext ctx, String inventarioId, int numero, String descricao, Runnable voltar, Runnable divergencias) {
            super(10);
            this.ctx = ctx;
            this.inventarioId = inventarioId;
            Button v = Ui.botao("‹ Voltar", () -> {
                parar();
                voltar.run();
            });
            Button d = Ui.botao("Ver divergências", () -> {
                parar();
                divergencias.run();
            });
            getChildren().add(Ui.cabecalho("Contagem · inventário #" + numero, descricao, v, d));

            leitura.getStyleClass().add("rg-scan-field");
            quantidade.getStyleClass().add("rg-count-input");
            quantidade.setPrefWidth(130);
            local.setEditable(true);
            local.setValue("Prateleira");
            local.setPrefWidth(190);
            leitura.setOnAction(e -> aoLer());
            quantidade.setOnAction(e -> registrar());
            Button ok = Ui.primario("Registrar (Enter)", this::registrar);
            javafx.scene.layout.HBox.setHgrow(leitura, Priority.ALWAYS);
            getChildren().add(Ui.barra(leitura));
            getChildren().add(Ui.barra(Ui.rotulado("Quantidade", quantidade), Ui.rotulado("Local", local), ok, direto));
            getChildren().addAll(produtoAtual, total, aviso, progresso);

            Ui.col(ultimos, "Hora", c -> Fmt.dateTime(c.criadoEm()), 110);
            Ui.col(ultimos, "Produto", c -> c.codigo() + " · " + c.nome(), 280);
            Ui.colNum(ultimos, "Qtd.", c -> Fmt.qty(c.quantidade()), 70);
            Ui.col(ultimos, "Local", c -> Ui.nz(c.local()), 110);
            Ui.col(ultimos, "Situação", c -> c.anulada() ? "Anulada" : "Válida", 80);
            ultimos.setPrefHeight(230);
            Button anular = Ui.botao("Anular lançamento selecionado", this::anular);
            getChildren().addAll(Ui.texto("Seus últimos lançamentos", "rg-section-title"), ultimos, Ui.barra(anular));
        }

        boolean visivel() {
            return getScene() != null && isVisible() && getParent() != null;
        }

        void focar() {
            leitura.requestFocus();
        }

        void iniciar() {
            ctx.rascunhos.ler("contagem-" + inventarioId);
            carregarPendentes();
            Fx.async(() -> {
                ctx.garantirCatalogo(false);
                return ctx.inventarios.itens(inventarioId, null);
            }, itens -> {
                itens.forEach(i -> escopo.add(i.produtoId()));
                long contados = itens.stream().filter(i -> i.contado() != null).count();
                progresso.setText("Escopo: " + itens.size() + " produtos · " + contados + " já contados");
            }, t -> aviso("Não foi possível carregar o escopo: " + Fx.message(t), true));
            atualizarUltimos();
            reenvio = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(10), e -> reenviar()));
            reenvio.setCycleCount(javafx.animation.Animation.INDEFINITE);
            reenvio.play();
            javafx.application.Platform.runLater(this::focar);
        }

        void parar() {
            if (reenvio != null) {
                reenvio.stop();
            }
        }

        private void aviso(String msg, boolean erro) {
            aviso.setText(msg);
            aviso.setStyle(erro ? "-fx-text-fill: -danger; -fx-font-weight: bold; -fx-font-size: 14px;" : "-fx-text-fill: -success;");
            if (erro) {
                try {
                    java.awt.Toolkit.getDefaultToolkit().beep();
                } catch (RuntimeException | java.awt.AWTError ignored) {
                    // sem som: o destaque visual basta
                }
            }
        }

        /** Resolve o código lido: produto + quantidade sugerida (peso, se for etiqueta de balança). */
        private void aoLer() {
            String codigo = leitura.getText() == null ? "" : leitura.getText().trim();
            if (codigo.isEmpty()) {
                return;
            }
            var l = ctx.catalogo.ler(codigo);
            leitura.clear();
            if (l == null) {
                produtoAtual.setText("Código desconhecido: " + codigo);
                total.setText("");
                atual = null;
                aviso("Código desconhecido: nada foi registrado.", true);
                focar();
                return;
            }
            if (!escopo.isEmpty() && !escopo.contains(l.produto().id())) {
                produtoAtual.setText(l.produto().codigo() + " · " + l.produto().nome());
                total.setText("");
                atual = null;
                aviso("Produto fora do escopo deste inventário: nada foi registrado.", true);
                focar();
                return;
            }
            atual = l.produto();
            quantidadeSugerida = l.quantidade();
            produtoAtual.setText(atual.codigo() + " · " + atual.nome() + "  (" + atual.unidade() + ")");
            aviso(l.balanca() ? "Etiqueta de balança: peso " + Fmt.qty(l.quantidade()) + " " + atual.unidade() : "", false);
            if (l.balanca() || atual.fracionavel()) {
                quantidade.setText(Fmt.qty(l.balanca() ? l.quantidade() : java.math.BigDecimal.ONE));
            } else {
                quantidade.setText("1");
            }
            total.setText("");
            if (direto.isSelected() && !atual.fracionavel() && !l.balanca()) {
                registrar();
            } else {
                quantidade.requestFocus();
                quantidade.selectAll();
            }
        }

        private void registrar() {
            if (atual == null) {
                aviso("Bipe um produto antes de registrar.", true);
                focar();
                return;
            }
            java.math.BigDecimal q;
            try {
                q = Ui.numero(quantidade, "a quantidade");
                if (q.signum() < 0) {
                    throw new DomainException("A quantidade não pode ser negativa.");
                }
                if (!atual.fracionavel() && q.stripTrailingZeros().scale() > 0) {
                    throw new DomainException("Produto em UN: informe uma quantidade inteira. Fração só para kg, litro, etc.");
                }
            } catch (DomainException e) {
                aviso(e.getMessage(), true);
                quantidade.requestFocus();
                return;
            }
            Produto alvo = atual;
            String loc = local.getValue() == null ? "" : local.getValue().trim();
            atual = null;
            quantidade.setText("1");
            leitura.requestFocus();
            Fx.async(() -> ctx.inventarios.contar(inventarioId, alvo.id(), null, q, loc, ctx.terminal()), r -> {
                produtoAtual.setText(r.codigo() + " · " + r.nome());
                total.setText("Total contado: " + Fmt.qty(r.totalContado()) + " " + r.unidade());
                aviso("Registrado: +" + Fmt.qty(q) + " (" + loc + ")", false);
                atualizarUltimos();
            }, t -> {
                if (t instanceof br.com.quack.estoque.api.ApiException ae && ae.isTransient()) {
                    pendentes.add(new Pendente(alvo.id(), alvo.nome(), q, loc));
                    salvarPendentes();
                    aviso("Sem conexão: lançamento guardado neste computador (" + pendentes.size() + " pendente(s)). Será reenviado sozinho.", true);
                } else {
                    aviso(Fx.message(t), true);
                }
            });
        }

        private void atualizarUltimos() {
            Fx.async(() -> ctx.inventarios.contagens(inventarioId, false, null, 15), lista -> ultimos.setItems(FXCollections.observableArrayList(lista)),
                    t -> {
                    });
        }

        private void anular() {
            var c = ultimos.getSelectionModel().getSelectedItem();
            if (c == null || c.anulada()) {
                aviso("Selecione um lançamento válido para anular.", true);
                return;
            }
            Modal.pedirTexto(Ui.janela(this), "Anular lançamento", "Motivo (ex.: lancei 240 no lugar de 24)", "Lançamento errado", "Anular", motivo ->
                    Fx.async(() -> {
                        ctx.inventarios.anular(inventarioId, c.id(), motivo);
                        return true;
                    }, ok -> {
                        aviso("Lançamento anulado (continua visível na trilha).", false);
                        atualizarUltimos();
                    }, t -> aviso(Fx.message(t), true)));
        }

        // ---- fila local quando a API cai

        private void salvarPendentes() {
            var arr = br.com.quack.estoque.persistence.JsonFiles.MAPPER.createArrayNode();
            for (Pendente p : pendentes) {
                var o = arr.addObject();
                o.put("produtoId", p.produtoId());
                o.put("nome", p.nome());
                o.put("quantidade", p.quantidade().toPlainString());
                o.put("local", p.local());
            }
            if (pendentes.isEmpty()) {
                ctx.rascunhos.apagar("contagem-" + inventarioId);
            } else {
                ctx.rascunhos.salvar("contagem-" + inventarioId, arr);
            }
        }

        private void carregarPendentes() {
            JsonNode arr = ctx.rascunhos.ler("contagem-" + inventarioId);
            pendentes.clear();
            if (arr != null && arr.isArray()) {
                for (JsonNode o : arr) {
                    pendentes.add(new Pendente(o.path("produtoId").asText(), o.path("nome").asText(),
                            new java.math.BigDecimal(o.path("quantidade").asText("0")), o.path("local").asText("")));
                }
            }
            if (!pendentes.isEmpty()) {
                aviso(pendentes.size() + " lançamento(s) de uma contagem anterior ainda não foram enviados; vou reenviar agora.", true);
                reenviar();
            }
        }

        private void reenviar() {
            if (enviando || pendentes.isEmpty()) {
                return;
            }
            enviando = true;
            List<Pendente> lote = List.copyOf(pendentes);
            Fx.async(() -> {
                int enviados = 0;
                for (Pendente p : lote) {
                    ctx.inventarios.contar(inventarioId, p.produtoId(), null, p.quantidade(), p.local(), ctx.terminal());
                    enviados++;
                }
                return enviados;
            }, n -> {
                enviando = false;
                pendentes.removeAll(lote);
                salvarPendentes();
                aviso(n + " lançamento(s) pendente(s) enviados.", false);
                atualizarUltimos();
            }, t -> {
                enviando = false;
                if (!(t instanceof br.com.quack.estoque.api.ApiException ae && ae.isTransient())) {
                    // recusado pela API (ex.: inventário encerrado): não adianta insistir
                    aviso("Lançamentos pendentes recusados: " + Fx.message(t), true);
                    pendentes.clear();
                    salvarPendentes();
                }
            });
        }
    }

    // =====================================================================
    // Divergências e aplicação
    // =====================================================================

    static final class DivergenciasPanel extends VBox {
        private final AppContext ctx;
        private final Perfil perfil;
        private final String inventarioId;
        private final int numero;
        private final Label resumo = Ui.muted("");
        private final TableView<br.com.quack.estoque.api.InventarioApi.Item> tabela = Ui.tabela();
        private final ToggleGroup filtros = new ToggleGroup();
        private String statusInv = "";
        private Runnable aoContarMais;
        private JsonNode ultimoResumo;

        DivergenciasPanel(AppContext ctx, String inventarioId, int numero, Runnable voltar, Runnable contarMais) {
            super(10);
            this.ctx = ctx;
            this.perfil = ctx.session.perfil();
            this.inventarioId = inventarioId;
            this.numero = numero;
            this.aoContarMais = contarMais;
            Button v = Ui.botao("‹ Voltar", voltar);
            Button atualizar = Ui.botao("Atualizar", this::carregar);
            Button csv = Ui.botao("Exportar CSV", this::exportar);
            Button contar = Ui.botao("Continuar contando", () -> aoContarMais.run());
            Button recontar = Ui.botao("Pedir recontagem", this::recontar);
            recontar.setVisible(perfil.aplicarOuCancelarInventario());
            recontar.setManaged(perfil.aplicarOuCancelarInventario());
            Button aplicar = Ui.primario("Aplicar acerto…", this::aplicar);
            aplicar.setVisible(perfil.aplicarOuCancelarInventario());
            aplicar.setManaged(perfil.aplicarOuCancelarInventario());
            getChildren().add(Ui.cabecalho("Divergências · inventário #" + numero, null, v, atualizar, csv));

            javafx.scene.layout.HBox chips = new javafx.scene.layout.HBox(8);
            for (String[] f : new String[][] {{"", "Todos"}, {"divergentes", "Divergentes"}, {"nao_contados", "Não contados"},
                {"recontar", "Recontar"}, {"contados", "Contados"}}) {
                RadioButton b = new RadioButton(f[1]);
                b.setUserData(f[0]);
                b.setToggleGroup(filtros);
                chips.getChildren().add(b);
                if (f[0].equals("divergentes")) {
                    b.setSelected(true);
                }
            }
            filtros.selectedToggleProperty().addListener((o, a, b) -> carregar());
            getChildren().addAll(resumo, Ui.barra(chips, Ui.espaco(), contar, recontar, aplicar));

            Ui.col(tabela, "Código", i -> i.codigo(), 90);
            Ui.col(tabela, "Produto", i -> i.nome(), 280);
            Ui.col(tabela, "Un", i -> i.unidade(), 44);
            Ui.colNum(tabela, "Sistema na contagem", i -> i.saldoNa1aContagem() == null ? "—" : Fmt.qty(i.saldoNa1aContagem()), 130);
            Ui.colNum(tabela, "Contado", i -> i.contado() == null ? "—" : Fmt.qty(i.contado()), 80);
            Ui.colNum(tabela, "Diferença", i -> i.diferenca() == null ? "—" : Fmt.qty(i.diferenca()), 80);
            if (perfil.verCustos()) {
                Ui.colNum(tabela, "Valor (custo)", i -> i.valorDiferenca() == null ? "—" : Fmt.money(i.valorDiferenca()), 100);
            }
            Ui.col(tabela, "1ª contagem", i -> Fmt.dateTime(i.primeiraContagemEm()), 120);
            Ui.col(tabela, "Situação", DivergenciasPanel::situacao, 120);
            tabela.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
            tabela.setRowFactory(t -> new javafx.scene.control.TableRow<>() {
                @Override
                protected void updateItem(br.com.quack.estoque.api.InventarioApi.Item i, boolean empty) {
                    super.updateItem(i, empty);
                    getStyleClass().removeAll("rg-row-danger", "rg-row-warn");
                    if (!empty && i != null) {
                        if (i.recontar()) {
                            getStyleClass().add("rg-row-warn");
                        } else if (i.diferenca() != null && i.diferenca().signum() != 0) {
                            getStyleClass().add("rg-row-danger");
                        }
                    }
                }
            });
            VBox.setVgrow(tabela, Priority.ALWAYS);
            getChildren().add(tabela);
        }

        static String situacao(br.com.quack.estoque.api.InventarioApi.Item i) {
            if (i.saldoDepois() != null) {
                return "Ajustado → " + Fmt.qty(i.saldoDepois());
            }
            if (i.recontar()) {
                return "Recontar";
            }
            if (i.contado() == null) {
                return "Não contado";
            }
            return i.diferenca() == null || i.diferenca().signum() == 0 ? "Confere" : (i.diferenca().signum() > 0 ? "Sobra" : "Falta");
        }

        void carregar() {
            String filtro = filtros.getSelectedToggle() == null ? "divergentes" : (String) filtros.getSelectedToggle().getUserData();
            Fx.async(() -> new Object[] {ctx.inventarios.obter(inventarioId), ctx.inventarios.itens(inventarioId, filtro)}, r -> {
                ultimoResumo = (JsonNode) r[0];
                @SuppressWarnings("unchecked")
                List<br.com.quack.estoque.api.InventarioApi.Item> itens = (List<br.com.quack.estoque.api.InventarioApi.Item>) r[1];
                tabela.setItems(FXCollections.observableArrayList(itens));
                JsonNode inv = ultimoResumo.path("inventario");
                statusInv = inv.path("status").asText("");
                JsonNode s = ultimoResumo.path("resumo");
                String valores = perfil.verCustos() ? " · sobra " + Fmt.money(s.path("sobra").decimalValue()) + " · falta "
                        + Fmt.money(s.path("falta").decimalValue()) + " · líquido " + Fmt.money(s.path("liquido").decimalValue()) : "";
                resumo.setText(inv.path("descricao").asText("") + " · " + rotuloStatus(statusInv) + " · " + s.path("itens").asInt() + " produtos · "
                        + s.path("contados").asInt() + " contados · " + s.path("naoContados").asInt() + " não contados · "
                        + s.path("divergentes").asInt() + " divergentes · " + s.path("recontar").asInt() + " a recontar" + valores);
            }, t -> Toasts.error(Ui.janela(this), Fx.message(t)));
        }

        private void recontar() {
            var sel = new ArrayList<>(tabela.getSelectionModel().getSelectedItems());
            if (sel.isEmpty()) {
                Toasts.error(Ui.janela(this), "Selecione os produtos a recontar.");
                return;
            }
            List<String> ids = sel.stream().map(i -> i.produtoId()).toList();
            Fx.async(() -> {
                ctx.inventarios.recontar(inventarioId, ids);
                return true;
            }, ok -> {
                Toasts.success(Ui.janela(this), ids.size() + " produto(s) marcados para recontagem.");
                carregar();
            }, t -> Toasts.error(Ui.janela(this), Fx.message(t)));
        }

        private void aplicar() {
            if (!"em_contagem".equals(statusInv)) {
                Toasts.error(Ui.janela(this), "Este inventário está " + rotuloStatus(statusInv).toLowerCase() + ".");
                return;
            }
            JsonNode s = ultimoResumo.path("resumo");
            TextField justificativa = Ui.campo("Justificativa do acerto (mínimo 5 caracteres)");
            PasswordField senha = new PasswordField();
            senha.setPromptText("Senha do gerente");
            CheckBox zerar = Ui.check("Zerar os produtos não contados (" + s.path("naoContados").asInt() + ")", false);
            Label texto = Ui.muted("Serão ajustados " + (s.path("contados").asInt()) + " produtos contados"
                    + (perfil.verCustos() ? " (sobra " + Fmt.money(s.path("sobra").decimalValue()) + ", falta " + Fmt.money(s.path("falta").decimalValue())
                            + ", líquido " + Fmt.money(s.path("liquido").decimalValue()) + ")" : "")
                    + ". O ajuste respeita as vendas e entradas feitas depois da contagem. Esta operação não pode ser desfeita.");
            VBox corpo = new VBox(8, texto, Ui.rotulado("Justificativa", justificativa),
                    Ui.rotulado("Senha de " + ctx.session.user().name() + " (gerente)", senha), zerar,
                    Ui.caixa("Produtos marcados para recontagem não são ajustados.", "rg-warn-box"));
            Modal[] m = new Modal[1];
            m[0] = Modal.formulario(Ui.janela(this), "Aplicar acerto do inventário #" + numero, corpo, "Aplicar acerto", 520, () -> {
                String j = justificativa.getText() == null ? "" : justificativa.getText().trim();
                if (j.length() < 5) {
                    m[0].erro("Informe uma justificativa de no mínimo 5 caracteres.");
                    return false;
                }
                if (senha.getText().isEmpty()) {
                    m[0].erro("Informe a senha do gerente.");
                    return false;
                }
                m[0].ocupado(true);
                Fx.async(() -> ctx.inventarios.aplicar(inventarioId, ctx.session.user().id(), senha.getText(), j, zerar.isSelected(), ctx.terminal()),
                        r -> {
                            m[0].ocupado(false);
                            m[0].fechar();
                            Toasts.success(Ui.janela(this), "Inventário aplicado: " + r.path("ajustados").asInt() + " produtos ajustados.");
                            ctx.catalogo.invalidar();
                            carregar();
                        }, t -> {
                            m[0].ocupado(false);
                            senha.clear();
                            m[0].erro(Fx.message(t));
                        });
                return false;
            });
            m[0].mostrar();
            justificativa.requestFocus();
        }

        private void exportar() {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setInitialFileName("inventario-" + numero + ".csv");
            fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("CSV", "*.csv"));
            java.io.File f = fc.showSaveDialog(Ui.janela(this));
            if (f == null) {
                return;
            }
            List<List<String>> linhas = new ArrayList<>();
            for (var i : tabela.getItems()) {
                linhas.add(List.of(i.codigo(), i.nome(), i.unidade(), i.saldoNa1aContagem() == null ? "" : Fmt.qty(i.saldoNa1aContagem()),
                        i.contado() == null ? "" : Fmt.qty(i.contado()), i.diferenca() == null ? "" : Fmt.qty(i.diferenca()),
                        perfil.verCustos() && i.valorDiferenca() != null ? Fmt.dec2(i.valorDiferenca()) : "", situacao(i)));
            }
            try {
                br.com.quack.estoque.domain.Csv.save(f.toPath(), List.of("Código", "Produto", "Un", "Sistema na contagem", "Contado", "Diferença",
                        "Valor (custo)", "Situação"), linhas);
                Toasts.success(Ui.janela(this), "CSV gravado em " + f.getName() + ".");
            } catch (java.io.IOException e) {
                Toasts.error(Ui.janela(this), "Não foi possível gravar: " + e.getMessage());
            }
        }

        /** Trilha (gerente/administrador): eventos, contagens e antes/depois de cada produto ajustado. */
        static void mostrarTrilha(javafx.stage.Window dono, int numero, JsonNode t) {
            TableView<JsonNode> eventos = Ui.tabela();
            eventos.setPrefHeight(170);
            Ui.col(eventos, "Quando", e -> Fmt.dateTime(e.path("ocorridoEm").asText("")), 115);
            Ui.col(eventos, "Evento", e -> switch (e.path("tipo").asText("")) {
                case "aberto" -> "Aberto";
                case "recontagem" -> "Recontagem";
                case "aplicacao_negada" -> "Aplicação NEGADA";
                case "aplicado" -> "Aplicado";
                case "cancelado" -> "Cancelado";
                default -> e.path("tipo").asText("");
            }, 120);
            Ui.col(eventos, "Usuário", e -> e.path("usuario").asText(""), 110);
            Ui.col(eventos, "Gerente", e -> e.path("gerente").asText(""), 110);
            Ui.col(eventos, "IP", e -> e.path("ip").asText(""), 100);
            Ui.col(eventos, "Descrição", e -> e.path("descricao").asText(""), 360);
            List<JsonNode> ev = new ArrayList<>();
            t.path("eventos").forEach(ev::add);
            eventos.setItems(FXCollections.observableArrayList(ev));

            TableView<JsonNode> contagens = Ui.tabela();
            contagens.setPrefHeight(170);
            Ui.col(contagens, "Quando", c -> Fmt.dateTime(c.path("criadoEm").asText("")), 115);
            Ui.col(contagens, "Produto", c -> c.path("codigo").asText("") + " · " + c.path("nome").asText(""), 250);
            Ui.colNum(contagens, "Qtd.", c -> Fmt.qty(c.path("quantidade").decimalValue()), 60);
            Ui.col(contagens, "Local", c -> c.path("local").asText(""), 90);
            Ui.col(contagens, "Contou", c -> c.path("usuario").asText(""), 110);
            Ui.colNum(contagens, "Sistema", c -> Fmt.qty(c.path("saldoSistema").decimalValue()), 70);
            Ui.col(contagens, "Anulação", c -> c.hasNonNull("anuladaPor") ? c.path("anuladaPor").asText() + ": " + c.path("anuladaMotivo").asText() : "", 230);
            List<JsonNode> ct = new ArrayList<>();
            t.path("contagens").forEach(ct::add);
            contagens.setItems(FXCollections.observableArrayList(ct));

            TableView<JsonNode> itens = Ui.tabela();
            itens.setPrefHeight(170);
            Ui.col(itens, "Produto", i -> i.path("codigo").asText("") + " · " + i.path("nome").asText(""), 280);
            Ui.colNum(itens, "Antes", i -> i.hasNonNull("saldoAntes") ? Fmt.qty(i.path("saldoAntes").decimalValue()) : "", 70);
            Ui.colNum(itens, "Contado", i -> i.hasNonNull("contado") ? Fmt.qty(i.path("contado").decimalValue()) : "", 70);
            Ui.colNum(itens, "Diferença", i -> i.hasNonNull("delta") ? Fmt.qty(i.path("delta").decimalValue()) : "", 80);
            Ui.colNum(itens, "Depois", i -> i.hasNonNull("saldoDepois") ? Fmt.qty(i.path("saldoDepois").decimalValue()) : "", 70);
            Ui.colNum(itens, "Valor", i -> i.hasNonNull("valorDelta") ? Fmt.money(i.path("valorDelta").decimalValue()) : "", 90);
            List<JsonNode> it = new ArrayList<>();
            t.path("itens").forEach(it::add);
            itens.setItems(FXCollections.observableArrayList(it));

            VBox corpo = new VBox(8, Ui.texto("Eventos", "rg-section-title"), eventos, Ui.texto("Contagens (inclui as anuladas)", "rg-section-title"),
                    contagens, Ui.texto("Produtos (antes / depois do acerto)", "rg-section-title"), itens);
            Modal.informativo(dono, "Trilha do inventário #" + numero, "Registro que não pode ser alterado nem apagado.", corpo, 920, 560).mostrar();
        }
    }
}
