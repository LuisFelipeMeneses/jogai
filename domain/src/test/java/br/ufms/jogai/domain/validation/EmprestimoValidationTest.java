package br.ufms.jogai.domain.validation;

import br.ufms.jogai.domain.emprestimo.Emprestimo;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EmprestimoValidationTest {

    // O único ponto de entrada atual é o construtor protegido que recebe a identidade.
    private static class EmprestimoAcessivel extends Emprestimo {
        EmprestimoAcessivel(UUID id) {
            super(id);
        }
    }

    @Test
    void rejeitaIdentidadeNula() {
        assertThrows(NullPointerException.class, () -> new EmprestimoAcessivel(null));
    }

    @Test
    void preservaIdentidadeInformada() {
        var id = UUID.randomUUID();
        assertEquals(id, new EmprestimoAcessivel(id).getId());
    }
}
