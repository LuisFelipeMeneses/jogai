package br.ufms.jogai.domain.validation;

import br.ufms.jogai.domain.jogo.*;
import br.ufms.jogai.domain.usuario.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.time.*;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReconstituicaoValidationTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void dadosPersistidosTambemDevemRespeitarTextosObrigatorios(String texto) {
        var id = UUID.randomUUID();
        var nascimento = LocalDate.of(1990, 1, 1);
        var cadastro = Instant.parse("2020-01-01T00:00:00Z");
        Class<? extends RuntimeException> erroEmail = texto == null ? NullPointerException.class : IllegalArgumentException.class;
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> Usuario.reconstitute(id, texto, "a@b.com", "67999991234", nascimento, cadastro, StatusUsuario.ATIVO)),
                () -> assertThrows(erroEmail, () -> Usuario.reconstitute(id, "João Silva", texto, "67999991234", nascimento, cadastro, StatusUsuario.ATIVO)),
                () -> assertThrows(IllegalArgumentException.class, () -> Usuario.reconstitute(id, "João Silva", "a@b.com", texto, nascimento, cadastro, StatusUsuario.ATIVO)),
                () -> assertThrows(IllegalArgumentException.class, () -> Jogo.reconstitute(id, false).nomeEditora(texto, "Devir").numeroJogadores(2, 4).categorias(JogoValidationTest.categoria()).build()),
                () -> assertThrows(IllegalArgumentException.class, () -> Jogo.reconstitute(id, false).nomeEditora("Catan", texto).numeroJogadores(2, 4).categorias(JogoValidationTest.categoria()).build()),
                () -> assertThrows(IllegalArgumentException.class, () -> CategoriaJogo.reconstitute("estrategia", texto, null)));
    }

    @Test
    void reconstituicaoPreservaEstadoEValoresOpcionais() {
        var id = UUID.randomUUID();
        var jogo = Jogo.reconstitute(id, false).nomeEditoraLancamento("Catan", "Devir", 1995)
                .numeroJogadores(2, 4).faixaEtaria(10, null).duracaoPartida(30, 90)
                .categorias(JogoValidationTest.categoria()).descricao(null).build();
        assertEquals(id, jogo.getId());
        assertFalse(jogo.isAtivo());
        assertEquals(Year.of(1995), jogo.getAnoLancamento());
        assertEquals(2, jogo.getMinimoJogadores());
        assertEquals(4, jogo.getMaximoJogadores());
        assertEquals(10, jogo.getIdadeMinimaRecomendada());
        assertNull(jogo.getIdadeMaximaRecomendada());
        assertEquals(Duration.ofMinutes(30), jogo.getTempoMinimoEstimado());
        assertEquals(Duration.ofMinutes(90), jogo.getTempoMaximoEstimado());
        assertEquals(Duration.ofMinutes(60), jogo.getTempoEstimado());
        assertNull(jogo.getDescricao());
        var categoria = CategoriaJogo.reconstitute("estrategia", "Estratégia", null);
        assertEquals("estrategia", categoria.getId());
        assertNull(categoria.getDescricao());
    }

    @Test
    void reconstituicaoRejeitaNascimentoFuturoEInvalido() {
        for (var data : new LocalDate[]{LocalDate.now().plusDays(1), LocalDate.now().minusYears(151)}) {
            assertThrows(IllegalArgumentException.class, () -> Usuario.reconstitute(UUID.randomUUID(), "João Silva", "a@b.com", "67999991234", data, Instant.now(), StatusUsuario.ATIVO));
        }
    }
}
