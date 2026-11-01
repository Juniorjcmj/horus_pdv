package br.com.quack.estoque.ui;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.api.CadastroApi.Categoria;
import br.com.quack.estoque.api.CadastroApi.Fornecedor;
import br.com.quack.estoque.domain.DomainException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.collections.FXCollections;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Categorias (2 níveis) e fornecedores, para gerente e administrador. */
public class CadastrosView extends Tela {
    private final TableView<Categoria> categorias = Ui.tabela();
    private final TableView<Fornecedor> fornecedores = Ui.tabela();
    private List<Categoria> todasCategorias = new ArrayList<>();

    public CadastrosView(AppContext ctx, Navegador nav) {
        super(ctx, nav);
        getChildren().add(Ui.cabecalho("Cadastros", "Categorias (departamento e subcategoria) e fornecedores."));

        Ui.col(categorias, "Categoria", Categoria::caminho, 320);
        Ui.colNum(categorias, "Produtos", c -> String.valueOf(c.quantidadeProdutos()), 80);
        Ui.col(categorias, "Situação", c -> c.ativa() ? "Ativa" : "Inativa", 90);
        VBox abaCategorias = new VBox(10, Ui.barra(Ui.primario("Nova categoria", () -> editarCategoria(null, false)),
                Ui.botao("Nova subcategoria", () -> editarCategoria(null, true)), Ui.botao("Editar", () -> {
                    Categoria c = categorias.getSelectionModel().getSelectedItem();
                    if (c != null) {
                        editarCategoria(c, c.categoriaPaiId() != null && !c.categoriaPaiId().isBlank());
                    }
                }), Ui.botao("Ativar / desativar", this::alternarCategoria)), categorias);
        abaCategorias.setPadding(Ui.margem(10));

        Ui.col(fornecedores, "Nome fantasia", Fornecedor::nome, 220);
        Ui.col(fornecedores, "Razão social", Fornecedor::companyName, 220);
        Ui.col(fornecedores, "CNPJ", Fornecedor::cnpj, 140);
        Ui.col(fornecedores, "Telefone", Fornecedor::telephone, 120);
        Ui.col(fornecedores, "Cidade/UF", f -> f.city() + (f.state().isBlank() ? "" : "/" + f.state()), 130);
        Ui.col(fornecedores, "E-mail", Fornecedor::email, 180);
        VBox abaFornecedores = new VBox(10, Ui.barra(Ui.primario("Novo fornecedor", () -> editarFornecedor(null)),
                Ui.botao("Editar", () -> {
                    Fornecedor f = fornecedores.getSelectionModel().getSelectedItem();
                    if (f != null) {
                        editarFornecedor(f);
                    }
                })), fornecedores);
        abaFornecedores.setPadding(Ui.margem(10));

        TabPane abas = new TabPane(aba("Categorias", abaCategorias), aba("Fornecedores", abaFornecedores));
        abas.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(abas, Priority.ALWAYS);
        getChildren().add(abas);
    }

    private static Tab aba(String titulo, VBox conteudo) {
        VBox.setVgrow(conteudo.getChildren().get(1), Priority.ALWAYS);
        return new Tab(titulo, conteudo);
    }

    @Override
    public void ativar() {
        rodar(() -> new Object[] {ctx.cadastros.categorias(), ctx.cadastros.fornecedores()}, r -> {
            @SuppressWarnings("unchecked")
            List<Categoria> cats = (List<Categoria>) r[0];
            @SuppressWarnings("unchecked")
            List<Fornecedor> fors = (List<Fornecedor>) r[1];
            todasCategorias = cats;
            categorias.setItems(FXCollections.observableArrayList(ordenar(cats)));
            fornecedores.setItems(FXCollections.observableArrayList(fors));
        });
    }

    /** Departamentos seguidos das suas subcategorias. */
    static List<Categoria> ordenar(List<Categoria> cats) {
        List<Categoria> out = new ArrayList<>();
        for (Categoria pai : cats.stream().filter(c -> c.categoriaPaiId() == null || c.categoriaPaiId().isBlank())
                .sorted((a, b) -> a.nome().compareToIgnoreCase(b.nome())).toList()) {
            out.add(pai);
            cats.stream().filter(c -> pai.id().equals(c.categoriaPaiId()))
                    .sorted((a, b) -> a.nome().compareToIgnoreCase(b.nome())).forEach(out::add);
        }
        return out;
    }

    private void editarCategoria(Categoria c, boolean sub) {
        TextField nome = Ui.campo("Nome da categoria");
        List<Categoria> departamentos = todasCategorias.stream()
                .filter(x -> x.categoriaPaiId() == null || x.categoriaPaiId().isBlank()).toList();
        ComboBox<Categoria> pai = Ui.combo(departamentos);
        pai.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Categoria x) {
                return x == null ? "" : x.nome();
            }

