package br.com.quack.estoque.ui;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.api.CadastroApi;
import br.com.quack.estoque.domain.DomainException;
import br.com.quack.estoque.domain.Fmt;
import br.com.quack.estoque.domain.Perfil;
import br.com.quack.estoque.domain.Precificacao;
import br.com.quack.estoque.domain.Produto;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Produtos: lista com busca e indicador de estoque, ficha (criar/editar), alteração rápida de preço e etiqueta. */
public class ProdutosView extends Tela {
    private static final List<String> UNIDADES = List.of("UN", "KG", "G", "L", "ML", "CX", "PCT", "FD", "DZ", "M");

    private final Perfil perfil;
    private final TextField busca = Ui.campo("Buscar por nome, código ou GTIN (F2)");
    private final TableView<Produto> tabela = Ui.tabela();
    private final Label resumo = Ui.muted("");

    public ProdutosView(AppContext ctx, Navegador nav) {
        super(ctx, nav);
        this.perfil = ctx.session.perfil();

        Button novo = Ui.primario("Novo produto", () -> abrirFicha(null));
        novo.setVisible(perfil.cadastrarProdutos());
        novo.setManaged(perfil.cadastrarProdutos());
        Button atualizar = Ui.botao("Atualizar (F5)", nav::recarregarCatalogo);
        getChildren().add(Ui.cabecalho("Produtos", "Consulta de estoque e cadastro.", atualizar, novo));

        busca.textProperty().addListener((o, a, b) -> filtrar());
        busca.setOnAction(e -> {
            var l = ctx.catalogo.ler(busca.getText().trim());
            if (l != null) {
                tabela.getItems().setAll(List.of(l.produto()));
                tabela.getSelectionModel().selectFirst();
            }
        });
        Button ficha = Ui.botao(perfil.cadastrarProdutos() ? "Ficha / editar" : "Ver ficha", () -> {
            Produto p = tabela.getSelectionModel().getSelectedItem();
            if (p != null) {
                abrirFicha(p);
            }
        });
        Button preco = Ui.botao("Alterar preço", () -> {
            Produto p = tabela.getSelectionModel().getSelectedItem();
            if (p != null) {
                alterarPreco(p);
            }
        });
        preco.setVisible(perfil.cadastrarProdutos());
        preco.setManaged(perfil.cadastrarProdutos());
        Button etiqueta = Ui.botao("Etiqueta", () -> {
            Produto p = tabela.getSelectionModel().getSelectedItem();
            if (p != null) {
                ctx.etiquetas.adicionar(p.id(), 1);
                sucesso("Etiqueta de \"" + p.nome() + "\" na fila (" + ctx.etiquetas.totalEtiquetas() + " no total).");
            }
        });
        Button movimentos = Ui.botao("Movimentações", () -> {
            Produto p = tabela.getSelectionModel().getSelectedItem();
            if (p != null) {
                MovimentacoesView.abrirDoProduto(janela(), ctx, p);
            }
        });
        HBoxHelper.cresce(busca);
        getChildren().add(Ui.barra(busca, ficha, preco, etiqueta, movimentos));

        var cCodigo = Ui.col(tabela, "Código", Produto::codigo, 110);
        var cNome = Ui.col(tabela, "Produto", Produto::nome, 300);
        var cUn = Ui.col(tabela, "Un", Produto::unidade, 56);
        var cPreco = Ui.colNum(tabela, "Preço", p -> Fmt.dec2(p.preco()), 80);
        javafx.scene.control.TableColumn<Produto, String> cCusto = null;
        javafx.scene.control.TableColumn<Produto, String> cLucro = null;
        javafx.scene.control.TableColumn<Produto, String> cMargem = null;
        javafx.scene.control.TableColumn<Produto, String> cMarkup = null;
        if (perfil.verCustos()) {
            cCusto = Ui.colNum(tabela, "Custo", p -> Fmt.dec2(p.custo()), 80);
            // Derivados de custo e preço; editar qualquer um recalcula o preço de venda (Precificacao).
            cLucro = Ui.colNum(tabela, "Lucro R$", p -> Fmt.dec2(Precificacao.lucro(p.custo(), p.preco())), 84);
            cMargem = Ui.colNum(tabela, "Margem", p -> pct(Precificacao.margem(p.custo(), p.preco())), 84);
            cMarkup = Ui.colNum(tabela, "Markup", p -> pct(Precificacao.markup(p.custo(), p.preco())), 84);
        }
        var cEstoque = Ui.colNum(tabela, "Estoque", p -> Fmt.qty(p.estoque()), 80);
        var cMin = Ui.colNum(tabela, "Mín.", p -> Fmt.qty(p.minimo()), 60);
        var cMax = Ui.colNum(tabela, "Máx.", p -> Fmt.qty(p.maximo()), 60);
        Ui.col(tabela, "Situação", p -> Produto.rotulo(p.nivel()), 120);
        var cCategoria = Ui.col(tabela, "Categoria", p -> p.categoriaNome().isBlank() ? SEM_CATEGORIA : p.categoriaNome(), 160);
        javafx.scene.control.TableColumn<Produto, String> cFornecedor = perfil.cadastrarCategoriasEFornecedores()
                ? Ui.col(tabela, "Fornecedor", p -> p.fornecedor().isBlank() ? SEM_FORNECEDOR : p.fornecedor(), 160) : null;
        colunasEditaveis(cCodigo, cNome, cUn, cPreco, cCusto, cEstoque, cMin, cMax, cCategoria);
        if (perfil.cadastrarProdutos() && cLucro != null) {
            precificacaoEditavel(cLucro, "lucro", p -> Fmt.dec2(Precificacao.lucro(p.custo(), p.preco())));
            precificacaoEditavel(cMargem, "margem", p -> numPct(Precificacao.margem(p.custo(), p.preco())));
            precificacaoEditavel(cMarkup, "markup", p -> numPct(Precificacao.markup(p.custo(), p.preco())));
        }
        if (cFornecedor != null) {
            // Fornecedor: um clique abre a lista dos fornecedores cadastrados; grava no cadastro do produto.
            CelulaEditavel.lista(cFornecedor, nomesFornecedores, p -> true);
            cFornecedor.setOnEditCommit(e -> salvarEdicao(e.getRowValue(), "fornecedor", e.getNewValue()));
        }
        tabela.getSelectionModel().setCellSelectionEnabled(false);
        tabela.setRowFactory(t -> {
            TableRow<Produto> row = new TableRow<>() {
                @Override
                protected void updateItem(Produto p, boolean empty) {
                    super.updateItem(p, empty);
                    getStyleClass().removeAll("rg-row-danger", "rg-row-warn");
                    if (!empty && p != null) {
                        if (p.nivel() == Produto.Nivel.SEM_ESTOQUE) {
                            getStyleClass().add("rg-row-danger");
                        } else if (p.nivel() == Produto.Nivel.BAIXO || p.nivel() == Produto.Nivel.ACIMA_DO_MAXIMO) {
                            getStyleClass().add("rg-row-warn");
                        }
                    }
                }
            };
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty() && tabela.getEditingCell() == null) {
                    abrirFicha(row.getItem());
                }
            });
            return row;
        });
        VBox.setVgrow(tabela, Priority.ALWAYS);
        getChildren().addAll(tabela, resumo);
    }

    @Override
    public void ativar() {
        carregarCategorias();
        rodar(() -> {
            ctx.garantirCatalogo(false);
            return true;
        }, ok -> filtrar());
    }

    @Override
    public void focarBusca() {
        busca.requestFocus();
        busca.selectAll();
    }

    private void filtrar() {
        List<Produto> lista = ctx.catalogo.buscar(busca.getText(), 500);
        tabela.setItems(FXCollections.observableArrayList(lista));
        long semEstoque = ctx.catalogo.todos().stream().filter(p -> p.nivel() == Produto.Nivel.SEM_ESTOQUE).count();
        long baixo = ctx.catalogo.todos().stream().filter(p -> p.nivel() == Produto.Nivel.BAIXO).count();
        resumo.setText(ctx.catalogo.todos().size() + " produtos no catálogo · " + semEstoque + " sem estoque · " + baixo
                + " com estoque baixo" + (lista.size() >= 500 ? " · mostrando os 500 primeiros" : ""));
    }

    // ------------------------------------------------------------------ edição direto na tabela

    /** Categorias ativas (nome do caminho → id) para a coluna Categoria; carregadas em ativar(). */
    private final java.util.Map<String, String> categoriasPorNome = new java.util.LinkedHashMap<>();
    /** id da categoria → nome (como a lista de produtos mostra), para completar respostas sem o nome. */
    private final java.util.Map<String, String> nomeDaCategoria = new java.util.HashMap<>();
    private final List<String> nomesCategorias = new ArrayList<>();
    private static final String SEM_CATEGORIA = "(sem categoria)";
    /** Fornecedores cadastrados (nome) para a coluna Fornecedor; carregados em ativar(). */
    private final List<String> nomesFornecedores = new ArrayList<>();
    private static final String SEM_FORNECEDOR = "(sem fornecedor)";

    /** Colunas editáveis com um clique: Enter salva, Esc cancela, Tab vai para a próxima. */
    private void colunasEditaveis(javafx.scene.control.TableColumn<Produto, String> codigo,
            javafx.scene.control.TableColumn<Produto, String> nome, javafx.scene.control.TableColumn<Produto, String> un,
            javafx.scene.control.TableColumn<Produto, String> preco, javafx.scene.control.TableColumn<Produto, String> custo,
            javafx.scene.control.TableColumn<Produto, String> estoque, javafx.scene.control.TableColumn<Produto, String> minimo,
            javafx.scene.control.TableColumn<Produto, String> maximo, javafx.scene.control.TableColumn<Produto, String> categoria) {
        boolean cadastro = perfil.cadastrarProdutos();
        tabela.setEditable(true);
        // Estoque: todos os perfis do app ajustam (vira movimentação de entrada/saída).
        CelulaEditavel.em(estoque, p -> Fmt.qty(p.estoque()), true, p -> true);
        estoque.setOnEditCommit(e -> salvarEdicao(e.getRowValue(), "estoque", e.getNewValue()));
        if (!cadastro) {
            return;
        }
        CelulaEditavel.em(codigo, Produto::codigo, false, p -> true);
        codigo.setOnEditCommit(e -> salvarEdicao(e.getRowValue(), "codigo", e.getNewValue()));
        CelulaEditavel.em(nome, Produto::nome, false, p -> true);
        nome.setOnEditCommit(e -> salvarEdicao(e.getRowValue(), "nome", e.getNewValue()));
        CelulaEditavel.lista(un, UNIDADES, p -> true);
        un.setOnEditCommit(e -> salvarEdicao(e.getRowValue(), "unidade", e.getNewValue()));
        CelulaEditavel.em(preco, p -> Fmt.dec2(p.preco()), true, p -> true);
        preco.setOnEditCommit(e -> salvarEdicao(e.getRowValue(), "preco", e.getNewValue()));
        if (custo != null) {
            CelulaEditavel.em(custo, p -> Fmt.dec2(p.custo()), true, p -> true);
            custo.setOnEditCommit(e -> salvarEdicao(e.getRowValue(), "custo", e.getNewValue()));
        }
        CelulaEditavel.em(minimo, p -> Fmt.qty(p.minimo()), true, p -> true);
        minimo.setOnEditCommit(e -> salvarEdicao(e.getRowValue(), "minimo", e.getNewValue()));
        CelulaEditavel.em(maximo, p -> Fmt.qty(p.maximo()), true, p -> true);
        maximo.setOnEditCommit(e -> salvarEdicao(e.getRowValue(), "maximo", e.getNewValue()));
        CelulaEditavel.lista(categoria, nomesCategorias, p -> true);
        categoria.setOnEditCommit(e -> salvarEdicao(e.getRowValue(), "categoria", e.getNewValue()));
    }

    private void carregarCategorias() {
        if (!perfil.cadastrarProdutos()) {
            return;
        }
        Fx.async(ctx.cadastros::categorias, cats -> {
            categoriasPorNome.clear();
            nomesCategorias.clear();
            nomesCategorias.add(SEM_CATEGORIA);
            for (CadastroApi.Categoria c : CadastrosView.ordenar(cats.stream().filter(CadastroApi.Categoria::ativa).toList())) {
                categoriasPorNome.put(c.caminho(), c.id());
                nomeDaCategoria.put(c.id(), c.nome());
                nomesCategorias.add(c.caminho());
            }
        }, t -> {
            // sem categorias: a coluna fica só com "(sem categoria)"
        });
        if (perfil.cadastrarCategoriasEFornecedores()) {
            Fx.async(ctx.cadastros::fornecedores, fors -> {
                nomesFornecedores.clear();
                nomesFornecedores.add(SEM_FORNECEDOR);
                fors.stream().map(CadastroApi.Fornecedor::nome).filter(n -> n != null && !n.isBlank()).distinct()
                        .sorted(String.CASE_INSENSITIVE_ORDER).forEach(nomesFornecedores::add);
            }, t -> {
                // sem fornecedores: a coluna fica só com "(sem fornecedor)"
            });
        }
    }

    /** Valida o valor digitado e salva só aquele campo; em erro, a célula volta ao valor anterior. */
    private void salvarEdicao(Produto p, String campo, String texto) {
        String t = texto == null ? "" : texto.trim();
        try {
            switch (campo) {
                case "preco" -> {
                    BigDecimal v = Fmt.parse(t);
                    if (v.signum() <= 0) {
                        throw new DomainException("O preço de venda deve ser maior que zero.");
                    }
                    if (v.compareTo(p.preco()) == 0) {
                        tabela.refresh();
                        return;
                    }
                    salvarPreco(p, v, "Preço");
                }
                case "lucro", "margem", "markup" -> {
                    BigDecimal v = Fmt.parse(t.replace("%", "").trim());
                    BigDecimal novo = switch (campo) {
                        case "lucro" -> Precificacao.precoPorLucro(p.custo(), v);
                        case "margem" -> Precificacao.precoPorMargem(p.custo(), v);
                        default -> Precificacao.precoPorMarkup(p.custo(), v);
                    };
                    if (novo.compareTo(p.preco()) == 0) {
                        tabela.refresh();
                        return;
                    }
                    String rotulo = switch (campo) {
                        case "lucro" -> "Lucro";
                        case "margem" -> "Margem";
                        default -> "Markup";
                    };
                    salvarPreco(p, novo, rotulo);
                }
                case "estoque" -> {
                    BigDecimal v = Fmt.parse(t);
                    if (v.signum() < 0) {
                        throw new DomainException("O estoque não pode ser negativo.");
                    }
                    if (!p.fracionavel() && v.stripTrailingZeros().scale() > 0) {
                        throw new DomainException("Produto vendido por " + p.unidade() + " não aceita quantidade fracionada.");
                    }
                    BigDecimal delta = v.subtract(p.estoque());
                    if (delta.signum() == 0) {
                        tabela.refresh();
                        return;
                    }
                    String tipo = delta.signum() > 0 ? "entrada" : "saida";
                    // Ajuste relativo: vendas feitas depois do carregamento continuam valendo.
                    Carregando.async("Ajustando o estoque…", () -> {
                        ctx.produtos.ajustar(p.id(), tipo, delta.abs(), "Acerto pela tabela de produtos", null, null);
                        return true;
                    }, ok -> {
                        substituir(p, comCampo(p, "productQnt", Fmt.qtyApi(p.estoque().add(delta))));
                        sucesso("Estoque de \"" + p.nome() + "\": " + Fmt.qty(p.estoque()) + " → " + Fmt.qty(v) + " " + p.unidade()
                                + " (movimentação de " + ("entrada".equals(tipo) ? "entrada" : "saída") + " registrada).");
                    }, this::erroEdicao);
                }
                case "nome" -> {
                    if (t.length() < 3) {
                        throw new DomainException("Informe o nome do produto (mínimo 3 letras).");
                    }
                    salvarCampo(p, b -> b.put("productName", t), "Nome atualizado.");
                }
                case "codigo" -> {
                    if (t.isEmpty()) {
                        throw new DomainException("O código não pode ficar vazio.");
                    }
                    salvarCampo(p, b -> b.put("productCode", t), "Código atualizado.");
                }
                case "unidade" -> {
                    String u = t.toUpperCase();
                    salvarCampo(p, b -> {
                        b.put("unidadeComercial", u);
                        b.put("unidadeTributavel", u);
                    }, "Unidade atualizada para " + u + ".");
                }
                case "custo" -> {
                    BigDecimal v = Fmt.parse(t);
                    if (v.signum() <= 0) {
                        throw new DomainException("O preço de custo deve ser maior que zero.");
                    }
                    salvarCampo(p, b -> b.put("productUnitPrice", Fmt.cost4Api(v)), "Custo atualizado para " + Fmt.money(v) + ".");
                }
                case "minimo", "maximo" -> {
                    BigDecimal v = t.isEmpty() ? BigDecimal.ZERO : Fmt.parse(t);
                    if (v.signum() < 0) {
                        throw new DomainException("O valor não pode ser negativo.");
                    }
                    BigDecimal min = "minimo".equals(campo) ? v : p.minimo();
                    BigDecimal max = "maximo".equals(campo) ? v : p.maximo();
                    if (max.signum() > 0 && max.compareTo(min) < 0) {
                        throw new DomainException("O estoque máximo não pode ser menor que o mínimo.");
                    }
                    salvarCampo(p, b -> b.put("minimo".equals(campo) ? "estoqueMinimo" : "estoqueMaximo", Fmt.qtyApi(v)),
                            ("minimo".equals(campo) ? "Estoque mínimo" : "Estoque máximo") + " atualizado.");
                }
                case "fornecedor" -> {
                    String nome = SEM_FORNECEDOR.equals(t) ? "" : t;
                    if (!nome.isEmpty() && !nomesFornecedores.contains(nome)) {
                        throw new DomainException("Fornecedor não encontrado. Aguarde a lista de fornecedores carregar e tente de novo.");
                    }
                    if (nome.equals(p.fornecedor())) {
                        tabela.refresh();
                        return;
                    }
                    salvarCampo(p, b -> b.put("productSupplier", nome),
                            nome.isEmpty() ? "Fornecedor removido." : "Fornecedor atualizado para " + nome + ".");
                }
                case "categoria" -> {
                    String id = SEM_CATEGORIA.equals(t) ? null : categoriasPorNome.get(t);
                    if (id == null && !SEM_CATEGORIA.equals(t)) {
                        // nunca grava "sem categoria" por engano (lista ainda carregando ou nome que não existe)
                        throw new DomainException("Categoria não encontrada. Aguarde a lista de categorias carregar e tente de novo.");
                    }
                    salvarCampo(p, b -> {
                        if (id == null) {
                            b.putNull("categoriaId");
                        } else {
                            b.put("categoriaId", id);
                        }
                    }, "Categoria atualizada.");
                }
                default -> tabela.refresh();
            }
        } catch (DomainException e) {
            tabela.refresh();
            Toasts.error(janela(), e.getMessage());
        }
    }

    /** Grava o novo preço de venda (com controle de versão) e mostra preço, lucro, margem e markup resultantes. */
    private void salvarPreco(Produto p, BigDecimal v, String origem) {
        Carregando.async("Salvando o preço…", () -> ctx.produtos.atualizarPreco(p.id(), Fmt.moneyApi(v), p.versaoPreco()),
                salvo -> {
                    Produto novo = salvo != null ? salvo : comCampo(p, "productSalePrice", Fmt.moneyApi(v));
                    substituir(p, novo);
                    sucesso(origem + " de \"" + p.nome() + "\": preço " + Fmt.money(p.preco()) + " → " + Fmt.money(novo.preco())
                            + " · lucro " + Fmt.money(Precificacao.lucro(novo.custo(), novo.preco()))
                            + " · margem " + pct(Precificacao.margem(novo.custo(), novo.preco()))
                            + " · markup " + pct(Precificacao.markup(novo.custo(), novo.preco()))
                            + ". Use \"Preços alterados\" em Etiquetas para imprimir.");
                }, this::erroEdicao);
    }

    /** Lucro/margem/markup editáveis: só com custo informado (sem custo não há como calcular o preço). */
    private void precificacaoEditavel(javafx.scene.control.TableColumn<Produto, String> col, String campo,
            java.util.function.Function<Produto, String> valor) {
        CelulaEditavel.em(col, valor, true, p -> p.custo() != null && p.custo().signum() > 0);
        col.setOnEditCommit(e -> salvarEdicao(e.getRowValue(), campo, e.getNewValue()));
    }

    static String pct(BigDecimal v) {
        return v == null ? "—" : Fmt.dec2(v) + "%";
    }

    private static String numPct(BigDecimal v) {
        return v == null ? "" : Fmt.dec2(v);
    }

    /**
     * Regrava o cadastro trocando só o campo editado. Manda o estoque que a tela viu (estoqueConsultado): a API não mexe
     * no saldo nem desfaz vendas feitas depois que a lista carregou.
     */
    private void salvarCampo(Produto p, java.util.function.Consumer<ObjectNode> mudar, String mensagem) {
        Carregando.async("Salvando…", () -> {
            ObjectNode body = p.raw().deepCopy();
            body.put("estoqueConsultado", body.path("productQnt").asText("0"));
            mudar.accept(body);
            return ctx.produtos.atualizar(p.id(), body);
        }, salvo -> {
            substituir(p, comNomeDaCategoria(salvo));
            sucesso(mensagem);
        }, this::erroEdicao);
    }

    /** A API antiga devolve o produto salvo sem o nome da categoria: completa pelo cadastro de categorias. */
    private Produto comNomeDaCategoria(Produto salvo) {
        if (salvo == null || salvo.categoriaId().isBlank() || !salvo.categoriaNome().isBlank()) {
            return salvo;
        }
        String nome = nomeDaCategoria.get(salvo.categoriaId());
        return nome == null ? salvo : comCampo(salvo, "categoriaNome", nome);
    }

    private void erroEdicao(Throwable t) {
        tabela.refresh();
        Toasts.error(janela(), Fx.message(t));
    }

    private static Produto comCampo(Produto p, String campo, String valor) {
        ObjectNode raw = p.raw().deepCopy();
        raw.put(campo, valor);
        return Produto.de(raw);
    }

    /** Troca o produto na tabela e no catálogo em memória (sem recarregar a lista inteira). */
    private void substituir(Produto antigo, Produto novo) {
        if (novo == null) {
            return;
        }
        ctx.catalogo.substituir(novo);
        int i = tabela.getItems().indexOf(antigo);
        if (i >= 0) {
            tabela.getItems().set(i, novo);
        } else {
            tabela.refresh();
        }
    }

    // ------------------------------------------------------------------ alterar preço

    private void alterarPreco(Produto p) {
        TextField novo = Ui.campo("Novo preço de venda");
        novo.setText(Fmt.dec2(p.preco()));
        Label margem = Ui.muted("");
        Runnable calc = () -> {
            try {
                BigDecimal v = Fmt.parse(novo.getText());
                if (perfil.verCustos() && p.custo().signum() > 0) {
                    BigDecimal m = v.subtract(p.custo()).divide(p.custo(), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
                    margem.setText("Margem sobre o custo: " + Fmt.dec2(m) + "%");
                }
            } catch (DomainException e) {
                margem.setText("");
            }
        };
        novo.textProperty().addListener((o, a, b) -> calc.run());
        calc.run();
        VBox corpo = new VBox(8, Ui.texto(p.nome(), "rg-section-title"), Ui.muted("Preço atual: " + Fmt.money(p.preco())),
                Ui.rotulado("Novo preço (R$)", novo), margem);
        Modal[] m = new Modal[1];
        m[0] = Modal.formulario(janela(), "Alterar preço", corpo, "Salvar preço", 420, () -> {
            BigDecimal v;
            try {
                v = Ui.numero(novo, "o novo preço");
                if (v.signum() <= 0) {
                    throw new DomainException("O preço deve ser maior que zero.");
                }
            } catch (DomainException e) {
                m[0].erro(e.getMessage());
                return false;
            }
            m[0].ocupado(true);
            Carregando.async(() -> ctx.produtos.atualizarPreco(p.id(), Fmt.moneyApi(v), p.versaoPreco()), salvo -> {
                m[0].ocupado(false);
                m[0].fechar();
                sucesso("Preço de \"" + p.nome() + "\" alterado para " + Fmt.money(v) + ".");
                recarregar(() -> Modal.confirmar(janela(), "Etiqueta de gôndola",
                        "Colocar \"" + p.nome() + "\" na fila de etiquetas com o novo preço?", "Colocar na fila", () -> {
                            ctx.etiquetas.adicionar(p.id(), 1);
                            sucesso("Etiqueta na fila.");
                        }));
            }, t -> {
                m[0].ocupado(false);
                m[0].erro(Fx.message(t));
            });
            return false;
        });
        m[0].mostrar();
        novo.requestFocus();
        novo.selectAll();
    }

    private void recarregar(Runnable depois) {
        rodar(() -> {
            ctx.garantirCatalogo(true);
            return true;
        }, ok -> {
            filtrar();
            if (depois != null) {
                depois.run();
            }
        });
    }

    // ------------------------------------------------------------------ ficha

    /** p == null cria um produto novo (só gerente/administrador). */
    private void abrirFicha(Produto p) {
        boolean edita = perfil.cadastrarProdutos();
        boolean novo = p == null;
        if (novo && !edita) {
            return;
        }
        boolean custos = perfil.verCustos();

        TextField codigo = Ui.campo("Gerado automaticamente se vazio");
        TextField gtin = Ui.campo("EAN-13 (vazio = sem GTIN)");
        TextField nome = Ui.campo("Descrição do produto");
        ComboBox<String> unidade = Ui.combo(UNIDADES);
        unidade.setEditable(true);
        TextField custo = Ui.campo("0,00");
        TextField preco = Ui.campo("0,00");
        Label margem = Ui.muted("");
        TextField minimo = Ui.campo("0");
        TextField maximo = Ui.campo("0");
        TextField estoqueInicial = Ui.campo("0");
        ComboBox<CadastroApi.Categoria> categoria = Ui.combo(new ArrayList<>());
        categoria.setPromptText("Sem categoria");
        ComboBox<String> fornecedor = Ui.combo(new ArrayList<>());
        fornecedor.setEditable(false);
        fornecedor.setPromptText("Sem fornecedor");
        CheckBox validade = Ui.check("Controla validade (lote e data na entrada)", false);
        TextField localizacao = Ui.campo("Corredor / prateleira");

        if (!novo) {
            codigo.setText(p.codigo());
            gtin.setText(p.gtin());
            nome.setText(p.nome());
            unidade.setValue(p.unidade());
            custo.setText(Fmt.dec2(p.custo()));
            preco.setText(Fmt.dec2(p.preco()));
            minimo.setText(Fmt.qty(p.minimo()));
            maximo.setText(Fmt.qty(p.maximo()));
            validade.setSelected(p.controlaValidade());
            localizacao.setText(p.localizacao());
            estoqueInicial.setText(Fmt.qty(p.estoque()));
        } else {
            unidade.setValue("UN");
        }
        estoqueInicial.setDisable(!novo);
        Runnable calcMargem = () -> {
            try {
                BigDecimal c = Fmt.parseOrZero(custo.getText());
                BigDecimal v = Fmt.parseOrZero(preco.getText());
                if (c.signum() > 0 && v.signum() > 0) {
                    BigDecimal m = v.subtract(c).divide(c, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
                    margem.setText("Margem: " + Fmt.dec2(m) + "%  ·  lucro por unidade: " + Fmt.money(v.subtract(c)));
                } else {
                    margem.setText("");
                }
            } catch (DomainException e) {
                margem.setText("");
            }
        };
        custo.textProperty().addListener((o, a, b) -> calcMargem.run());
        preco.textProperty().addListener((o, a, b) -> calcMargem.run());
        calcMargem.run();

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        int r = 0;
        g.add(Ui.rotulado("Código interno", codigo), 0, r);
        g.add(Ui.rotulado("GTIN / código de barras", gtin), 1, r++);
        g.add(Ui.rotulado("Nome", nome), 0, r, 2, 1);
        r++;
        g.add(Ui.rotulado("Unidade de venda", unidade), 0, r);
        g.add(Ui.rotulado(novo ? "Estoque inicial" : "Estoque atual (use Ajustes para alterar)", estoqueInicial), 1, r++);
        if (custos) {
            g.add(Ui.rotulado("Preço de custo (R$)", custo), 0, r);
        }
        g.add(Ui.rotulado("Preço de venda (R$)", preco), custos ? 1 : 0, r++);
        if (custos) {
            g.add(margem, 0, r++, 2, 1);
        }
        g.add(Ui.rotulado("Estoque mínimo", minimo), 0, r);
        g.add(Ui.rotulado("Estoque máximo", maximo), 1, r++);
        g.add(Ui.rotulado("Categoria", categoria), 0, r);
        if (perfil.cadastrarCategoriasEFornecedores()) {
            g.add(Ui.rotulado("Fornecedor", fornecedor), 1, r);
        }
        r++;
        g.add(Ui.rotulado("Localização no estoque", localizacao), 0, r, 2, 1);
        r++;
        g.add(validade, 0, r, 2, 1);
        for (int c = 0; c < 2; c++) {
            javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
            cc.setPercentWidth(50);
            g.getColumnConstraints().add(cc);
        }
        if (!edita) {
            for (var n : g.getChildren()) {
                n.setDisable(true);
            }
            g.getChildren().add(0, Ui.caixa("Perfil estoquista: consulta sem custo e sem edição.", "rg-warn-box"));
        }

        Modal[] m = new Modal[1];
        m[0] = Modal.formularioRolavel(janela(), novo ? "Novo produto" : "Produto: " + p.nome(),
                novo ? null : "Código " + p.codigo() + " · estoque " + Fmt.qty(p.estoque()) + " " + p.unidade(), g,
                edita ? "Salvar" : null, 560, 520, () -> {
                    try {
                        salvar(m[0], p, codigo, gtin, nome, unidade, custo, preco, minimo, maximo, estoqueInicial, categoria,
                                fornecedor, validade, localizacao);
                    } catch (DomainException e) {
                        m[0].erro(e.getMessage());
                    }
                    return false;
                });
        m[0].mostrar();

        // Categorias e fornecedores (listas curtas) carregados com a janela já aberta.
        rodar(() -> {
            List<CadastroApi.Categoria> cats = ctx.cadastros.categorias();
            List<CadastroApi.Fornecedor> fors = perfil.cadastrarCategoriasEFornecedores() ? ctx.cadastros.fornecedores() : List.of();
            return new Object[] {cats, fors};
        }, res -> {
            @SuppressWarnings("unchecked")
            List<CadastroApi.Categoria> cats = (List<CadastroApi.Categoria>) res[0];
            @SuppressWarnings("unchecked")
            List<CadastroApi.Fornecedor> fors = (List<CadastroApi.Fornecedor>) res[1];
            categoria.setItems(FXCollections.observableArrayList(cats.stream().filter(CadastroApi.Categoria::ativa).toList()));
            categoria.setConverter(new javafx.util.StringConverter<>() {
                @Override
                public String toString(CadastroApi.Categoria c) {
                    return c == null ? "" : c.caminho();
                }

                @Override
                public CadastroApi.Categoria fromString(String s) {
                    return null;
                }
            });
            if (!novo && !p.categoriaId().isBlank()) {
                cats.stream().filter(c -> c.id().equals(p.categoriaId())).findFirst().ifPresent(categoria::setValue);
            }
            List<String> nomes = new ArrayList<>();
            nomes.add("");
            fors.forEach(f -> nomes.add(f.nome()));
            fornecedor.setItems(FXCollections.observableArrayList(nomes));
            if (!novo) {
                fornecedor.setValue(p.fornecedor());
            }
        });
    }

    private void salvar(Modal m, Produto original, TextField codigo, TextField gtin, TextField nome,
            ComboBox<String> unidade, TextField custo, TextField preco, TextField minimo, TextField maximo,
            TextField estoqueInicial, ComboBox<CadastroApi.Categoria> categoria, ComboBox<String> fornecedor,
            CheckBox validade, TextField localizacao) {
        boolean novo = original == null;
        String nomeTxt = nome.getText() == null ? "" : nome.getText().trim();
        if (nomeTxt.length() < 3) {
            throw new DomainException("Informe o nome do produto (mínimo 3 letras).");
        }
        BigDecimal vPreco = Ui.numero(preco, "o preço de venda");
        BigDecimal vCusto = perfil.verCustos() ? Ui.numero(custo, "o preço de custo") : (novo ? BigDecimal.ZERO : original.custo());
        if (vPreco.signum() <= 0) {
            throw new DomainException("O preço de venda deve ser maior que zero.");
        }
        if (perfil.verCustos() && vCusto.signum() <= 0) {
            throw new DomainException("O preço de custo deve ser maior que zero.");
        }
        String gtinTxt = gtin.getText() == null ? "" : gtin.getText().trim();
        if (!gtinTxt.isEmpty() && !gtinTxt.matches("\\d{8}|\\d{12,14}")) {
            throw new DomainException("O GTIN deve ter 8, 12, 13 ou 14 números.");
        }
        BigDecimal vMin = Ui.numeroOuZero(minimo);
        BigDecimal vMax = Ui.numeroOuZero(maximo);
        if (vMax.signum() > 0 && vMax.compareTo(vMin) < 0) {
            throw new DomainException("O estoque máximo não pode ser menor que o mínimo.");
        }
        String un = unidade.getValue() == null || unidade.getValue().isBlank() ? "UN" : unidade.getValue().trim().toUpperCase();
        BigDecimal vEstoque = novo ? Ui.numeroOuZero(estoqueInicial) : original.estoque();
        String codigoTxt = codigo.getText() == null ? "" : codigo.getText().trim();
        String categoriaId = categoria.getValue() == null ? "" : categoria.getValue().id();
        String fornecedorNome = fornecedor.getValue() == null ? "" : fornecedor.getValue();

        m.ocupado(true);
        Carregando.async(() -> {
            ObjectNode body;
            if (novo) {
                body = br.com.quack.estoque.persistence.JsonFiles.MAPPER.createObjectNode();
                body.put("productCode", codigoTxt);
                body.put("productName", nomeTxt);
                body.put("productDescription", "");
                body.put("productImageUrl", "");
                body.put("productImageName", "");
                body.put("totalPriceOnProduct", "0,00");
                body.put("ncm", "00000000");
                body.put("cfop", "5102");
                body.put("unidadeCompra", un);
                body.put("fatorConversao", "1");
                body.put("qtdEmbalagem", "1");
                body.put("productSupplier", fornecedorNome);
            } else {
                // Manda o estoque que a tela viu: a API aplica só diferenças digitadas e não desfaz vendas feitas
                // depois que o catálogo carregou (sem precisar reler a lista inteira de produtos).
                body = original.raw().deepCopy();
                body.put("estoqueConsultado", body.path("productQnt").asText("0"));
                if (perfil.cadastrarCategoriasEFornecedores()) {
                    body.put("productSupplier", fornecedorNome);
                }
            }
            body.put("productName", nomeTxt);
            if (!codigoTxt.isEmpty()) {
                body.put("productCode", codigoTxt);
            }
            body.put("gtin", gtinTxt.isEmpty() ? "SEM GTIN" : gtinTxt);
            body.put("unidadeComercial", un);
            body.put("unidadeTributavel", un);
            body.put("productSalePrice", Fmt.moneyApi(vPreco));
            body.put("productUnitPrice", Fmt.cost4Api(vCusto));
            body.put("estoqueMinimo", Fmt.qtyApi(vMin));
            body.put("estoqueMaximo", Fmt.qtyApi(vMax));
            body.put("controlaValidade", validade.isSelected());
            body.put("localizacaoEstoque", localizacao.getText() == null ? "" : localizacao.getText().trim());
            if (categoriaId.isEmpty()) {
                body.putNull("categoriaId");
            } else {
                body.put("categoriaId", categoriaId);
            }
            if (novo) {
                body.put("productQnt", Fmt.qtyApi(vEstoque));
                return ctx.produtos.criar(body);
            }
            return ctx.produtos.atualizar(original.id(), body);
        }, salvo -> {
            m.ocupado(false);
            m.fechar();
            sucesso(novo ? "Produto cadastrado." : "Produto atualizado.");
            recarregar(() -> {
                if (!novo && salvo.preco().compareTo(original.preco()) != 0) {
                    Modal.confirmar(janela(), "Etiqueta de gôndola", "O preço mudou. Colocar \"" + salvo.nome()
                            + "\" na fila de etiquetas?", "Colocar na fila", () -> ctx.etiquetas.adicionar(salvo.id(), 1));
                }
            });
        }, t -> {
            m.ocupado(false);
            m.erro(Fx.message(t));
        });
    }

    /** Pequeno utilitário para o campo de busca ocupar a largura livre da barra. */
    private static final class HBoxHelper {
        static void cresce(javafx.scene.Node n) {
            javafx.scene.layout.HBox.setHgrow(n, Priority.ALWAYS);
        }
    }
}
