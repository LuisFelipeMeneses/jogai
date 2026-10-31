package br.ufms.jogai.data.repository;

import br.ufms.jogai.domain.usuario.Usuario;
import br.ufms.jogai.domain.usuario.UsuarioDuplicadoException;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Executa apenas no emulador, com um projeto isolado por teste e sem credenciais reais. */
@EnabledIfEnvironmentVariable(named = "FIRESTORE_EMULATOR_HOST", matches = ".+")
class UsuarioFirestoreRepositoryTest {

    private Firestore db;
    private UsuarioFirestoreRepository repository;

    @BeforeEach
    void configurar() {
        db = FirestoreOptions.newBuilder()
                .setProjectId("demo-jogai-" + UUID.randomUUID().toString().substring(0, 8))
                .setEmulatorHost(System.getenv("FIRESTORE_EMULATOR_HOST"))
                .build().getService();
        repository = new UsuarioFirestoreRepository(db);
    }

    @AfterEach
    void fechar() throws Exception {
        if (db != null) db.close();
    }

    @Test
    void rejeitaEmailDuplicadoSemDiferenciarMaiusculas() throws Exception {
        aguardar(repository.cadastrar(usuario("Pessoa@Example.com", "67996122809")));
        duplicado("email", repository.cadastrar(usuario("pessoa@example.com", "67996122810")));
        assertEquals(1, aguardar(repository.getAll()).size());
        assertEquals(1, quantidadeReservas("telefone"));
    }

    @Test
    void rejeitaTelefoneDuplicadoEmOutroFormato() throws Exception {
        aguardar(repository.cadastrar(usuario("a@example.com", "67996122809")));
        duplicado("telefone", repository.cadastrar(usuario("b@example.com", "+55 67 99612-2809")));
        assertEquals(1, quantidadeReservas("email"));
    }

    @Test
    void saveTambemImpedeDuplicidade() throws Exception {
        aguardar(repository.save(usuario("a@example.com", "67996122809")));
        duplicado("email", repository.save(usuario("a@example.com", "67996122810")));
    }

    @Test
    void cadastrarNaoSobrescreveIdExistente() throws Exception {
        Usuario usuario = usuario("a@example.com", "67996122809");
        aguardar(repository.cadastrar(usuario));
        duplicado("id", repository.cadastrar(usuario));
        aguardar(repository.save(usuario));
    }

    @Test
    void atualizacaoLiberaReservasAnteriores() throws Exception {
        Usuario usuario = usuario("a@example.com", "67996122809");
        aguardar(repository.cadastrar(usuario));
        usuario.setEmail("b@example.com");
        usuario.setTelefone("67996122810");
        aguardar(repository.save(usuario));
        aguardar(repository.cadastrar(usuario("a@example.com", "67996122809")));
        assertEquals(2, aguardar(repository.getAll()).size());
        duplicado("email", repository.cadastrar(usuario("b@example.com", "67996122811")));
    }

    @Test
    void atualizacaoComConflitoPreservaUsuarioEReservas() throws Exception {
        Usuario primeiro = usuario("a@example.com", "67996122809");
        Usuario segundo = usuario("b@example.com", "67996122810");
        aguardar(repository.cadastrar(primeiro));
        aguardar(repository.cadastrar(segundo));
        segundo.setEmail(primeiro.getEmail());
        segundo.setTelefone("67996122811");
        duplicado("email", repository.save(segundo));
        assertEquals("b@example.com", aguardar(repository.get(segundo.getId())).orElseThrow().getEmail());
        duplicado("telefone", repository.cadastrar(usuario("c@example.com", "67996122810")));
        aguardar(repository.cadastrar(usuario("c@example.com", "67996122811")));
    }

    @Test
    void exclusaoLiberaReservas() throws Exception {
        Usuario usuario = usuario("a@example.com", "67996122809");
        aguardar(repository.cadastrar(usuario));
        aguardar(repository.delete(usuario.getId()));
        aguardar(repository.delete(usuario.getId()));
        assertEquals(0, quantidadeReservas("email"));
        assertEquals(0, quantidadeReservas("telefone"));
        aguardar(repository.cadastrar(usuario("a@example.com", "67996122809")));
    }

