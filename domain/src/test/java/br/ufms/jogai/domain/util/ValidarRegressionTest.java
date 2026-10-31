package br.ufms.jogai.domain.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.Year;

import static org.junit.jupiter.api.Assertions.*;

class ValidarRegressionTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void camposOpcionaisAceitamAusencia(String valor) {
        assertEquals("", Validar.email(valor, false));
        assertEquals("", Validar.telefone(valor, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalido", "a..b@example.com", "a@-example.com"})
    void emailOpcionalValidaValorInformado(String valor) {
        assertThrows(IllegalArgumentException.class, () -> Validar.email(valor, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "00999991234", "6712345678"})
    void telefoneOpcionalValidaValorInformado(String valor) {
        assertThrows(IllegalArgumentException.class, () -> Validar.telefone(valor, false));
    }

    @Test
    void anoOpcionalNuloTemMesmoComportamentoNasDuasSobrecargas() {
        assertNull(Validar.anoLancamentoJogo((Integer) null));
        assertNull(Validar.anoLancamentoJogo((Year) null));
        assertNull(Validar.anoLancamentoJogo((Integer) null, false));
        assertThrows(IllegalArgumentException.class, () -> Validar.anoLancamentoJogo((Integer) null, true));
    }

    @Test
    void nascimentoOpcionalValidaPresencaELimitesPorDia() {
        assertNull(Validar.dataNascimento(null, false));
        assertThrows(IllegalArgumentException.class, () -> Validar.dataNascimento(null, true));
        LocalDate limite = LocalDate.now().minusYears(150);
        assertEquals(limite, Validar.dataNascimento(limite));
        assertThrows(IllegalArgumentException.class, () -> Validar.dataNascimento(limite.minusDays(1), false));
    }

    @Test
    void conversorQueRetornaNuloNaoDeveRestaurarEntradaInvalida() {
        var validador = new ValidadorString("Campo", "entrada descartada", true, valor -> null);
        assertThrows(IllegalArgumentException.class, validador::validarNulo);
    }

    @Test
    void nomeComAcentoCombinadoEhNormalizado() {
        assertEquals("José Silva", Validar.nomePessoa("Jose\u0301 Silva", true));
    }
}
