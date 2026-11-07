package br.com.quack.pdv.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import br.com.quack.pdv.auditoria.app.AuditoriaService;
import br.com.quack.pdv.auth.app.AutenticacaoService;
import br.com.quack.pdv.auth.app.CadastroPublicoService;
import br.com.quack.pdv.auth.app.RecaptchaService;
import br.com.quack.pdv.auth.app.RecuperacaoSenhaService;
import br.com.quack.pdv.email.app.EmailService;
import br.com.quack.pdv.security.JwtService;
import br.com.quack.pdv.sessao.app.SessaoService;
import br.com.quack.pdv.sessao.app.ValidadorSessao;
import br.com.quack.pdv.usuario.app.UsuarioService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base dos testes web: aplicacao inteira (filtros, interceptor, advice, Jackson) em MockMvc, sem banco; os servicos
 * ficam como mocks. O token e emitido pelo JwtService real, e o ValidadorSessao devolve a situacao combinada.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class WebTestBase {

    @Autowired protected MockMvc mvc;
    @Autowired protected JwtService jwt;

    @MockitoBean protected br.com.quack.pdv.empresa.app.EmpresaService empresas;
    @MockitoBean protected br.com.quack.pdv.empresa.app.EmpresaTemaService temas;
    @MockitoBean protected br.com.quack.pdv.cadastro.fornecedor.app.FornecedorService fornecedores;
    @MockitoBean protected br.com.quack.pdv.cadastro.categoria.app.CategoriaService categorias;
    @MockitoBean protected br.com.quack.pdv.empresa.app.TreinamentoService treinamento;
    @MockitoBean protected br.com.quack.pdv.cadastro.mercado.app.ModuloMercadoService modulos;
    @MockitoBean protected br.com.quack.pdv.cadastro.cliente.app.ClienteService clientes;
    @MockitoBean protected br.com.quack.pdv.cadastro.cliente.app.ClienteTransacao clienteTransacao;
    @MockitoBean protected br.com.quack.pdv.empresa.app.GerenciamentoEmpresasService gerenciamento;
    @MockitoBean protected br.com.quack.pdv.empresa.repo.GerenciamentoEmpresasRepository gerenciamentoRepo;
    @MockitoBean protected br.com.quack.pdv.estoque.registrador.RegistradorEstoque registradorEstoque;
    @MockitoBean protected br.com.quack.pdv.usuario.app.ValidadorSupervisor validadorSupervisor;
    @MockitoBean protected br.com.quack.pdv.produto.lote.app.LoteOperacoes loteOperacoes;
    @MockitoBean protected br.com.quack.pdv.produto.lote.app.LoteService loteService;
    @MockitoBean protected br.com.quack.pdv.produto.produto.app.ProdutoService produtos;
    @MockitoBean protected br.com.quack.pdv.estoque.movimentacao.app.EstoqueMovimentacaoService movimentacoes;
    @MockitoBean protected br.com.quack.pdv.produto.importacao.ImportacaoProdutosService importacaoProdutos;
    @MockitoBean protected br.com.quack.pdv.produto.importacao.GravadorImportacao gravadorImportacao;
    @MockitoBean protected br.com.quack.pdv.produto.entrada.app.EntradaManualService entradaManual;
    @MockitoBean protected br.com.quack.pdv.produto.entrada.app.NfeImportService nfeImport;
    @MockitoBean protected br.com.quack.pdv.produto.entrada.app.NotaEntradaArquivoService notasEntrada;
    @MockitoBean protected br.com.quack.pdv.compras.ordem.app.OrdemCompraService ordensCompra;
    @MockitoBean protected br.com.quack.pdv.promocao.promocao.app.PromocaoService promocoes;
    @MockitoBean protected br.com.quack.pdv.estoque.inventario.app.InventarioService inventarios;
    @MockitoBean protected br.com.quack.pdv.estoque.inventario.app.InventarioConsultas inventarioConsultas;
    @MockitoBean protected br.com.quack.pdv.plataforma.numeracao.NumeradorDocumentos numeradorDocumentos;
    @MockitoBean protected br.com.quack.pdv.produto.lote.app.LotePendencias lotePendencias;
    @MockitoBean protected br.com.quack.pdv.produto.lote.app.RetentativaBaixaLotes retentativaBaixaLotes;
    @MockitoBean protected br.com.quack.pdv.venda.caixa.app.CaixaService caixaService;
    @MockitoBean protected br.com.quack.pdv.venda.caixa.app.CaixaStatusService caixaStatusService;
    @MockitoBean protected br.com.quack.pdv.venda.caixa.app.CaixaOperacoes caixaOperacoes;
    @MockitoBean protected br.com.quack.pdv.venda.caixa.repo.CaixaRepository caixaRepository;
    @MockitoBean protected br.com.quack.pdv.venda.registro.repo.VendaRepository vendaRepository;
    @MockitoBean protected br.com.quack.pdv.venda.registro.app.BaixaEstoqueVenda baixaEstoqueVenda;
    @MockitoBean protected br.com.quack.pdv.venda.registro.app.AutorizacaoPrecoService autorizacaoPreco;
    @MockitoBean protected br.com.quack.pdv.venda.registro.app.VendaRegistroService vendaRegistro;
    @MockitoBean protected br.com.quack.pdv.venda.registro.app.VendaConsultaService vendaConsulta;
    @MockitoBean protected br.com.quack.pdv.venda.fiado.app.FiadoDebitos fiadoDebitos;
    @MockitoBean protected br.com.quack.pdv.venda.fiado.app.CreditoGateway creditoGateway;
    @MockitoBean protected br.com.quack.pdv.venda.fiado.app.FiadoService fiadoService;
    @MockitoBean protected br.com.quack.pdv.venda.fiado.repo.FiadoRepository fiadoRepository;
    @MockitoBean protected br.com.quack.pdv.venda.pedido.app.PedidoService pedidoService;
    @MockitoBean protected br.com.quack.pdv.venda.pedido.repo.PedidoRepository pedidoRepository;
    @MockitoBean protected br.com.quack.pdv.fiscal.fila.repo.DocumentoFiscalConsulta documentoFiscalConsulta;
    @MockitoBean protected br.com.quack.pdv.fiscal.fila.app.DecisaoEmissaoNfce decisaoEmissao;
    @MockitoBean protected br.com.quack.pdv.fiscal.fila.app.PendenciaFiscal pendenciaFiscal;
    @MockitoBean protected br.com.quack.pdv.fiscal.fila.app.FiscalDaVenda fiscalDaVenda;
    @MockitoBean protected br.com.quack.pdv.fiscal.fila.app.EmitenteConfigurado emitenteConfigurado;
    @MockitoBean protected br.com.quack.pdv.fiscal.fila.app.FilaFiscalMinima filaFiscalMinima;
    @MockitoBean protected br.com.quack.pdv.fiscal.fila.repo.FilaFiscalRepository filaFiscalRepository;
    @MockitoBean protected br.com.quack.pdv.venda.fiado.app.FiadoEstornos fiadoEstornos;
    @MockitoBean protected br.com.quack.pdv.venda.cancelamento.repo.CancelamentoRepository cancelamentoRepository;
    @MockitoBean protected br.com.quack.pdv.venda.cancelamento.repo.CorrecaoRepository correcaoRepository;
    @MockitoBean protected br.com.quack.pdv.venda.cancelamento.app.CancelamentoVendaService cancelamentoVenda;
    @MockitoBean protected br.com.quack.pdv.venda.cancelamento.app.CorrecaoVendaService correcaoVenda;
    @MockitoBean protected br.com.quack.pdv.venda.cancelamento.app.CancelamentoConsultas cancelamentoConsultas;
    @MockitoBean protected ValidadorSessao validador;
    @MockitoBean protected AutenticacaoService autenticacao;
    @MockitoBean protected CadastroPublicoService cadastro;
    @MockitoBean protected RecuperacaoSenhaService recuperacao;
    @MockitoBean protected SessaoService sessoes;
    @MockitoBean protected UsuarioService usuarios;
    @MockitoBean protected EmailService email;
    @MockitoBean protected AuditoriaService auditoria;
    @MockitoBean protected RecaptchaService recaptcha;

    @BeforeEach
    void recaptchaDesligadoPorPadrao() {
        when(recaptcha.verificar(any(), any(), any()))
                .thenReturn(new RecaptchaService.Resultado(true, 200, "", "", true));
    }

    /** Token valido de um usuario da empresa-principal, com a sessao e o usuario "ativos" no ValidadorSessao. */
    protected String tokenDe(String role, String... perfisAdicionais) {
        return tokenDaEmpresa("usr-1", "empresa-principal", role, "aprovada", perfisAdicionais);
    }

    protected String tokenDaEmpresa(String userId, String companyId, String role, String statusEmpresa, String... perfis) {
        String sessionId = "sess-" + userId;
        String token = jwt.criarToken(userId, companyId, "Fulano", "fulano@x.com", role, sessionId, "10.0.0.1");
        when(validador.validar(eq(sessionId), eq(userId), eq(companyId)))
                .thenReturn(new ValidadorSessao.Validacao(true, true, statusEmpresa, String.join(",", perfis)));
        return token;
    }
}