    @Test
    void protegeUsuariosAntigosSemReservas() throws Exception {
        Usuario antigo = usuario("a@example.com", "67996122809");
        var dados = new java.util.HashMap<>(repository.entityToMap(antigo));
        dados.put("email", "A@Example.com");
        dados.put("telefone", "(67) 99612-2809");
        db.collection("usuarios").document(antigo.getId().toString()).set(dados).get();
        duplicado("email", repository.cadastrar(usuario("a@example.com", "67996122810")));
        duplicado("telefone", repository.cadastrar(usuario("b@example.com", "67996122809")));
        aguardar(repository.save(antigo));
        assertEquals(1, quantidadeReservas("email"));
    }

    @Test
    void atualizaDocumentoAntigoUsandoNormalizacaoDoDominioParaLiberarReservas() throws Exception {
        Usuario antigo = gravarUsuarioAntigo();
        // Inicializa reservas sem regravar o documento legado.
        aguardar(repository.cadastrar(usuario("b@example.com", "67996122810")));
        antigo.setEmail("novo@example.com");
        antigo.setTelefone("67996122811");
        aguardar(repository.save(antigo));
        assertReservasConsistentes();
        aguardar(repository.cadastrar(usuario("a@example.com", "67996122809")));
        assertReservasConsistentes();
    }

    @Test
    void excluiDocumentoAntigoLiberandoAsReservasCanonicas() throws Exception {
        Usuario antigo = gravarUsuarioAntigo();
        aguardar(repository.cadastrar(usuario("b@example.com", "67996122810")));
        aguardar(repository.delete(antigo.getId()));
        assertReservasConsistentes();
        aguardar(repository.cadastrar(usuario("a@example.com", "67996122809")));
        assertReservasConsistentes();
    }

