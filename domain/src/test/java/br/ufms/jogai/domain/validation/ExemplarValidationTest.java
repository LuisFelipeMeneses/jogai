package br.ufms.jogai.domain.validation;

import br.ufms.jogai.domain.jogo.*;
import br.ufms.jogai.domain.usuario.UsuarioInfo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ExemplarValidationTest {

    @Test
    void validaTodosOsObrigatoriosNaCriacaoEReconstituicao() {
        var jogo = new JogoInfo(JogoValidationTest.builder().build());
        var dono = new UsuarioInfo(UsuarioValidationTest.valido());
        var id = UUID.randomUUID();
        var agora = Instant.now();
        assertThrows(NullPointerException.class, () -> Exemplar.create(null, dono, EstadoConservacao.NOVO, null));
        assertThrows(NullPointerException.class, () -> Exemplar.create(jogo, null, EstadoConservacao.NOVO, null));
        assertThrows(NullPointerException.class, () -> Exemplar.create(jogo, dono, null, null));
        assertThrows(NullPointerException.class, () -> Exemplar.reconstitute(null, jogo, dono, EstadoConservacao.NOVO, null, agora));
        assertThrows(NullPointerException.class, () -> Exemplar.reconstitute(id, null, dono, EstadoConservacao.NOVO, null, agora));
        assertThrows(NullPointerException.class, () -> Exemplar.reconstitute(id, jogo, null, EstadoConservacao.NOVO, null, agora));
        assertThrows(NullPointerException.class, () -> Exemplar.reconstitute(id, jogo, dono, null, null, agora));
        assertThrows(NullPointerException.class, () -> Exemplar.reconstitute(id, jogo, dono, EstadoConservacao.NOVO, null, null));
        var exemplar = Exemplar.reconstitute(id, jogo, dono, EstadoConservacao.NOVO, "  completo  ", agora);
        assertEquals(id, exemplar.getId());
        assertEquals(agora, exemplar.getDataCadastro());
        assertEquals("completo", exemplar.getObservacao());
        assertThrows(NullPointerException.class, () -> exemplar.setEstadoConservacao(null));
        assertEquals(EstadoConservacao.NOVO, exemplar.getEstadoConservacao());
        for (var estado : EstadoConservacao.values()) {
            exemplar.setEstadoConservacao(estado);
            assertEquals(estado != EstadoConservacao.DANIFICADO, exemplar.isJogavel());
        }
        exemplar.setObservacao(null);
        assertNull(exemplar.getObservacao());
    }
}
