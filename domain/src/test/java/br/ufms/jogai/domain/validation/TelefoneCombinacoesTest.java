package br.ufms.jogai.domain.validation;

import br.ufms.jogai.domain.usuario.Usuario;
import br.ufms.jogai.domain.util.Validar;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TelefoneCombinacoesTest {
    // Lista de referência explícita, independente da expressão regular de produção.
    private static final Set<Integer> DDDS = Set.of(
            11,12,13,14,15,16,17,18,19,21,22,24,27,28,31,32,33,34,35,37,38,
            41,42,43,44,45,46,47,48,49,51,53,54,55,61,62,63,64,65,66,67,68,69,
            71,73,74,75,77,79,81,82,83,84,85,86,87,88,89,91,92,93,94,95,96,97,98,99);

    static Stream<String> telefonesComDddInvalido() {
        return IntStream.range(0,100).filter(ddd -> !DDDS.contains(ddd))
                .mapToObj(ddd -> String.format(java.util.Locale.ROOT, "%02d", ddd))
                .flatMap(ddd -> Stream.of(ddd + "999991234", "(" + ddd + ") 99999-1234", "+55 " + ddd + " 3333-1234"));
    }

    static Stream<String> telefonesMalformados() {
        Stream<String> formatos = Stream.of("999991234", "33331234", "5567999991234", "005567999991234",
                "+55(67)99999-1234", "+55 67 999991234", "(067) 99999-1234", "(67 99999-1234",
                "67) 99999-1234", "(67)  99999-1234", "(67) 99999.1234", "(67) 99999--1234",
                "(67) 9999-91234", "+55 067 99999-1234", "+54 67 99999-1234",
                "67999991234#123", "67999991234;123", "67999991234x123", "６７９９９９９１２３４", "٦٧٩٩٩٩٩١٢٣٤");
        Stream<String> comprimentos = IntStream.rangeClosed(1,20).filter(n -> n != 10 && n != 11)
                .mapToObj(n -> "9".repeat(n));
        Stream<String> prefixos = IntStream.rangeClosed(0,9)
                .filter(n -> n < 2 || n > 5).mapToObj(n -> "67" + n + "3331234");
        Stream<String> moveis = IntStream.range(0,9).mapToObj(n -> "67" + n + "99991234");
        Stream<String> controles = UsuarioEntradasInvalidasTest.controles().map(c -> "67999" + c + "991234");
        return Stream.of(formatos, comprimentos, prefixos, moveis, controles).flatMap(s -> s);
    }

    @ParameterizedTest
    @MethodSource({"telefonesComDddInvalido", "telefonesMalformados"})
    void rejeitaTelefoneEmTodosOsPontosDeEntrada(String telefone) {
        var usuario = UsuarioValidationTest.valido();
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> UsuarioValidationTest.usuario(usuario.getNome(), usuario.getEmail(), telefone, usuario.getDataNascimento())),
                () -> assertThrows(IllegalArgumentException.class, () -> Usuario.reconstitute(usuario.getId(), usuario.getNome(), usuario.getEmail(), telefone, usuario.getDataNascimento(), usuario.getDataCadastro(), usuario.getStatus())),
                () -> assertThrows(IllegalArgumentException.class, () -> usuario.setTelefone(telefone)),
                () -> assertThrows(IllegalArgumentException.class, () -> Validar.telefone(telefone, false)));
        assertEquals("67999991234", usuario.getTelefone());
    }

    static Stream<Integer> dddsValidos() { return DDDS.stream().sorted(); }

    @ParameterizedTest
    @MethodSource("dddsValidos")
    void aceitaCadaDddComTodosOsPrefixosEFormatosSuportados(int ddd) {
        for (String assinante : List.of("23331234", "33331234", "43331234", "53331234", "999991234")) {
            String nacional = ddd + assinante;
            String hifenizado = assinante.substring(0, assinante.length()-4) + "-" + assinante.substring(assinante.length()-4);
            for (String telefone : Arrays.asList(nacional, "(" + ddd + ") " + hifenizado, "+55 " + ddd + " " + hifenizado)) {
                var usuario = UsuarioValidationTest.valido();
                usuario.setTelefone(telefone);
                assertEquals(nacional, usuario.getTelefone(), telefone);
            }
        }
    }
}
