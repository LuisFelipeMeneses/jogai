package br.ufms.jogai.domain.validation;

import br.ufms.jogai.domain.jogo.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class JogoValidationTest {

    static CategoriaJogo categoria() {
        return CategoriaJogo.create("estrategia", "Estratégia", null);
    }

    static Jogo.Builder builder() {
        return Jogo.create().nomeEditora("Catan", "Devir").numeroJogadores(2, 4).categorias(categoria());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejeitaTextosObrigatorios(String texto) {
        assertThrows(IllegalArgumentException.class, () -> builder().nomeEditora(texto, "Devir").build());
        assertThrows(IllegalArgumentException.class, () -> builder().nomeEditora("Catan", texto).build());
        assertThrows(IllegalArgumentException.class, () -> CategoriaJogo.create("chave", texto, null));
        var jogo = builder().build();
        assertThrows(IllegalArgumentException.class, () -> jogo.setNome(texto));
        assertThrows(IllegalArgumentException.class, () -> jogo.setEditora(texto));
        assertEquals("Catan", jogo.getNome());
        assertEquals("Devir", jogo.getEditora());
        var categoria = categoria();
        assertThrows(IllegalArgumentException.class, () -> categoria.setNome(texto));
        assertEquals("Estratégia", categoria.getNome());
    }

    @Test
    void limitesDosTextos() {
        var jogo = builder().nomeEditora("A", "B").build();
        jogo.setNome("A".repeat(50));
        jogo.setEditora("B".repeat(50));
        assertThrows(IllegalArgumentException.class, () -> jogo.setNome("A".repeat(51)));
        assertThrows(IllegalArgumentException.class, () -> jogo.setEditora("B".repeat(51)));
        var c = CategoriaJogo.create("chave", "AB", null);
        c.setNome("A".repeat(30));
        assertThrows(IllegalArgumentException.class, () -> c.setNome("A"));
        assertThrows(IllegalArgumentException.class, () -> c.setNome("A".repeat(31)));
    }

    @Test
    void validaAno() {
        var jogo = builder().build();
        assertNull(jogo.getAnoLancamento());
        jogo.setAnoLancamento(Year.of(1));
        jogo.setAnoLancamento(Year.now());
        for (var ano : new Year[]{Year.of(0), Year.of(-1), Year.now().plusYears(1), Year.of(Year.MAX_VALUE)}) {
            assertThrows(IllegalArgumentException.class, () -> jogo.setAnoLancamento(ano));
            assertThrows(IllegalArgumentException.class, () -> builder().nomeEditoraLancamento("Catan", "Devir", ano.getValue()).build());
        }
    }

    @Test
    void validaIdentidadeEObrigatoriosDoBuilder() {
        assertThrows(NullPointerException.class, () -> Jogo.reconstitute(null, true).nomeEditora("Catan", "Devir").numeroJogadores(1, 2).categorias(categoria()).build());
        assertThrows(IllegalArgumentException.class, () -> Jogo.create().nomeEditora("Catan", "Devir").categorias(categoria()).build());
        assertThrows(IllegalArgumentException.class, () -> Jogo.create().nomeEditora("Catan", "Devir").numeroJogadores(1, 2).build());
        assertThrows(IllegalArgumentException.class, () -> builder().categorias().build());
        assertThrows(NullPointerException.class, () -> CategoriaJogo.reconstitute(null, "Estratégia", null));
    }

    @Test
    void protegeCategoriasContraRemocaoTotalEMutacaoExterna() {
        var jogo = builder().build();
        var unica = jogo.getCategorias().iterator().next();
        assertThrows(IllegalArgumentException.class, () -> jogo.adicionarCategoria(null));
        assertThrows(IllegalArgumentException.class, () -> jogo.adicionarCategorias((CategoriaJogo[]) null));
        assertThrows(IllegalArgumentException.class, () -> jogo.removerCategorias((CategoriaJogo[]) null));
        assertThrows(IllegalStateException.class, () -> jogo.removerCategoria(unica));
        assertThrows(IllegalStateException.class, () -> jogo.removerCategorias(unica, unica));
        assertThrows(UnsupportedOperationException.class, () -> jogo.getCategorias().clear());
        assertEquals(1, jogo.getCategorias().size());
        var outra = CategoriaJogo.create("familia", "Família", null);
        jogo.adicionarCategoria(outra);
        jogo.removerCategoria(unica);
        assertEquals(Set.of(outra), jogo.getCategorias());
    }

    @ParameterizedTest
    @CsvSource({"0,1", "-1,1", "2,1", "1,0", "1,-1", "-2147483648,2147483647"})
    void rejeitaIntervalosDeJogadores(int minimo, int maximo) {
        assertThrows(IllegalArgumentException.class, () -> new NumeroJogadores(minimo, maximo));
        assertThrows(IllegalArgumentException.class, () -> builder().numeroJogadores(minimo, maximo));
    }

    @ParameterizedTest
    @CsvSource({"-1,10", "1,-1", "18,17", "-2147483648,2147483647"})
    void rejeitaFaixaEtaria(int minimo, int maximo) {
        assertThrows(IllegalArgumentException.class, () -> new FaixaEtaria(minimo, maximo));
        assertThrows(IllegalArgumentException.class, () -> builder().faixaEtaria(minimo, maximo));
    }

    @Test
    void validaDuracoesIncluindoFracoesNegativas() {
        assertThrows(IllegalArgumentException.class, () -> new DuracaoPartida(Duration.ofNanos(-1), null));
        assertThrows(IllegalArgumentException.class, () -> new DuracaoPartida(null, Duration.ofNanos(-1)));
        assertThrows(IllegalArgumentException.class, () -> new DuracaoPartida(20, 19));
        assertThrows(IllegalArgumentException.class, () -> builder().duracaoPartida(-1, 20));
        assertThrows(IllegalArgumentException.class, () -> builder().duracaoPartida(20, -1));
        assertThrows(IllegalArgumentException.class, () -> builder().duracaoPartida(20, 19));
        assertEquals(Duration.ofMinutes(15), new DuracaoPartida(10, 20).estimativa());
    }

    @Test
    void aceitaLimitesEOpcionais() {
        assertEquals(1, new NumeroJogadores().minimo());
        assertNull(new NumeroJogadores(1, null).maximo());
        assertDoesNotThrow(() -> new NumeroJogadores(1, 1));
        assertDoesNotThrow(() -> new FaixaEtaria(0, 0));
        assertDoesNotThrow(() -> new FaixaEtaria(null, 18));
        assertDoesNotThrow(() -> new FaixaEtaria(18, null));
        assertThrows(IllegalArgumentException.class, () -> new FaixaEtaria(-1, null));
        assertThrows(IllegalArgumentException.class, () -> new FaixaEtaria(null, -1));
        var jogo = builder().descricao("  descrição  ").build();
        assertNull(jogo.getTempoEstimado());
        assertNull(jogo.getIdadeMinimaRecomendada());
        assertEquals("descrição", jogo.getDescricao());
        jogo.setDescricao(null);
        assertNull(jogo.getDescricao());
        jogo.setFaixaEtaria(null);
        assertNotNull(jogo.getFaixaEtaria());
        var c = CategoriaJogo.create("chave", "  Estratégia  ", "  descrição  ");
        assertEquals("Estratégia", c.getNome());
        assertEquals("descrição", c.getDescricao());
        c.setDescricao(null);
        assertNull(c.getDescricao());
        jogo.inativar();
        assertFalse(jogo.isAtivo());
        jogo.ativar();
        assertTrue(jogo.isAtivo());
    }
}
