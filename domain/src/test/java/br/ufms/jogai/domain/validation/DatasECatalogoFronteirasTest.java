package br.ufms.jogai.domain.validation;

import br.ufms.jogai.domain.jogo.*;
import br.ufms.jogai.domain.usuario.Usuario;
import br.ufms.jogai.domain.usuario.UsuarioInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.*;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class DatasECatalogoFronteirasTest {
    static Stream<LocalDate> nascimentosInvalidos() {
        var hoje = LocalDate.now();
        return Stream.of(null, hoje.plusDays(1), hoje.plusYears(1), hoje.minusYears(150).minusDays(1),
                hoje.minusYears(151), LocalDate.MIN, LocalDate.MAX, LocalDate.of(1,1,1));
    }

    @ParameterizedTest
    @MethodSource("nascimentosInvalidos")
    void rejeitaNascimentoSemAlterarUsuario(LocalDate data) {
        var usuario = UsuarioValidationTest.valido();
        var anterior = usuario.getDataNascimento();
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> UsuarioValidationTest.usuario(usuario.getNome(), usuario.getEmail(), usuario.getTelefone(), data)),
                () -> assertThrows(IllegalArgumentException.class, () -> Usuario.reconstitute(usuario.getId(), usuario.getNome(), usuario.getEmail(), usuario.getTelefone(), data, usuario.getDataCadastro(), usuario.getStatus())),
                () -> assertThrows(IllegalArgumentException.class, () -> usuario.setDataNascimento(data)));
        assertEquals(anterior, usuario.getDataNascimento());
    }

    static Stream<LocalDate> nascimentosValidos() {
        var hoje = LocalDate.now();
        return Stream.of(hoje, hoje.minusDays(1), hoje.minusYears(150), hoje.minusYears(150).plusDays(1),
                LocalDate.of(2000,2,29), LocalDate.of(2024,2,29), LocalDate.of(1900,2,28));
    }

    @ParameterizedTest
    @MethodSource("nascimentosValidos")
    void aceitaDatasExistentesInclusivamenteNosLimites(LocalDate data) {
        var usuario = UsuarioValidationTest.valido();
        usuario.setDataNascimento(data);
        assertEquals(data, usuario.getDataNascimento());
    }

    static Stream<Instant> cadastrosFuturos() {
        var agora = Instant.now();
        return Stream.of(agora.plusSeconds(60), agora.plus(Duration.ofDays(1)), agora.plus(Duration.ofDays(365)), Instant.MAX);
    }

    @ParameterizedTest
    @MethodSource("cadastrosFuturos")
    void reconstituicaoDeUsuarioEExemplarRejeitaCadastroFuturo(Instant data) {
        var usuario = UsuarioValidationTest.valido();
        assertThrows(IllegalArgumentException.class, () -> Usuario.reconstitute(usuario.getId(), usuario.getNome(), usuario.getEmail(), usuario.getTelefone(), usuario.getDataNascimento(), data, usuario.getStatus()));
        assertThrows(IllegalArgumentException.class, () -> Exemplar.reconstitute(UUID.randomUUID(), new JogoInfo(JogoValidationTest.builder().build()), new UsuarioInfo(usuario), EstadoConservacao.NOVO, null, data));
    }

    static Stream<String> nomesDeCatalogoInvalidos() {
        return Stream.concat(
                UsuarioEntradasInvalidasTest.controles()
                        .filter(c -> c.codePoints().anyMatch(cp -> Character.isISOControl(cp)
                                || Character.getType(cp) == Character.FORMAT
                                || Character.getType(cp) == Character.LINE_SEPARATOR
                                || Character.getType(cp) == Character.PARAGRAPH_SEPARATOR))
                        .flatMap(c -> Stream.of(c + "Nome", "No" + c + "me", "Nome" + c)),
                Stream.of("\u2000", "\u2001", "\u2002", "\u2007", "\u202f", "\u3000"));
    }

    @ParameterizedTest
    @MethodSource("nomesDeCatalogoInvalidos")
    void rejeitaControlesNasBordasENoInteriorDoCatalogo(String nome) {
        var jogo = JogoValidationTest.builder().build();
        var categoria = JogoValidationTest.categoria();
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> jogo.setNome(nome)),
                () -> assertThrows(IllegalArgumentException.class, () -> jogo.setEditora(nome)),
                () -> assertThrows(IllegalArgumentException.class, () -> categoria.setNome(nome)),
                () -> assertThrows(IllegalArgumentException.class, () -> CategoriaJogo.create(nome,"Estratégia",null)),
                () -> assertThrows(IllegalArgumentException.class, () -> JogoValidationTest.builder().nomeEditora(nome,"Devir").build()),
                () -> assertThrows(IllegalArgumentException.class, () -> JogoValidationTest.builder().nomeEditora("Catan",nome).build()),
                () -> assertThrows(IllegalArgumentException.class, () -> CategoriaJogo.reconstitute("estrategia",nome,null)));
        assertEquals("Catan", jogo.getNome());
        assertEquals("Devir", jogo.getEditora());
        assertEquals("Estratégia", categoria.getNome());
    }

    @Test
    void intervalosRejeitamExtremosENaoApenasMenosUm() {
        for (int negativo : new int[]{Integer.MIN_VALUE, -1000, -2, -1}) {
            assertThrows(IllegalArgumentException.class, () -> new NumeroJogadores(negativo,null));
            assertThrows(IllegalArgumentException.class, () -> new NumeroJogadores(1,negativo));
            assertThrows(IllegalArgumentException.class, () -> new FaixaEtaria(negativo,null));
            assertThrows(IllegalArgumentException.class, () -> new FaixaEtaria(null,negativo));
        }
        for (Duration negativa : new Duration[]{Duration.ofNanos(-1), Duration.ofSeconds(-1), Duration.ofSeconds(Long.MIN_VALUE)}) {
            assertThrows(IllegalArgumentException.class, () -> new DuracaoPartida(negativa,null));
            assertThrows(IllegalArgumentException.class, () -> new DuracaoPartida(null,negativa));
        }
        assertThrows(IllegalArgumentException.class, () -> new DuracaoPartida(Duration.ofSeconds(1),Duration.ofSeconds(1).minusNanos(1)));
        assertThrows(IllegalArgumentException.class, () -> new NumeroJogadores(Integer.MAX_VALUE,Integer.MAX_VALUE-1));
        assertThrows(IllegalArgumentException.class, () -> new FaixaEtaria(Integer.MAX_VALUE,Integer.MAX_VALUE-1));
    }

    @Test
    void anoInvalidoNaoSubstituiAnoAnterior() {
        var jogo = JogoValidationTest.builder().build();
        jogo.setAnoLancamento(Year.of(2000));
        for (int ano : new int[]{Year.MIN_VALUE,-1,0,Year.now().getValue()+1,Year.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> jogo.setAnoLancamento(Year.of(ano)));
            assertEquals(Year.of(2000),jogo.getAnoLancamento());
            assertThrows(IllegalArgumentException.class, () -> JogoValidationTest.builder().nomeEditoraLancamento("Catan","Devir",ano).build());
        }
    }
}
