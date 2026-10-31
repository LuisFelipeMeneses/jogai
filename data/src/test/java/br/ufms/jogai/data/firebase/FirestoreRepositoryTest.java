package br.ufms.jogai.data.firebase;

import br.ufms.jogai.domain.shared.Entidade;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static br.ufms.jogai.data.firebase.UniqueValuesFirestoreRepository.COLLECTION_PATH;

import static org.junit.jupiter.api.Assertions.*;

/** Exercita o fluxo sem campos únicos, inclusive IDs String e valores opcionais. */
@EnabledIfEnvironmentVariable(named = "FIRESTORE_EMULATOR_HOST", matches = ".+")
class FirestoreRepositoryTest {

    private Firestore db;
    private RegistroRepository repository;

    @BeforeEach
    void configurar() {
        db = FirestoreOptions.newBuilder()
                .setProjectId("demo-jogai-" + UUID.randomUUID().toString().substring(0, 8))
                .setEmulatorHost(System.getenv("FIRESTORE_EMULATOR_HOST"))
                .build().getService();
        repository = new RegistroRepository(db);
    }

    @AfterEach
    void fechar() throws Exception {
        if (db != null) db.close();
    }

    @Test
    void permiteValoresRepetidosSemCriarReservasOuControles() throws Exception {
        aguardar(repository.create(new Registro("a", "igual")));
        aguardar(repository.create(new Registro("b", "igual")));
        assertEquals(2, aguardar(repository.getPage(100).thenApply(page -> page.items())).size());
        assertSemReservas();
    }

    @Test
    void cadastroRejeitaIdExistenteSemSobrescreverDocumento() throws Exception {
        aguardar(repository.create(new Registro("a", "original")));
        var erro = conflito(repository.create(new Registro("a", "substituto")));
        assertEquals("id", erro.getField());
        assertEquals("original", aguardar(repository.get("a")).orElseThrow().valor);
        assertSemReservas();
    }

    @Test
    void saveCriaEAtualizaEConsultasRetornamOsDados() throws Exception {
        aguardar(repository.save(new Registro("a", "primeiro")));
        aguardar(repository.save(new Registro("a", "segundo")));
        assertEquals("segundo", aguardar(repository.get("a")).orElseThrow().valor);
        assertTrue(aguardar(repository.get("inexistente")).isEmpty());
        assertEquals(1, aguardar(repository.getPage(100).thenApply(page -> page.items())).size());
        assertEquals("a", aguardar(repository.buscar("segundo")).orElseThrow().getId());
        assertTrue(aguardar(repository.buscar("primeiro")).isEmpty());
        assertSemReservas();
    }

    @Test
    void exclusaoEhIdempotente() throws Exception {
        aguardar(repository.create(new Registro("a", "valor")));
        aguardar(repository.delete("a"));
        aguardar(repository.delete("a"));
        assertTrue(aguardar(repository.get("a")).isEmpty());
        assertSemReservas();
    }

    @Test
    void cadastrosConcorrentesDoMesmoIdTemApenasUmVencedor() throws Exception {
        var a = repository.create(new Registro("a", "primeiro")).handle((registro, erro) -> erro);
        var b = repository.create(new Registro("a", "segundo")).handle((registro, erro) -> erro);
        Throwable erroA = aguardar(a);
        Throwable erroB = aguardar(b);
        assertNotEquals(erroA == null, erroB == null);
        assertEquals("id", assertInstanceOf(DuplicateUniqueValueException.class,
                causa(erroA != null ? erroA : erroB)).getField());
        assertEquals(erroA == null ? "primeiro" : "segundo",
                aguardar(repository.get("a")).orElseThrow().valor);
        assertEquals(1, aguardar(repository.getPage(100).thenApply(page -> page.items())).size());
        assertSemReservas();
    }

