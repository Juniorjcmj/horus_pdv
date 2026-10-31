package br.com.quack.estoque.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Regras de acesso por perfil (design D9). */
class PerfilAcessoTest {

    @Test
    void soEstoqueEGerenciaEntram() {
        assertEquals(Perfil.ADMINISTRADOR, Perfil.de("administrador").orElseThrow());
        assertEquals(Perfil.GERENTE, Perfil.de(" Gerente ").orElseThrow());
        assertEquals(Perfil.ESTOQUISTA, Perfil.de("ESTOQUISTA").orElseThrow());
        assertTrue(Perfil.de("caixa").isEmpty());
        assertTrue(Perfil.de("atendente").isEmpty());
        assertTrue(Perfil.de("integracao").isEmpty());
        assertTrue(Perfil.de(null).isEmpty());
        assertTrue(Perfil.de("").isEmpty());
    }

    @Test
    void gerenteEAdminTemTudo() {
        for (Perfil p : new Perfil[] {Perfil.ADMINISTRADOR, Perfil.GERENTE}) {
            assertTrue(p.verCustos());
            assertTrue(p.aplicarOuCancelarInventario());
            assertTrue(p.cadastrarProdutos());
            assertTrue(p.cadastrarCategoriasEFornecedores());
            assertTrue(p.estornarEntrada());
            assertTrue(p.gerarPedidoCompra());
            assertTrue(p.relatoriosDeValor());
        }
    }

    @Test
    void estoquistaNaoVeCustoNemAplicaNemCadastra() {
        Perfil e = Perfil.ESTOQUISTA;
        assertFalse(e.verCustos());
        assertFalse(e.aplicarOuCancelarInventario());
        assertFalse(e.cadastrarProdutos());
        assertFalse(e.cadastrarCategoriasEFornecedores());
        assertFalse(e.estornarEntrada());
        assertFalse(e.gerarPedidoCompra());
        assertFalse(e.relatoriosDeValor());
        assertFalse(e.gerencia());
    }
}
