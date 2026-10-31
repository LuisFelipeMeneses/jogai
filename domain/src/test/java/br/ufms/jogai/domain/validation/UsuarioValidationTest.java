package br.ufms.jogai.domain.validation;

import br.ufms.jogai.domain.usuario.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.time.*;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UsuarioValidationTest {

    static Usuario usuario(String nome, String email, String telefone, LocalDate nascimento) {
        return Usuario.create(nome, email, telefone, nascimento);
    }

    static Usuario valido() {
        return usuario("João Silva", "joao@example.com", "67999991234", LocalDate.of(1990, 1, 1));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "João", "12 Silva", "João 😊", "João@Silva", "João_Silva", "João.Silva", "João\nSilva"})
    void rejeitaNomeNaCriacaoENaAlteracao(String nome) {
        assertThrows(IllegalArgumentException.class, () -> usuario(nome, "joao@example.com", "67999991234", LocalDate.of(1990, 1, 1)));
        var u = valido();
        assertThrows(IllegalArgumentException.class, () -> u.setNome(nome));
        assertEquals("João Silva", u.getNome());
    }

    @Test
    void aceitaLimitesDeTamanhoDeNomeEEmail() {
        var u = valido();
        u.setNome("A B");
        assertEquals("A B", u.getNome());
        String nome = "A".repeat(48) + " B";
        u.setNome(nome);
        assertEquals(nome, u.getNome());
        String email = "a".repeat(38) + "@example.com";
        u.setEmail(email);
        assertEquals(email, u.getEmail());
    }

    @Test
    void rejeitaNomeAcimaDoLimite() {
        assertThrows(IllegalArgumentException.class, () -> valido().setNome("A".repeat(49) + " B"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "sem-arroba", "a@@b.com", "@b.com", "a@", "a@localhost", "a b@example.com", "a@b.c", "a@b.12", "a@b.com\nxyz"})
    void rejeitaEmailNaCriacaoENaAlteracao(String email) {
        Class<? extends RuntimeException> tipo = email == null ? NullPointerException.class : IllegalArgumentException.class;
        assertThrows(tipo, () -> usuario("João Silva", email, "67999991234", LocalDate.of(1990, 1, 1)));
        var u = valido();
        assertThrows(tipo, () -> u.setEmail(email));
        assertEquals("joao@example.com", u.getEmail());
    }

    @Test
    void rejeitaEmailAcimaDoLimiteAtual() {
        assertThrows(IllegalArgumentException.class, () -> valido().setEmail("a".repeat(39) + "@example.com"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "abc", "679999123", "679999912345", "6701234567", "67812345678", "+1 67 99999-1234", "(67)99999-1234", "(67) 9999-123", "67abcdefgh", "67999991234 ramal 1"})
    void rejeitaTelefoneNaCriacaoENaAlteracao(String telefone) {
        assertThrows(IllegalArgumentException.class, () -> usuario("João Silva", "joao@example.com", telefone, LocalDate.of(1990, 1, 1)));
        var u = valido();
        assertThrows(IllegalArgumentException.class, () -> u.setTelefone(telefone));
        assertEquals("67999991234", u.getTelefone());
    }

    @ParameterizedTest
    @ValueSource(strings = {"6733331234", "67999991234", "(67) 3333-1234", "(67) 99999-1234", "+55 67 3333-1234", "+55 67 99999-1234"})
    void aceitaENormalizaTelefone(String telefone) {
        var u = valido();
        u.setTelefone(telefone);
        assertEquals(telefone.contains("3333") ? "6733331234" : "67999991234", u.getTelefone());
    }

    @Test
    void validaNascimento() {
        var u = valido();
        assertThrows(IllegalArgumentException.class, () -> u.setDataNascimento(null));
        for (var data : new LocalDate[]{LocalDate.now().plusDays(1), LocalDate.now().minusYears(150).minusDays(1), LocalDate.MIN, LocalDate.MAX}) {
            assertThrows(IllegalArgumentException.class, () -> u.setDataNascimento(data));
            assertThrows(IllegalArgumentException.class, () -> usuario("João Silva", "a@b.com", "67999991234", data));
        }
        u.setDataNascimento(LocalDate.now());
        assertEquals(0, u.getIdade());
        u.setDataNascimento(LocalDate.now().minusYears(18));
        assertTrue(u.isMaiorIdade());
        u.setDataNascimento(LocalDate.now().minusYears(18).plusDays(1));
        assertFalse(u.isMaiorIdade());
    }

    @Test
    void validaCamposExclusivosDaReconstituicao() {
        var data = LocalDate.of(1990, 1, 1);
        var agora = Instant.now();
        var id = UUID.randomUUID();
        assertThrows(NullPointerException.class, () -> Usuario.reconstitute(null, "João Silva", "a@b.com", "67999991234", data, agora, StatusUsuario.ATIVO));
        assertThrows(NullPointerException.class, () -> Usuario.reconstitute(id, "João Silva", "a@b.com", "67999991234", data, null, StatusUsuario.ATIVO));
        assertThrows(NullPointerException.class, () -> Usuario.reconstitute(id, "João Silva", "a@b.com", "67999991234", data, agora, null));
        var u = Usuario.reconstitute(id, "João Silva", "a@b.com", "67999991234", data, agora, StatusUsuario.BLOQUEADO);
        assertEquals(id, u.getId());
        assertEquals(agora, u.getDataCadastro());
        assertEquals(StatusUsuario.BLOQUEADO, u.getStatus());
    }

    @Test
    void normalizaEntradasValidas() {
        var u = usuario("  João   Silva  ", " JOAO@EXAMPLE.COM ", " (67) 99999-1234 ", LocalDate.of(1990, 1, 1));
        assertEquals("João Silva", u.getNome());
        assertEquals("joao@example.com", u.getEmail());
        assertEquals("67999991234", u.getTelefone());
    }
}
