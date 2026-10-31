package br.ufms.jogai.data.firebase;

import br.ufms.jogai.domain.shared.Entidade;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static br.ufms.jogai.data.firebase.UniqueValuesFirestoreRepository.COLLECTION_PATH;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Projection must preserve literal field names and never truncate the replacement write.
 */
@EnabledIfEnvironmentVariable(named = "FIRESTORE_EMULATOR_HOST", matches = ".+")
class ProjectedReadsTest {

    private Firestore db;
    private PayloadRepository repository;

    @BeforeEach
    void configure() {
        db = FirestoreOptions.newBuilder().setProjectId("demo-jogai-" + UUID.randomUUID().toString().substring(0, 8))
                .setEmulatorHost(System.getenv("FIRESTORE_EMULATOR_HOST")).build().getService();
        repository = new PayloadRepository(db);
    }

    @AfterEach
    void close() throws Exception {
        db.close();
    }

    @Test
    void literalDottedUniqueFieldCanChangeAndBeReleasedWithoutTruncatingUnrelatedData() throws Exception {
        String body = "payload".repeat(20_000);
        await(repository.create(new Payload("a", "old@example.com", body)));
        await(repository.save(new Payload("a", "new@example.com", body)));
        await(repository.create(new Payload("b", "old@example.com", body)));
        var failure = assertThrows(ExecutionException.class,
                () -> await(repository.save(new Payload("b", "new@example.com", "rejected"))));
        Throwable error = failure;
        while (error.getCause() != null) error = error.getCause();
        assertEquals("contact.email", assertInstanceOf(DuplicateUniqueValueException.class, error).getField());
        for (String id : Set.of("a", "b")) assertEquals(body, await(repository.get(id)).orElseThrow().body);
        assertEquals("old@example.com", await(repository.get("b")).orElseThrow().email);
        await(repository.delete("a"));
        await(repository.create(new Payload("c", "new@example.com", body)));
        assertEquals(2, db.collection(COLLECTION_PATH).get().get().size());
        assertEquals("different@example.com", db.collection("payloads").document("b").get().get().getString("contact.email"));
        // The SDK string accessor above follows a nested path; the literal key remains separate.
        assertEquals("old@example.com", db.collection("payloads").document("b").get().get().getData().get("contact.email"));
    }

    private static <T> T await(CompletableFuture<T> result) throws Exception {
        return result.get(60, TimeUnit.SECONDS);
    }

    private static final class Payload extends Entidade<String> {
        private final String email, body;

        private Payload(String id, String email, String body) {
            super(id);
            this.email = email;
            this.body = body;
        }
    }

    private static final class PayloadRepository extends FirestoreRepository<Payload, String> {
        private PayloadRepository(Firestore firestore) {
            super(firestore.collection("payloads"), Set.of("contact.email"));
        }

        @Override
        protected Map<String, Object> entityToMap(Payload entity) {
            return Map.of("contact.email", entity.email, "payload", entity.body,
                    "contact", Map.of("email", "different@example.com"));
        }

        @Override
        protected Payload mapToEntity(String id, Map<String, Object> data) {
            return new Payload(id, (String) data.get("contact.email"), (String) data.get("payload"));
        }
    }
}