    @Test
    void dominioRejeitaDocumentoAntigoInvalidoSemEscritasParciais() throws Exception {
        Usuario antigo = usuario("a@example.com", "67996122809");
        var dados = new java.util.HashMap<>(repository.entityToMap(antigo));
        dados.put("nome", "!!!");
        db.collection("usuarios").document(antigo.getId().toString()).set(dados).get();
        Exception erro = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> aguardar(repository.cadastrar(usuario("b@example.com", "67996122810"))));
        assertInstanceOf(IllegalArgumentException.class, causa(erro));
        assertEquals(1, db.collection("usuarios").get().get().size());
        assertEquals(0, db.collection("valores_unicos").get().get().size());
        assertEquals(0, db.collection("valores_unicos_controle").get().get().size());
    }

    @Test
    void consultasUsamAsMesmasRegrasDoDominioParaEntradasNaoNormalizadas() throws Exception {
        Usuario a = usuario("a@example.com", "67996122809");
        aguardar(repository.cadastrar(a));
        assertEquals(a.getId(), aguardar(repository.buscarPorEmail(" A@Example.com ")).orElseThrow().getId());
        assertEquals(a.getId(), aguardar(repository.buscarPorTelefone("(67) 99612-2809")).orElseThrow().getId());
    }

    private Usuario gravarUsuarioAntigo() throws Exception {
        Usuario antigo = usuario("a@example.com", "67996122809");
        var dados = new java.util.HashMap<>(repository.entityToMap(antigo));
        dados.put("email", "A@Example.com");
        dados.put("telefone", "(67) 99612-2809");
        db.collection("usuarios").document(antigo.getId().toString()).set(dados).get();
        return antigo;
    }

    @Test
    void primeiraAtualizacaoMigraELiberaReservasAntigas() throws Exception {
        Usuario antigo = usuario("a@example.com", "67996122809");
        db.collection("usuarios").document(antigo.getId().toString()).set(repository.entityToMap(antigo)).get();
        antigo.setEmail("b@example.com");
        antigo.setTelefone("67996122810");
        aguardar(repository.save(antigo));
        assertEquals(1, quantidadeReservas("email"));
        assertEquals(1, quantidadeReservas("telefone"));
        aguardar(repository.cadastrar(usuario("a@example.com", "67996122809")));
    }

    @Test
    void migracaoRejeitaDuplicidadePreexistenteSemEscritasParciais() throws Exception {
        Usuario a = usuario("a@example.com", "67996122809");
        Usuario b = usuario("a@example.com", "67996122810");
        db.collection("usuarios").document(a.getId().toString()).set(repository.entityToMap(a)).get();
        db.collection("usuarios").document(b.getId().toString()).set(repository.entityToMap(b)).get();
        duplicado("email", repository.cadastrar(usuario("c@example.com", "67996122811")));
        assertEquals(2, aguardar(repository.getAll()).size());
        assertEquals(0, quantidadeReservas("email"));
        assertEquals(0, db.collection("usuarios_indices").get().get().size());
        assertEquals(0, db.collection("valores_unicos_controle").get().get().size());
    }

    @Test
    void concorrenciaTambemEhProtegidaAposInicializacao() throws Exception {
        Usuario inicial = usuario("inicial@example.com", "67996122811");
        aguardar(repository.cadastrar(inicial));
        aguardar(repository.delete(inicial.getId()));
        verificarConcorrencia("email",
                usuario("a@example.com", "67996122809"),
                usuario("a@example.com", "67996122810"));
    }

    @Test
    void cadastrosSimultaneosComMesmoEmailTemApenasUmVencedor() throws Exception {
        verificarConcorrencia("email",
                usuario("a@example.com", "67996122809"),
                usuario("A@example.com", "67996122810"));
    }

    @Test
    void cadastrosSimultaneosComMesmoTelefoneTemApenasUmVencedor() throws Exception {
        verificarConcorrencia("telefone",
                usuario("a@example.com", "67996122809"),
                usuario("b@example.com", "(67) 99612-2809"));
    }

    @Test
    void atualizacaoComTelefoneOcupadoNaoReservaNovoEmailNemLiberaValoresAntigos() throws Exception {
        Usuario a = usuario("a@example.com", "67996122809");
        Usuario b = usuario("b@example.com", "67996122810");
        aguardar(repository.cadastrar(a));
        aguardar(repository.cadastrar(b));
        b.setEmail("novo@example.com");
        b.setTelefone(a.getTelefone());
        duplicado("telefone", repository.save(b));
        Usuario persistido = aguardar(repository.get(b.getId())).orElseThrow();
        assertEquals("b@example.com", persistido.getEmail());
        assertEquals("67996122810", persistido.getTelefone());
        assertReservasConsistentes();
        aguardar(repository.cadastrar(usuario("novo@example.com", "67996122811")));
        duplicado("email", repository.cadastrar(usuario("b@example.com", "67996122812")));
        duplicado("telefone", repository.cadastrar(usuario("livre@example.com", "67996122810")));
    }

    @Test
    void permiteAlterarSomenteEmailOuSomenteTelefoneEManterOsPropriosValores() throws Exception {
        Usuario a = usuario("a@example.com", "67996122809");
        aguardar(repository.cadastrar(a));
        a.setEmail("novo@example.com");
        aguardar(repository.save(a));
        assertReservasConsistentes();
        a.setTelefone("67996122810");
        aguardar(repository.save(a));
        a.setNome("Outro Nome");
        a.setEmail(" NOVO@EXAMPLE.COM ");
        a.setTelefone("(67) 99612-2810");
        aguardar(repository.save(a));
        assertEquals("Outro Nome", aguardar(repository.get(a.getId())).orElseThrow().getNome());
        assertReservasConsistentes();
        aguardar(repository.cadastrar(usuario("a@example.com", "67996122809")));
    }

    @Test
    void duasAtualizacoesDisputandoEmailTemApenasUmVencedor() throws Exception {
        verificarAtualizacoesConcorrentes("email");
    }

    @Test
    void duasAtualizacoesDisputandoTelefoneTemApenasUmVencedor() throws Exception {
        verificarAtualizacoesConcorrentes("telefone");
    }

    private void verificarAtualizacoesConcorrentes(String campo) throws Exception {
        Usuario a = usuario("a@example.com", "67996122809");
        Usuario b = usuario("b@example.com", "67996122810");
        aguardar(repository.cadastrar(a));
        aguardar(repository.cadastrar(b));
        if (campo.equals("email")) {
            a.setEmail("disputa@example.com");
            b.setEmail("disputa@example.com");
        } else {
            a.setTelefone("67996122811");
            b.setTelefone("67996122811");
        }
        var resultadoA = repository.save(a).handle((usuario, erro) -> erro);
        var resultadoB = repository.save(b).handle((usuario, erro) -> erro);
        apenasUmVencedor(campo, aguardar(resultadoA), aguardar(resultadoB));
        assertEquals(2, aguardar(repository.getAll()).size());
        assertReservasConsistentes();
    }

    @Test
    void cadastroEAtualizacaoDisputandoEmailTemApenasUmVencedor() throws Exception {
        Usuario existente = usuario("a@example.com", "67996122809");
        aguardar(repository.cadastrar(existente));
        existente.setEmail("disputa@example.com");
        var alteracao = repository.save(existente).handle((usuario, erro) -> erro);
        var cadastro = repository.cadastrar(usuario("disputa@example.com", "67996122810"))
                .handle((usuario, erro) -> erro);
        apenasUmVencedor("email", aguardar(alteracao), aguardar(cadastro));
        assertReservasConsistentes();
    }

    @Test
    void atualizacoesConcorrentesDoMesmoUsuarioNaoDeixamReservasOrfas() throws Exception {
        Usuario a = usuario("a@example.com", "67996122809");
        aguardar(repository.cadastrar(a));
        Usuario b = aguardar(repository.get(a.getId())).orElseThrow();
        a.setEmail("primeiro@example.com");
        a.setTelefone("67996122810");
        b.setEmail("segundo@example.com");
        b.setTelefone("67996122811");
        var primeira = repository.save(a);
        var segunda = repository.save(b);
        aguardar(primeira);
        aguardar(segunda);
        assertEquals(1, aguardar(repository.getAll()).size());
        assertReservasConsistentes();
    }

    @Test
    void migraMesmoQuandoMarcadorDaVersaoAnteriorExiste() throws Exception {
        Usuario antigo = usuario("a@example.com", "67996122809");
        db.collection("usuarios").document(antigo.getId().toString()).set(repository.entityToMap(antigo)).get();
        db.collection("usuarios_indices").document("unicidade_v1").set(java.util.Map.of("versao", 1)).get();
        aguardar(repository.cadastrar(usuario("b@example.com", "67996122810")));
        assertEquals(1, db.collection("valores_unicos_controle").get().get().size());
        assertReservasConsistentes();
        duplicado("email", repository.cadastrar(usuario("a@example.com", "67996122811")));
        duplicado("telefone", repository.cadastrar(usuario("c@example.com", "67996122809")));
    }

    @Test
    void falhaAposAgendarEscritasDesfazUsuarioEReservas() throws Exception {
        Usuario a = usuario("a@example.com", "67996122809");
        aguardar(repository.cadastrar(a));
        var valores = new UniqueValuesFirestoreRepository(db);
        var falha = db.runTransaction(transaction -> {
            var reservas = valores.inTransaction(transaction);
            reservas.reserve("usuario", "email", "novo@example.com", a.getId().toString());
            reservas.release("usuario", "email", a.getEmail(), a.getId().toString());
            reservas.apply();
            transaction.update(db.collection("usuarios").document(a.getId().toString()), "email", "novo@example.com");
            throw new IllegalStateException("Falha simulada antes do commit");
        });
        Exception erro = assertThrows(Exception.class, () -> falha.get(45, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, causa(erro));
        assertEquals(a.getEmail(), aguardar(repository.get(a.getId())).orElseThrow().getEmail());
        assertReservasConsistentes();
        aguardar(repository.cadastrar(usuario("novo@example.com", "67996122810")));
    }

    @Test
    void falhaNoCommitNaoAplicaNenhumaDasEscritas() throws Exception {
        Usuario a = usuario("a@example.com", "67996122809");
        aguardar(repository.cadastrar(a));
        var valores = new UniqueValuesFirestoreRepository(db);
        var falha = db.runTransaction(transaction -> {
            var reservas = valores.inTransaction(transaction);
            reservas.reserve("usuario", "email", "novo@example.com", a.getId().toString());
            reservas.release("usuario", "email", a.getEmail(), a.getId().toString());
            reservas.apply();
            transaction.update(db.collection("usuarios").document(a.getId().toString()), "email", "novo@example.com");
            // update exige um documento existente: o servidor deve rejeitar o commit inteiro.
            transaction.update(db.collection("falhas_de_teste").document("inexistente"), "valor", 1);
            return null;
        });
        assertThrows(java.util.concurrent.ExecutionException.class, () -> falha.get(45, TimeUnit.SECONDS));
        assertEquals(a.getEmail(), aguardar(repository.get(a.getId())).orElseThrow().getEmail());
        assertReservasConsistentes();
        aguardar(repository.cadastrar(usuario("novo@example.com", "67996122810")));
    }

    @Test
    void alterarObjetoAposIniciarCadastroNaoMudaOsDadosDaOperacao() throws Exception {
        Usuario a = usuario("a@example.com", "67996122809");
        var cadastro = repository.cadastrar(a);
        a.setEmail("outro@example.com");
        a.setTelefone("67996122810");
        Usuario resultado = aguardar(cadastro);
        assertEquals(a.getDataCadastro(), resultado.getDataCadastro());
        assertEquals("a@example.com", resultado.getEmail());
        assertEquals("67996122809", resultado.getTelefone());
        assertReservasConsistentes();
    }

    private int quantidadeReservas(String campo) throws Exception {
        return db.collection("valores_unicos").whereEqualTo("entidade", "usuario")
                .whereEqualTo("campo", campo).get().get().size();
    }

    private void assertReservasConsistentes() throws Exception {
        var usuarios = aguardar(repository.getAll());
        var reservas = db.collection("valores_unicos").get().get().getDocuments();
        assertEquals(usuarios.size() * 2, reservas.size(), "Não pode haver reservas órfãs ou ausentes");
        for (var usuario : usuarios) {
            for (var campo : java.util.Map.of("email", usuario.getEmail(), "telefone", usuario.getTelefone()).entrySet()) {
                String chave = codificar("usuario") + "." + codificar(campo.getKey()) + "." + codificar(campo.getValue());
                var reserva = db.collection("valores_unicos").document(chave).get().get();
                assertTrue(reserva.exists(), "Reserva ausente: " + campo.getKey());
                assertEquals(usuario.getId().toString(), reserva.getString("entidadeId"));
                assertEquals("usuario", reserva.getString("entidade"));
                assertEquals(campo.getKey(), reserva.getString("campo"));
            }
        }
    }

    private static String codificar(String valor) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(valor.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void apenasUmVencedor(String campo, Throwable erroA, Throwable erroB) {
        assertNotEquals(erroA == null, erroB == null, "Exatamente uma operação deve concluir");
        assertEquals(campo, assertInstanceOf(UsuarioDuplicadoException.class,
                causa(erroA != null ? erroA : erroB)).getCampo());
    }

    private void verificarConcorrencia(String campo, Usuario a, Usuario b) throws Exception {
        var primeiro = repository.cadastrar(a);
        var segundo = repository.cadastrar(b);
        var resultadoA = primeiro.handle((usuario, erro) -> erro);
        var resultadoB = segundo.handle((usuario, erro) -> erro);
        Throwable erroA = aguardar(resultadoA);
        Throwable erroB = aguardar(resultadoB);
        apenasUmVencedor(campo, erroA, erroB);
        assertReservasConsistentes();
        assertEquals(1, aguardar(repository.getAll()).size());
        assertEquals(1, quantidadeReservas("email"));
        assertEquals(1, quantidadeReservas("telefone"));
    }

    private static Usuario usuario(String email, String telefone) {
        return Usuario.create("Pessoa Exemplo", email, telefone, LocalDate.of(1990, 1, 1));
    }

    private static <T> T aguardar(CompletableFuture<T> future) throws Exception {
        return future.get(45, TimeUnit.SECONDS);
    }

    private static void duplicado(String campo, CompletableFuture<?> future) {
        Exception erro = assertThrows(Exception.class, () -> aguardar(future));
        assertEquals(campo, assertInstanceOf(UsuarioDuplicadoException.class, causa(erro)).getCampo());
    }

    private static Throwable causa(Throwable erro) {
        while (erro.getCause() != null && !(erro instanceof UsuarioDuplicadoException)) erro = erro.getCause();
        return erro;
    }
}
