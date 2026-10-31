package br.ufms.jogai.domain.validation;

import br.ufms.jogai.domain.usuario.Usuario;
import br.ufms.jogai.domain.util.Validar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class UsuarioEntradasInvalidasTest {
    static Stream<String> emailsInvalidos() {
        return Stream.concat(Stream.of(
                "a@b@c.com", "a＠example.com", "a@example", "a@example.", "a@example.c",
                "a@example.c0m", "a@example.123", "a@.com", "a@..com", "a@-b.com", "a@b-.com",
                "a@b._com", "a@b_com.br", "a@b/com.br", "a@b:25.com", "a@[127.0.0.1]",
                ".a@b.com", "a.@b.com", "a...b@c.com", "a@b...com", "a b@c.com",
                "a<b>@c.com", "a,b@c.com", "a;b@c.com", "a:b@c.com", "a\\b@c.com",
                "a(b)@c.com", "a[b]@c.com", "a\"b@c.com", "a@exámple.com", "a@例子.com",
                "a@b.сom", "a😀@b.com", "a@b.😀", "a".repeat(39) + "@example.com",
                "a".repeat(10_000) + "@example.com"),
                controles().flatMap(c -> Stream.of("jo" + c + "ao@example.com", "a@exam" + c + "ple.com")));
    }

    @ParameterizedTest
    @MethodSource("emailsInvalidos")
    void rejeitaEmailEmTodosOsPontosDeEntrada(String email) {
        var usuario = UsuarioValidationTest.valido();
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> UsuarioValidationTest.usuario("João Silva", email, usuario.getTelefone(), usuario.getDataNascimento())),
                () -> assertThrows(IllegalArgumentException.class, () -> Usuario.reconstitute(usuario.getId(), usuario.getNome(), email, usuario.getTelefone(), usuario.getDataNascimento(), usuario.getDataCadastro(), usuario.getStatus())),
                () -> assertThrows(IllegalArgumentException.class, () -> usuario.setEmail(email)),
                () -> assertThrows(IllegalArgumentException.class, () -> Validar.email(email, false)));
        assertEquals("joao@example.com", usuario.getEmail());
    }

    static Stream<String> nomesInvalidos() {
        return Stream.concat(Stream.of(
                "A", "AB", "123 456", "João Silva1", "1João Silva", "João Sílva²", "João ١Silva",
                "João _Silva", "João /Silva", "João \\Silva", "João <Silva>", "João Silva!",
                "João Silva?", "João Silva;", "João Silva:", "João Silva=", "João Silva+",
                "João Silva©", "João 😀Silva", "João • Silva", "-João Silva", "João Silva-",
                "'João Silva", "João Silva'", "João --Silva", "João Silva--Neto",
                "João D''Ávila", "João D’ Ávila", "João D' Ávila", "\u0301João Silva",
                "João \u0301Silva", "A".repeat(49) + " B", "A".repeat(10_000) + " B"),
                controles().map(c -> "João" + c + " Silva"));
    }

    @ParameterizedTest
    @MethodSource("nomesInvalidos")
    void rejeitaNomeEmTodosOsPontosDeEntrada(String nome) {
        var usuario = UsuarioValidationTest.valido();
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> UsuarioValidationTest.usuario(nome, usuario.getEmail(), usuario.getTelefone(), usuario.getDataNascimento())),
                () -> assertThrows(IllegalArgumentException.class, () -> Usuario.reconstitute(usuario.getId(), nome, usuario.getEmail(), usuario.getTelefone(), usuario.getDataNascimento(), usuario.getDataCadastro(), usuario.getStatus())),
                () -> assertThrows(IllegalArgumentException.class, () -> usuario.setNome(nome)));
        assertEquals("João Silva", usuario.getNome());
    }

    // Injeção no interior evita confundir normalização de bordas com conteúdo inválido.
    static Stream<String> controles() {
        return Stream.concat(IntStream.rangeClosed(0, 31).mapToObj(c -> Character.toString((char)c)),
                Stream.of("\u007f", "\u0085", "\u00a0", "\u200b", "\u200e", "\u2028", "\u2029", "\u202e", "\u2066", "\ufeff"));
    }

    static Stream<String> emailsValidos() {
        return Stream.of("a@b.co", "a.b+tag@sub.example.com", "a_b-c%d@example.com",
                "a@xn--exmple-cua.com", "a@sub-domain.example.museum", "JOAO@EXAMPLE.COM");
    }

    @ParameterizedTest
    @MethodSource("emailsValidos")
    void aceitaEmailsValidosSemPerderNormalizacao(String email) {
        var usuario = UsuarioValidationTest.valido();
        usuario.setEmail(" " + email + " ");
        assertEquals(email.toLowerCase(Locale.ROOT), usuario.getEmail());
        var restaurado = Usuario.reconstitute(usuario.getId(), usuario.getNome(), email, usuario.getTelefone(), usuario.getDataNascimento(), usuario.getDataCadastro(), usuario.getStatus());
        assertEquals(usuario.getEmail(), restaurado.getEmail());
    }

    static Stream<String> nomesValidos() {
        return Stream.of("Ana Maria", "João D'Ávila", "Anne-Marie Silva", "João D’Ávila", "李 小龍",
                "Мария Иванова", "Αννα Παπα", "José da Silva Neto", "Jose\u0301 Silva");
    }

    @ParameterizedTest
    @MethodSource("nomesValidos")
    void aceitaNomesValidosNaCriacaoEReconstituicao(String nome) {
        var usuario = UsuarioValidationTest.usuario(nome, "a@b.com", "67999991234", LocalDate.of(2000, 2, 29));
        var restaurado = Usuario.reconstitute(usuario.getId(), nome, usuario.getEmail(), usuario.getTelefone(), usuario.getDataNascimento(), Instant.now(), usuario.getStatus());
        assertEquals(usuario.getNome(), restaurado.getNome());
    }

    @Test
    void normalizacaoDeEmailNaoDependeDoLocaleDoSistema() {
        // Exercita o contrato explícito sem alterar o Locale global de outras suítes.
        assertEquals("i@example.com", Usuario.normalizarEmail("I@EXAMPLE.COM"));
    }
}