    @Test
    void resultadoNaoCompartilhaObjetoMutavelEMapeamentoAceitaNulosOpcionais() throws Exception {
        Registro original = new Registro("a", "capturado");
        var cadastro = repository.create(original);
        original.valor = "alterado";
        Registro resultado = aguardar(cadastro);
        assertNotSame(original, resultado);
        assertEquals("capturado", resultado.valor);
        assertEquals("capturado", aguardar(repository.get("a")).orElseThrow().valor);
        aguardar(repository.save(new Registro("b", null)));
        assertNull(aguardar(repository.get("b")).orElseThrow().valor);
        assertSemReservas();
    }

    @Test
    void conjuntoVazioExplicitoTambemDesabilitaUnicidade() throws Exception {
        RegistroRepository explicito = new RegistroRepository(db, Set.of());
        aguardar(explicito.create(new Registro("a", "igual")));
        aguardar(explicito.create(new Registro("b", "igual")));
        aguardar(explicito.delete("a"));
        assertEquals(1, aguardar(explicito.getPage(100).thenApply(page -> page.items())).size());
        assertSemReservas();
    }

    @Test
    void falhaDePersistenciaNaoEhTraduzidaComoDuplicidade() throws Exception {
        // O servidor rejeita documentos acima do limite de tamanho, sem conflito de ID.
        FirestoreRepository<Registro, String> invalido = new RegistroRepository(db) {
            @Override
            protected Map<String, Object> entityToMap(Registro registro) {
                return Map.of("valor", registro.valor, "payload", "x".repeat(1_048_577));
            }
        };
        ExecutionException erro = assertThrows(ExecutionException.class,
                () -> aguardar(invalido.create(new Registro("a", "valor"))));
        assertFalse(causa(erro) instanceof DuplicateUniqueValueException);
        assertTrue(aguardar(repository.get("a")).isEmpty());
        aguardar(repository.create(new Registro("a", "original")));
        ExecutionException falhaSave = assertThrows(ExecutionException.class,
                () -> aguardar(invalido.save(new Registro("a", "substituto"))));
        assertFalse(causa(falhaSave) instanceof DuplicateUniqueValueException);
        assertEquals("original", aguardar(repository.get("a")).orElseThrow().valor);
        assertSemReservas();
    }

    private void assertSemReservas() throws Exception {
        assertEquals(0, db.collection(COLLECTION_PATH).get().get().size());
        assertEquals(0, db.collection("valores_unicos_controle").get().get().size());
    }

    private static DuplicateUniqueValueException conflito(CompletableFuture<?> resultado) {
        ExecutionException erro = assertThrows(ExecutionException.class, () -> aguardar(resultado));
        return assertInstanceOf(DuplicateUniqueValueException.class, causa(erro));
    }

    private static Throwable causa(Throwable erro) {
        while (erro.getCause() != null) erro = erro.getCause();
        return erro;
    }

    private static <T> T aguardar(CompletableFuture<T> resultado) throws Exception {
        return resultado.get(45, TimeUnit.SECONDS);
    }

    private static class Registro extends Entidade<String> {
        private String valor;

        Registro(String id, String valor) {
            super(id);
            this.valor = valor;
        }
    }

    private static class RegistroRepository extends FirestoreRepository<Registro, String> {
        RegistroRepository(Firestore db) {
            super(db.collection("registros"));
        }

        RegistroRepository(Firestore db, Set<String> campos) {
            super(db.collection("registros"), "registro", campos);
        }

        @Override
        protected Map<String, Object> entityToMap(Registro registro) {
            Map<String, Object> dados = new LinkedHashMap<>();
            dados.put("valor", registro.valor);
            return dados;
        }

        @Override
        protected Registro mapToEntity(String id, Map<String, Object> dados) {
            return new Registro(id, (String) dados.get("valor"));
        }

        CompletableFuture<java.util.Optional<Registro>> buscar(String valor) {
            return queryOne(collection.whereEqualTo("valor", valor));
        }
    }
}
