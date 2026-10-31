package br.ufms.jogai.domain.validation;

import br.ufms.jogai.domain.jogo.*;
import br.ufms.jogai.domain.usuario.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regressões das lacunas de integridade identificadas na auditoria inicial.
 * Os testes permanecem ativos para impedir a reintrodução dos problemas corrigidos.
 * Limites de tamanho novos e regras de negócio ainda indefinidas não são inventados aqui.
 */
@Tag("auditoria")
class LacunasValidacaoTest {

    @ParameterizedTest
    @ValueSource(strings = {"---", "-- --", "João -", "- Silva", "João -- Silva", "João- Silva"})
    void nomeDePessoaExigeLetrasEmCadaParte(String nome) {
        assertThrows(IllegalArgumentException.class, () -> UsuarioValidationTest.usuario(nome, "a@b.com", "67999991234", LocalDate.of(1990, 1, 1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {".joao@example.com", "joao.@example.com", "joao..silva@example.com", "joao@-example.com", "joao@example-.com", "joao@example..com", "joao@.example.com"})
    void emailRejeitaPontosEHifensEmPosicoesInvalidas(String email) {
        assertThrows(IllegalArgumentException.class, () -> UsuarioValidationTest.valido().setEmail(email));
    }

    @ParameterizedTest
    @ValueSource(strings = {"00999991234", "10999991234", "20999991234", "23999991234", "25999991234", "36999991234", "5212345678", "6712345678"})
    void telefoneRejeitaDddInexistenteOuPrefixoDeAssinanteInvalido(String telefone) {
        assertThrows(IllegalArgumentException.class, () -> UsuarioValidationTest.valido().setTelefone(telefone));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u2003", "\u00a0", "\u200b", "\u0000", "\u0001", "\u007f", "Jogo\nInjetado", "Jogo\tInjetado"})
    void nomesDoCatalogoRejeitamTextoInvisivelOuControles(String texto) {
        var jogo = JogoValidationTest.builder().build();
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> jogo.setNome(texto)),
                () -> assertThrows(IllegalArgumentException.class, () -> jogo.setEditora(texto)),
                () -> assertThrows(IllegalArgumentException.class, () -> JogoValidationTest.categoria().setNome(texto + texto)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", "\u2003", "\u0000"})
    void chaveDaCategoriaDeveSerUtilizavelComoIdentificador(String chave) {
        assertThrows(IllegalArgumentException.class, () -> CategoriaJogo.create(chave, "Estratégia", null));
    }

    @Test
    void jogoNaoAceitaCategoriaNulaNaConstrucao() {
        assertThrows(IllegalArgumentException.class, () -> JogoValidationTest.builder().categorias((CategoriaJogo) null).build());
    }

    @Test
    void adicaoEmLoteDeCategoriasNaoPodeDeixarAlteracaoParcial() {
        var jogo = JogoValidationTest.builder().build();
        var antes = Set.copyOf(jogo.getCategorias());
        assertThrows(IllegalArgumentException.class, () -> jogo.adicionarCategorias(CategoriaJogo.create("familia", "Família", null), null));
        assertEquals(antes, jogo.getCategorias(), "Uma operação rejeitada deve preservar as categorias anteriores");
    }

    @Test
    void nascimentoNoLimiteDe150AnosDeveSerAceito() {
        assertDoesNotThrow(() -> UsuarioValidationTest.valido().setDataNascimento(LocalDate.now().minusYears(150)));
    }

    @Test
    void nascimentoNuloDeveGerarErroDeValidacao() {
        assertThrows(IllegalArgumentException.class, () -> UsuarioValidationTest.valido().setDataNascimento(null));
    }

    @Test
    void cadastroDeUsuarioNaoPodeEstarNoFuturo() {
        var u = UsuarioValidationTest.valido();
        assertThrows(IllegalArgumentException.class, () -> Usuario.reconstitute(u.getId(), u.getNome(), u.getEmail(), u.getTelefone(), u.getDataNascimento(), Instant.now().plus(Duration.ofDays(1)), StatusUsuario.ATIVO));
    }

    @Test
    void cadastroDeExemplarNaoPodeEstarNoFuturo() {
        assertThrows(IllegalArgumentException.class, () -> Exemplar.reconstitute(UUID.randomUUID(), new JogoInfo(JogoValidationTest.builder().build()), new UsuarioInfo(UsuarioValidationTest.valido()), EstadoConservacao.NOVO, null, Instant.now().plus(Duration.ofDays(1))));
    }

    @Test
    void exemplarNaoPodeReferenciarJogoSemIdentidade() {
        assertThrows(IllegalArgumentException.class, () -> Exemplar.create(new JogoInfo(null, "Catan", "Devir", Set.of(JogoValidationTest.categoria())), new UsuarioInfo(UsuarioValidationTest.valido()), EstadoConservacao.NOVO, null));
    }

    @Test
    void exemplarNaoPodeReferenciarProprietarioSemIdentidade() {
        assertThrows(IllegalArgumentException.class, () -> Exemplar.create(new JogoInfo(JogoValidationTest.builder().build()), new UsuarioInfo(null, "João Silva", "a@b.com", "67999991234", LocalDate.of(1990, 1, 1)), EstadoConservacao.NOVO, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Anne-Marie D'Ávila", "李 小龍", "Jose\u0301 Silva"})
    void nomesReaisComApostrofoAlfabetosUnicodeEAcentosCombinadosSaoValidos(String nome) {
        assertDoesNotThrow(() -> UsuarioValidationTest.valido().setNome(nome));
    }

    @ParameterizedTest
    @ValueSource(strings = {"joao@example.technology", "joao@example.museum", "joao@example.solutions"})
    void emailAceitaDominiosComSufixoLongo(String email) {
        assertDoesNotThrow(() -> UsuarioValidationTest.valido().setEmail(email));
    }

    static java.util.stream.Stream<JogoInfo> jogosComDadosInvalidos() {
        var id = UUID.randomUUID();
        return java.util.stream.Stream.of(
                new JogoInfo(id, null, "Devir", Set.of(JogoValidationTest.categoria())),
                new JogoInfo(id, " ", "Devir", Set.of(JogoValidationTest.categoria())),
                new JogoInfo(id, "Catan", null, Set.of(JogoValidationTest.categoria())),
                new JogoInfo(id, "Catan", "Devir", null),
                new JogoInfo(id, "Catan", "Devir", Set.of()),
                new JogoInfo(id, "Catan", "Devir", new HashSet<>(Arrays.asList((CategoriaJogo) null))));
    }

    @ParameterizedTest
    @MethodSource("jogosComDadosInvalidos")
    void exemplarRejeitaDadosInvalidosDoJogo(JogoInfo jogo) {
        assertThrows(IllegalArgumentException.class, () -> Exemplar.create(jogo, new UsuarioInfo(UsuarioValidationTest.valido()), EstadoConservacao.NOVO, null));
    }

    static java.util.stream.Stream<UsuarioInfo> proprietariosComDadosInvalidos() {
        var id = UUID.randomUUID();
        var nascimento = LocalDate.of(1990, 1, 1);
        return java.util.stream.Stream.of(
                new UsuarioInfo(id, null, "a@b.com", "67999991234", nascimento),
                new UsuarioInfo(id, " ", "a@b.com", "67999991234", nascimento),
                new UsuarioInfo(id, "João Silva", null, "67999991234", nascimento),
                new UsuarioInfo(id, "João Silva", "invalido", "67999991234", nascimento),
                new UsuarioInfo(id, "João Silva", "a@b.com", null, nascimento),
                new UsuarioInfo(id, "João Silva", "a@b.com", "abc", nascimento),
                new UsuarioInfo(id, "João Silva", "a@b.com", "67999991234", null),
                new UsuarioInfo(id, "João Silva", "a@b.com", "67999991234", LocalDate.now().plusDays(1)));
    }

    @ParameterizedTest
    @MethodSource("proprietariosComDadosInvalidos")
    void exemplarRejeitaDadosInvalidosDoProprietario(UsuarioInfo proprietario) {
        assertThrows(IllegalArgumentException.class, () -> Exemplar.create(new JogoInfo(JogoValidationTest.builder().build()), proprietario, EstadoConservacao.NOVO, null));
    }

}
