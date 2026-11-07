package br.com.quack.estoque.ui;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.domain.Fmt;
import br.com.quack.estoque.domain.Perfil;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/** Entradas de mercadoria: nova entrada por NF-e (F3) ou manual, lista de notas, itens, XML e estorno. */
public class EntradasView extends Tela {
    private final Perfil perfil;
    private final TextField busca = Ui.campo("Fornecedor, número ou chave da nota (F2)");
    private final TableView<JsonNode> tabela = Ui.tabela();
    private final Label rodape = Ui.muted("");
    private int pagina = 1;
    private int totalNotas;
    private static final int TAMANHO = 30;

    public EntradasView(AppContext ctx, Navegador nav) {
        super(ctx, nav);
        this.perfil = ctx.session.perfil();
        Button nfe = Ui.primario("Nova entrada por NF-e (F3)", this::novaEntradaNfe);
        Button manual = Ui.botao("Entrada manual", () -> EntradaManualDialog.abrir(ctx, janela(), this::ativar, () -> nav.ir("etiquetas")));
        getChildren().add(Ui.cabecalho("Entradas de mercadoria", "Receba por NF-e (XML ou chave) ou lance uma entrada manual.", manual, nfe));

        busca.setOnAction(e -> {
            pagina = 1;
            ativar();
        });
        javafx.scene.layout.HBox.setHgrow(busca, Priority.ALWAYS);
        Button itens = Ui.botao("Ver itens", () -> {
            JsonNode n = tabela.getSelectionModel().getSelectedItem();
            if (n != null) {
                verItens(n);
            }
        });
        Button xml = Ui.botao("Baixar XML", () -> {
            JsonNode n = tabela.getSelectionModel().getSelectedItem();
            if (n != null) {
                baixarXml(n);
            }
        });
        Button estornar = Ui.perigo("Estornar", () -> {
            JsonNode n = tabela.getSelectionModel().getSelectedItem();
            if (n != null) {
                estornar(n);
            }
        });
        estornar.setVisible(perfil.estornarEntrada());
        estornar.setManaged(perfil.estornarEntrada());
        getChildren().add(Ui.barra(busca, Ui.botao("Buscar", () -> {
            pagina = 1;
            ativar();
        }), itens, xml, estornar));

        Ui.col(tabela, "Recebida em", n -> Fmt.dateTime(n.path("criadaEm").asText("")), 120);
        Ui.col(tabela, "Documento", EntradasView::documento, 200);
        Ui.col(tabela, "Fornecedor", n -> n.path("fornecedorNome").asText(""), 220);
        Ui.colNum(tabela, "Itens", n -> String.valueOf(n.path("quantidadeItens").asInt()), 60);
        if (perfil.verCustos()) {
            Ui.colNum(tabela, "Custo recebido", n -> Fmt.money(n.path("valorEntrada").decimalValue()), 110);
        }
        Ui.col(tabela, "Usuário", n -> n.path("usuarioNome").asText(""), 130);
        Ui.col(tabela, "Situação", n -> n.path("estornada").asBoolean(false) ? "Estornada" : "Ativa", 90);
        tabela.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && tabela.getSelectionModel().getSelectedItem() != null) {
                verItens(tabela.getSelectionModel().getSelectedItem());
            }
        });
        VBox.setVgrow(tabela, Priority.ALWAYS);

        Button anterior = Ui.pequeno("‹ Anterior", () -> {
            if (pagina > 1) {
                pagina--;
                ativar();
            }
        });
        Button proxima = Ui.pequeno("Próxima ›", () -> {
            if (pagina * TAMANHO < totalNotas) {
                pagina++;
                ativar();
            }
        });
        getChildren().addAll(tabela, Ui.barra(anterior, proxima, rodape));
    }

    /** "NF-e 55 nº 1234 série 1", "Cupom nº 56" ou "Recibo". */
    static String documento(JsonNode n) {
        String origem = n.path("origem").asText("");
        String numero = n.path("numeroNota").asText("");
        return switch (origem) {
            case "manual" -> "Entrada manual" + (numero.isBlank() ? "" : " nº " + numero);
            case "digitada" -> "Cupom digitado" + (numero.isBlank() ? "" : " nº " + numero);
            default -> "NF-e " + n.path("modelo").asInt(55) + " nº " + numero
                    + (n.path("serie").asText("").isBlank() ? "" : " série " + n.path("serie").asText());
        };
    }

    private void novaEntradaNfe() {
        EntradaNfeDialog.abrir(ctx, janela(), this::ativar, () -> nav.ir("etiquetas"));
    }

    @Override
    public void ativar() {
        rodar(() -> ctx.entradas.listarNotas(busca.getText(), pagina, TAMANHO), data -> {
            List<JsonNode> notas = new ArrayList<>();
            if (data != null) {
                data.path("notas").forEach(notas::add);
                totalNotas = data.path("total").asInt(notas.size());
            }
            tabela.setItems(FXCollections.observableArrayList(notas));
            rodape.setText("Página " + pagina + " · " + totalNotas + " entradas");
        });
    }

    @Override
    public void focarBusca() {
        busca.requestFocus();
    }

    private void verItens(JsonNode resumo) {
        ItensEntradaDialog.abrir(ctx, janela(), resumo);
    }

    private void baixarXml(JsonNode n) {
        if (!n.path("temXml").asBoolean(false)) {
            Toasts.error(janela(), "Esta entrada não tem XML guardado.");
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle("Salvar XML da nota");
        fc.setInitialFileName("nota-entrada-" + n.path("numeroNota").asText("xml") + ".xml");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("XML", "*.xml"));
        File f = fc.showSaveDialog(janela());
        if (f == null) {
            return;
        }
        rodar(() -> ctx.entradas.baixarXml(n.path("id").asText()), bytes -> {
            try {
                Files.write(f.toPath(), bytes);
                sucesso("XML salvo em " + f.getName() + ".");
            } catch (IOException e) {
                erro(e);
            }
        });
    }

    private void estornar(JsonNode n) {
        if (n.path("estornada").asBoolean(false)) {
            Toasts.error(janela(), "Esta entrada já foi estornada.");
            return;
        }
        Modal.pedirTexto(janela(), "Estornar entrada", "Justificativa (mínimo 5 caracteres)", "", "Estornar", just -> {
            if (just.length() < 5) {
                Toasts.error(janela(), "Informe uma justificativa com pelo menos 5 caracteres.");
                return;
            }
            rodar(() -> {
                ctx.entradas.estornar(n.path("id").asText(), just);
                ctx.garantirCatalogo(true);
                return true;
            }, ok -> {
                sucesso("Entrada estornada: o estoque foi ajustado.");
                ativar();
            });
        });
    }
}