            @Override
            public Categoria fromString(String s) {
                return null;
            }
        });
        CheckBox ativa = Ui.check("Ativa", true);
        if (c != null) {
            nome.setText(c.nome());
            ativa.setSelected(c.ativa());
            departamentos.stream().filter(x -> x.id().equals(c.categoriaPaiId())).findFirst().ifPresent(pai::setValue);
        }
        VBox corpo = new VBox(8, Ui.rotulado("Nome", nome));
        if (sub) {
            corpo.getChildren().add(Ui.rotulado("Departamento (categoria pai)", pai));
        }
        corpo.getChildren().add(ativa);
        Modal[] m = new Modal[1];
        m[0] = Modal.formulario(janela(), c == null ? (sub ? "Nova subcategoria" : "Nova categoria") : "Editar categoria", corpo,
                "Salvar", 440, () -> {
                    String n = nome.getText() == null ? "" : nome.getText().trim();
                    if (n.isEmpty()) {
                        m[0].erro("Informe o nome.");
                        return false;
                    }
                    if (sub && pai.getValue() == null) {
                        m[0].erro("Escolha o departamento da subcategoria (a categoria só tem 2 níveis).");
                        return false;
                    }
                    String paiId = sub ? pai.getValue().id() : null;
                    m[0].ocupado(true);
                    Carregando.async(() -> ctx.cadastros.salvarCategoria(c == null ? null : c.id(), n, paiId, ativa.isSelected()), id -> {
                        m[0].ocupado(false);
                        m[0].fechar();
                        sucesso("Categoria salva.");
                        ativar();
                    }, t -> {
                        m[0].ocupado(false);
                        m[0].erro(Fx.message(t));
                    });
                    return false;
                });
        m[0].mostrar();
        nome.requestFocus();
    }

    private void alternarCategoria() {
        Categoria c = categorias.getSelectionModel().getSelectedItem();
        if (c == null) {
            return;
        }
        rodar(() -> ctx.cadastros.salvarCategoria(c.id(), c.nome(), c.categoriaPaiId(), !c.ativa()), id -> {
            sucesso(c.ativa() ? "Categoria desativada." : "Categoria ativada.");
            ativar();
        });
    }

    private void editarFornecedor(Fornecedor f) {
        Map<String, TextField> campos = new LinkedHashMap<>();
        String[][] def = {{"companyName", "Razão social"}, {"fantasyName", "Nome fantasia"}, {"cnpj", "CNPJ"},
            {"telephone", "Telefone"}, {"email", "E-mail"}, {"cep", "CEP"}, {"city", "Cidade"}, {"state", "UF"},
            {"address", "Endereço"}, {"neighborhood", "Bairro"}, {"number", "Número"}};
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        int i = 0;
        for (String[] d : def) {
            TextField t = Ui.campo(d[1]);
            campos.put(d[0], t);
            g.add(Ui.rotulado(d[1], t), i % 2, i / 2);
            i++;
        }
        if (f != null) {
            campos.get("companyName").setText(f.companyName());
            campos.get("fantasyName").setText(f.fantasyName());
            campos.get("cnpj").setText(f.cnpj());
            campos.get("telephone").setText(f.telephone());
            campos.get("email").setText(f.email());
            campos.get("city").setText(f.city());
            campos.get("state").setText(f.state());
        }
        for (int c = 0; c < 2; c++) {
            javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
            cc.setPercentWidth(50);
            g.getColumnConstraints().add(cc);
        }
        Modal[] m = new Modal[1];
        m[0] = Modal.formulario(janela(), f == null ? "Novo fornecedor" : "Editar fornecedor", g, "Salvar", 600, () -> {
            Map<String, String> corpo = new LinkedHashMap<>();
            for (var e : campos.entrySet()) {
                corpo.put(e.getKey(), e.getValue().getText() == null ? "" : e.getValue().getText().trim());
            }
            for (String extra : new String[] {"streetComplement", "referencePoint", "cellphone"}) {
                corpo.put(extra, "");
            }
            if (corpo.get("companyName").isEmpty() && corpo.get("fantasyName").isEmpty()) {
                m[0].erro("Informe a razão social ou o nome fantasia.");
                return false;
            }
            m[0].ocupado(true);
            Carregando.async(() -> ctx.cadastros.salvarFornecedor(f == null ? null : f.id(), corpo), salvo -> {
                m[0].ocupado(false);
                m[0].fechar();
                sucesso("Fornecedor salvo.");
                ativar();
            }, t -> {
                m[0].ocupado(false);
                m[0].erro(Fx.message(t));
            });
            return false;
        });
        m[0].mostrar();
    }

    /** Lança DomainException se a regra de 2 níveis for violada (usada em testes e pelo formulário). */
    static void exigirDoisNiveis(Categoria pai) {
        if (pai != null && pai.categoriaPaiId() != null && !pai.categoriaPaiId().isBlank()) {
            throw new DomainException("A categoria só tem 2 níveis: escolha um departamento como pai.");
        }
    }
}
