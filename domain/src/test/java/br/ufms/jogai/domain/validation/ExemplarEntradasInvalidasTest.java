package br.ufms.jogai.domain.validation;

import br.ufms.jogai.domain.jogo.*;
import br.ufms.jogai.domain.usuario.UsuarioInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ExemplarEntradasInvalidasTest {
    static Stream<Arguments> proprietariosInvalidos() {
        var usuario = UsuarioValidationTest.valido();
        var id = usuario.getId();
        Stream<Arguments> nomes = UsuarioEntradasInvalidasTest.nomesInvalidos()
                .map(nome -> Arguments.of("nome", new UsuarioInfo(id,nome,usuario.getEmail(),usuario.getTelefone(),usuario.getDataNascimento())));
        Stream<Arguments> emails = UsuarioEntradasInvalidasTest.emailsInvalidos()
                .map(email -> Arguments.of("email", new UsuarioInfo(id,usuario.getNome(),email,usuario.getTelefone(),usuario.getDataNascimento())));
        Stream<Arguments> telefones = Stream.concat(TelefoneCombinacoesTest.telefonesComDddInvalido(), TelefoneCombinacoesTest.telefonesMalformados())
                .map(telefone -> Arguments.of("telefone", new UsuarioInfo(id,usuario.getNome(),usuario.getEmail(),telefone,usuario.getDataNascimento())));
        Stream<Arguments> datas = DatasECatalogoFronteirasTest.nascimentosInvalidos()
                .map(data -> Arguments.of("nascimento", new UsuarioInfo(id,usuario.getNome(),usuario.getEmail(),usuario.getTelefone(),data)));
        return Stream.of(nomes,emails,telefones,datas).flatMap(s -> s);
    }

    @ParameterizedTest(name = "[{index}] proprietário com {0} inválido")
    @MethodSource("proprietariosInvalidos")
    void criacaoEReconstituicaoValidamProprietario(String campo, UsuarioInfo proprietario) {
        var jogo = new JogoInfo(JogoValidationTest.builder().build());
        assertAll(campo,
                () -> assertThrows(IllegalArgumentException.class, () -> Exemplar.create(jogo,proprietario,EstadoConservacao.NOVO,null)),
                () -> assertThrows(IllegalArgumentException.class, () -> Exemplar.reconstitute(UUID.randomUUID(),jogo,proprietario,EstadoConservacao.NOVO,null,Instant.now())));
    }

    static Stream<Arguments> jogosInvalidos() {
        return DatasECatalogoFronteirasTest.nomesDeCatalogoInvalidos().flatMap(nome -> Stream.of(
                Arguments.of("nome",new JogoInfo(UUID.randomUUID(),nome,"Devir",java.util.Set.of(JogoValidationTest.categoria()))),
                Arguments.of("editora",new JogoInfo(UUID.randomUUID(),"Catan",nome,java.util.Set.of(JogoValidationTest.categoria())))));
    }

    @ParameterizedTest(name = "[{index}] jogo com {0} inválido")
    @MethodSource("jogosInvalidos")
    void criacaoEReconstituicaoValidamJogo(String campo, JogoInfo jogo) {
        var proprietario = new UsuarioInfo(UsuarioValidationTest.valido());
        assertAll(campo,
                () -> assertThrows(IllegalArgumentException.class, () -> Exemplar.create(jogo,proprietario,EstadoConservacao.NOVO,null)),
                () -> assertThrows(IllegalArgumentException.class, () -> Exemplar.reconstitute(UUID.randomUUID(),jogo,proprietario,EstadoConservacao.NOVO,null,Instant.now())));
    }
}
