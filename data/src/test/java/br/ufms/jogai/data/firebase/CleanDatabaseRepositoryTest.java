package br.ufms.jogai.data.firebase;

import br.ufms.jogai.data.repository.UsuarioFirestoreRepository;
import br.ufms.jogai.domain.usuario.Usuario;
import br.ufms.jogai.domain.usuario.UsuarioDuplicadoException;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import io.grpc.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static br.ufms.jogai.data.firebase.UniqueValuesFirestoreRepository.COLLECTION_PATH;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Each test begins with an empty database and performs no administrative preparation.
 */
@EnabledIfEnvironmentVariable(named = "FIRESTORE_EMULATOR_HOST", matches = ".+")
class CleanDatabaseRepositoryTest {

    private Firestore db;
    private CountingRepository repository;

    @BeforeEach
    void configure() {
        db = FirestoreOptions.newBuilder().setProjectId("demo-jogai-" + UUID.randomUUID().toString().substring(0, 8))
                .setEmulatorHost(System.getenv("FIRESTORE_EMULATOR_HOST")).build().getService();
        repository = new CountingRepository(db);
    }

    @AfterEach
    void close() throws Exception {
        db.close();
    }

    @Test
    void firstSaveCanCreateAndUpdateWithoutAnyInitialization() throws Exception {
        var user = user("first", "67996122809");
        await(repository.save(user));
        user.setEmail("changed@example.com");
        user.setTelefone("67996122810");
        await(repository.save(user));
        await(repository.create(user("first", "67996122809")));
        await(repository.delete(user.getId()));
        var remaining = await(repository.getPage(10));
        assertEquals(1, remaining.items().size());
        assertEquals("first@example.com", remaining.items().getFirst().getEmail());
        assertEquals(2, db.collection(COLLECTION_PATH).get().get().size());
        assertEquals(0, db.collection("valores_unicos_controle").get().get().size());
    }

    @Test
    void simultaneousFirstCreatesHaveOneWinnerWithoutAnInitializationMarker() throws Exception {
        var results = new ArrayList<CompletableFuture<Throwable>>();
        var claimants = new ArrayList<Usuario>();
        for (int i = 0; i < 8; i++) {
            // Separate repository instances share no local initialization or ownership cache.
            var writer = new UsuarioFirestoreRepository(db);
            var claimant = user("shared", "6799612280" + i);
            claimants.add(claimant);
            results.add(writer.create(claimant).handle((created, error) -> error));
        }
        await(CompletableFuture.allOf(results.toArray(CompletableFuture[]::new)));
        int winners = 0;
        for (int i = 0; i < results.size(); i++) {
            var error = await(results.get(i));
            if (error == null) winners++;
            else {
                while (error.getCause() != null) error = error.getCause();
                if (!(error instanceof UsuarioDuplicadoException)) {
                    // Heavy contention may exhaust SDK retries; no failed transaction may leak writes.
                    var code = Status.fromThrowable(error).getCode();
                    assertTrue(code == Status.Code.ABORTED || code == Status.Code.DEADLINE_EXCEEDED,
                            "Unexpected database failure: " + error);
                    // Once the competing operations settle, the same claim must hit the committed owner.
                    error = await(repository.create(claimants.get(i)).handle((created, failure) -> failure));
                    assertNotNull(error, "A retry must not create a second owner of the shared email");
                    while (error.getCause() != null) error = error.getCause();
                }
                assertEquals("email", assertInstanceOf(UsuarioDuplicadoException.class, error).getCampo());
            }
        }
        assertEquals(1, winners);
        var winner = await(repository.getPage(10)).items().getFirst();
        assertEquals(1, db.collection("usuarios").get().get().size());
        var reservations = db.collection(COLLECTION_PATH).get().get().getDocuments();
        assertEquals(2, reservations.size());
        for (var reservation : reservations)
            assertEquals(winner.getId().toString(), reservation.getString("entidadeId"));
        assertEquals(0, db.collection("valores_unicos_controle").get().get().size());
    }

    @Test
    void updateAndDeleteUseStoredUniqueValuesWithoutReconstructingTheOldEntity() throws Exception {
        var user = user("first", "67996122809");
        await(repository.create(user));
        repository.reconstructions.set(0);
        user.setEmail("changed@example.com");
        await(repository.save(user));
        assertEquals(1, repository.reconstructions.get(), "Only the independent write result is reconstructed");
        repository.reconstructions.set(0);
        await(repository.delete(user.getId()));
        assertEquals(0, repository.reconstructions.get(), "Deletion needs only persisted unique fields");
        assertTrue(await(repository.getPage(10)).items().isEmpty());
        assertEquals(0, db.collection(COLLECTION_PATH).get().get().size());
    }

    private static Usuario user(String label, String phone) {
        return Usuario.create("Pessoa Exemplo", label + "@example.com", phone, LocalDate.of(1990, 1, 1));
    }

    private static <T> T await(CompletableFuture<T> result) throws Exception {
        return result.get(60, TimeUnit.SECONDS);
    }

    private static final class CountingRepository extends UsuarioFirestoreRepository {
        private final AtomicInteger reconstructions = new AtomicInteger();

        private CountingRepository(Firestore firestore) {
            super(firestore);
        }

        @Override
        protected Usuario mapToEntity(String id, Map<String, Object> data) {
            reconstructions.incrementAndGet();
            return super.mapToEntity(id, data);
        }
    }
}
