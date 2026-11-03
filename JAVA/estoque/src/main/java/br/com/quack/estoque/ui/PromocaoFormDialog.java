package br.com.quack.estoque.ui;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.api.CadastroApi;
import br.com.quack.estoque.domain.DomainException;
import br.com.quack.estoque.domain.Fmt;
import br.com.quack.estoque.domain.Produto;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Formulário de promoção (nova ou edição), usado pela tela Promoções e pelo Controle de validade ("colocar em
 * promoção" os lotes escolhidos). Mesmos tipos e regras da tela web.
 */
public final class PromocaoFormDialog {
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

    /** Valores iniciais de uma promoção nova: nome, produtos, fim da vigência e % sugerido (null = vazio). */
    public record Preenchimento(String nome, List<Produto> produtos, LocalDateTime fim, BigDecimal percentual) {
    }

    private PromocaoFormDialog() {
    }

    /**
     * @param p       promoção existente (edição) ou null (nova)
     * @param pre     valores iniciais de uma promoção nova (ex.: vinda do controle de validade), ou null
     * @param aoSalvar recebe a mensagem de sucesso depois de gravar
     */
    public static void abrir(Window dono, AppContext ctx, List<CadastroApi.Categoria> categorias, JsonNode p, Preenchimento pre,
            Consumer<String> aoSalvar) {
        boolean novo = p == null;
        TextField nome = Ui.campo("Ex.: Festival de Cervejas, Oferta da Semana");
        ComboBox<String> tipo = Ui.combo(new ArrayList<>(PromocoesView.TIPOS.values()));
        TextField percentual = Ui.campo("Ex.: 10 para 10%");
        TextField valor = Ui.campo("Ex.: 2,50");
        TextField precoFixo = Ui.campo("Ex.: 4,99");
        TextField leva = Ui.campo("Ex.: 3");
        TextField paga = Ui.campo("Ex.: 2");
        TextField qtdCombo = Ui.campo("Ex.: 3");
        TextField precoCombo = Ui.campo("Ex.: 10,00");
        TextField qtdAtacado = Ui.campo("Ex.: 10");
        TextField precoAtacado = Ui.campo("Ex.: 3,50");
        DatePicker iniData = new DatePicker();
        TextField iniHora = Ui.campo("00:00");
        DatePicker fimData = new DatePicker();
        TextField fimHora = Ui.campo("23:59");
        iniHora.setPrefWidth(70);
        fimHora.setPrefWidth(70);
        ComboBox<String> categoria = Ui.combo(new ArrayList<>());
        Map<String, String> catPorNome = new LinkedHashMap<>();
        catPorNome.put("Nenhuma categoria específica", "");
        for (CadastroApi.Categoria c : CadastrosView.ordenar(categorias.stream().filter(CadastroApi.Categoria::ativa).toList())) {
            catPorNome.put(c.caminho(), c.id());
        }
        categoria.setItems(FXCollections.observableArrayList(catPorNome.keySet()));
        categoria.setValue("Nenhuma categoria específica");
        categoria.setPrefWidth(360);

        ObservableList<Produto> produtos = FXCollections.observableArrayList();
        ListView<Produto> listaProdutos = new ListView<>(produtos);
        listaProdutos.setPrefHeight(150);
        listaProdutos.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Produto it, boolean vazio) {
                super.updateItem(it, vazio);
                setText(vazio || it == null ? null : it.codigo() + "  ·  " + it.nome() + "  ·  " + Fmt.money(it.preco()));
            }
        });
        Label contagem = Ui.muted("");
        Runnable recontar = () -> contagem.setText(produtos.size() + " produto(s) selecionado(s)");
        TextField buscaProduto = Ui.campo("Digite 2 letras do nome, o código ou o código de barras");
        HBox.setHgrow(buscaProduto, Priority.ALWAYS);
        BuscaProdutoCampo bp = new BuscaProdutoCampo(buscaProduto, t -> ctx.catalogo.buscar(t, 60), ctx.catalogo::porCodigoOuGtin,
                pr -> {
                    if (produtos.stream().noneMatch(x -> x.id().equals(pr.id()))) {
                        produtos.add(pr);
                        recontar.run();
                    }
                });
        Button tirar = Ui.botao("Remover selecionado", () -> {
            Produto sel = listaProdutos.getSelectionModel().getSelectedItem();
            if (sel != null) {
                produtos.remove(sel);
                recontar.run();
            }
        });

        // valores iniciais
        LocalDateTime agora = LocalDateTime.now().withSecond(0).withNano(0);
        if (novo) {
            tipo.setValue(PromocoesView.TIPOS.get("desconto_percentual"));
            leva.setText("3");
            paga.setText("2");
            qtdCombo.setText("3");
            qtdAtacado.setText("3");
            iniData.setValue(agora.toLocalDate());
            iniHora.setText(agora.format(HORA));
            fimData.setValue(agora.toLocalDate().plusDays(7));
            fimHora.setText(agora.format(HORA));
        } else {
            nome.setText(p.path("nome").asText());
            String t = p.path("tipo").asText();
            tipo.setValue(PromocoesView.TIPOS.getOrDefault(t, PromocoesView.TIPOS.get("desconto_percentual")));
            String vd = p.hasNonNull("valorDesconto") ? Fmt.dec2(p.get("valorDesconto").decimalValue()) : "";
            String pf = p.hasNonNull("precoFixo") ? Fmt.dec2(p.get("precoFixo").decimalValue()) : "";
            percentual.setText(vd);
            valor.setText(vd);
            precoFixo.setText(pf);
            precoCombo.setText(pf);
            precoAtacado.setText(pf);
            leva.setText(p.hasNonNull("quantidadeLeva") ? p.get("quantidadeLeva").asText() : "");
            paga.setText(p.hasNonNull("quantidadePaga") ? p.get("quantidadePaga").asText() : "");
            String qm = p.hasNonNull("quantidadeMinima") ? p.get("quantidadeMinima").asText() : "";
            qtdCombo.setText(qm);
            qtdAtacado.setText(qm);
            OffsetDateTime ini = PromocoesView.instante(p.path("inicioVigencia").asText());
            OffsetDateTime fim = PromocoesView.instante(p.path("fimVigencia").asText());
            if (ini != null) {
                LocalDateTime l = ini.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
                iniData.setValue(l.toLocalDate());
                iniHora.setText(l.format(HORA));
            }
            if (fim != null) {
                LocalDateTime l = fim.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
                fimData.setValue(l.toLocalDate());
                fimHora.setText(l.format(HORA));
            }
            String catId = p.path("categoriaId").asText("");
            catPorNome.forEach((n, id) -> {
                if (!catId.isBlank() && id.equals(catId)) {
                    categoria.setValue(n);
                }
            });
            p.path("produtoIds").forEach(id -> {
                Produto pr = ctx.catalogo.porId(id.asText());
                if (pr != null) {
                    produtos.add(pr);
                }
            });
        }
        if (novo && pre != null) {
            if (pre.nome() != null) {
                nome.setText(pre.nome());
            }
            if (pre.percentual() != null) {
                percentual.setText(Fmt.dec2(pre.percentual()));
            }
            if (pre.fim() != null) {
                fimData.setValue(pre.fim().toLocalDate());
                fimHora.setText(pre.fim().format(HORA));
            }
            for (Produto pr : pre.produtos()) {
                if (produtos.stream().noneMatch(x -> x.id().equals(pr.id()))) {
                    produtos.add(pr);
                }
            }
        }
        recontar.run();

        // campos que mudam conforme o tipo
        VBox regra = new VBox(8);
        Runnable montarRegra = () -> {
            regra.getChildren().clear();
            switch (PromocoesView.chaveTipo(tipo.getValue())) {
                case "desconto_percentual" -> regra.getChildren().add(Ui.rotulado("Percentual de desconto (%) *", percentual));
                case "desconto_valor" -> regra.getChildren().add(Ui.rotulado("Valor do desconto por unidade (R$) *", valor));
                case "preco_fixo" -> regra.getChildren().add(Ui.rotulado("Preço promocional fixo (R$) *", precoFixo));
                case "leve_x_pague_y" -> regra.getChildren().add(Ui.barra(Ui.rotulado("Quantidade leva *", leva),
                        Ui.rotulado("Quantidade paga *", paga)));
                case "combo_quantidade" -> regra.getChildren().add(Ui.barra(Ui.rotulado("Qtd do combo *", qtdCombo),
                        Ui.rotulado("Preço total do combo (R$) *", precoCombo)));
                default -> regra.getChildren().add(Ui.barra(Ui.rotulado("A partir de (qtd mínima) *", qtdAtacado),
                        Ui.rotulado("Preço unitário de atacado (R$) *", precoAtacado)));
            }
        };
        tipo.setOnAction(e -> montarRegra.run());
        montarRegra.run();

        VBox corpo = new VBox(10,
                Ui.rotulado("Nome da campanha *", nome),
                Ui.rotulado("Tipo de promoção *", tipo),
                regra,
                Ui.barra(Ui.rotulado("Início da vigência *", new HBox(6, iniData, iniHora)),
                        Ui.rotulado("Fim da vigência *", new HBox(6, fimData, fimHora))),
                Ui.rotulado("Aplicar a uma categoria inteira (opcional)", categoria),
                Ui.texto("Produtos específicos", "rg-section-title"),
                new HBox(8, buscaProduto, Ui.botao("Adicionar", bp::confirmar)),
                listaProdutos, Ui.barra(contagem, Ui.espaco(), tirar));

        Modal[] m = new Modal[1];
        m[0] = Modal.formularioRolavel(dono, novo ? "Nova promoção" : "Editar promoção",
                "Selecione ao menos um produto ou uma categoria.", corpo, novo ? "Criar promoção" : "Salvar promoção", 720, 640,
                () -> {
                    Map<String, Object> corpoApi;
                    try {
                        corpoApi = PromocoesView.montarCorpo(nome.getText(), PromocoesView.chaveTipo(tipo.getValue()), percentual.getText(), valor.getText(),
                                precoFixo.getText(), leva.getText(), paga.getText(), qtdCombo.getText(), precoCombo.getText(),
                                qtdAtacado.getText(), precoAtacado.getText(), PromocoesView.quando(iniData.getValue(), iniHora.getText(), "início"),
                                PromocoesView.quando(fimData.getValue(), fimHora.getText(), "fim"), catPorNome.get(categoria.getValue()),
                                produtos.stream().map(Produto::id).toList(), novo || p.path("ativa").asBoolean(true));
                    } catch (DomainException e) {
                        m[0].erro(e.getMessage());
                        return false;
                    }
                    m[0].ocupado(true);
                    Carregando.async(novo ? "Criando a promoção…" : "Salvando a promoção…",
                            () -> ctx.promocoes.salvar(novo ? null : p.path("id").asText(), corpoApi), r -> {
                                m[0].ocupado(false);
                                m[0].fechar();
                                aoSalvar.accept(novo ? "Promoção \"" + nome.getText().trim() + "\" criada." : "Promoção atualizada.");
                            }, err -> {
                                m[0].ocupado(false);
                                m[0].erro(Fx.message(err));
                            });
                    return false;
                });
        m[0].mostrar();
    }
}
