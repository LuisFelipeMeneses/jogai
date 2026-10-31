package br.ufms.jogai.data.firebase;

import br.ufms.jogai.data.exemplo.CategoriaExemplo;
import br.ufms.jogai.data.exemplo.CategoriaExemploRepository;
import br.ufms.jogai.data.exemplo.RegistrationExampleRepository;
import br.ufms.jogai.data.repository.UsuarioFirestoreRepository;
import br.ufms.jogai.data.repository.DuplicateUniqueValueException;
import br.ufms.jogai.domain.usuario.Usuario;
import br.ufms.jogai.domain.usuario.UsuarioDuplicadoException;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.api.gax.grpc.GrpcStatusCode;
import com.google.api.gax.rpc.AbortedException;
import io.grpc.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static br.ufms.jogai.data.firebase.UniqueValuesFirestoreRepository.COLLECTION_PATH;

import static org.junit.jupiter.api.Assertions.*;

/** Multi-document invariants are checked against persisted data, not merely future completion. */
@EnabledIfEnvironmentVariable(named = "FIRESTORE_EMULATOR_HOST", matches = ".+")
class RepositoryWriteTransactionTest {
    private Firestore db;
    private RegistrationExampleRepository users;
    private CategoriaExemploRepository categories;

    @BeforeEach
    void configure() throws Exception {
        db = connection();
        users = new RegistrationExampleRepository(db);
        categories = new CategoriaExemploRepository(db);
    }

    private Firestore connection() {
        return FirestoreOptions.newBuilder()
                .setProjectId("demo-jogai-" + UUID.randomUUID().toString().substring(0, 8))
                .setEmulatorHost(System.getenv("FIRESTORE_EMULATOR_HOST")).build().getService();
    }

    @AfterEach
    void close() throws Exception { db.close(); }

    @Test
    void batchCreatesAllUsersAndReservations() throws Exception {
        var result = await(users.cadastrarTodos(List.of(user("a", 1), user("b", 2))));
        assertEquals(2, result.size());
        assertEquals(2, await(users.getPage(100).thenApply(page -> page.items())).size());
        assertConsistent();
    }

    @Test
    void duplicateEmailWithinOneTransactionRollsBackEverything() throws Exception {
        conflict("email", users.cadastrarTodos(List.of(user("same", 1), user("same", 2))));
        assertEmpty();
    }

    @Test
    void duplicatePhoneWithinOneTransactionRollsBackEverything() throws Exception {
        conflict("telefone", users.cadastrarTodos(List.of(user("a", 1), user("b", 1))));
        assertEmpty();
    }

    @Test
    void existingConflictOnSecondMemberDoesNotCreateTheFirstMember() throws Exception {
        await(users.create(user("occupied", 1)));
        conflict("email", users.cadastrarTodos(List.of(user("new", 2), user("occupied", 3))));
        assertEquals(1, await(users.getPage(100).thenApply(page -> page.items())).size());
        assertConsistent();
    }

    @Test
    void conflictingBatchUpdateKeepsEveryOriginalValue() throws Exception {
        var a = user("a", 1);
        var b = user("b", 2);
        await(users.cadastrarTodos(List.of(a, b)));
        a.setEmail("collision@example.com");
        b.setEmail("collision@example.com");
        conflict("email", users.writeTransaction(writes -> List.of(writes.save(users, a), writes.save(users, b))));
        assertEquals("a@example.com", await(users.get(a.getId())).orElseThrow().getEmail());
        assertEquals("b@example.com", await(users.get(b.getId())).orElseThrow().getEmail());
        assertConsistent();
    }

    @Test
    void unchangedUniqueValuesCannotBeClaimedByAnotherMemberOfTheSamePlan() throws Exception {
        var owner = user("owner", 1);
        var borrower = user("borrower", 2);
        await(users.cadastrarTodos(List.of(owner, borrower)));
        owner.setNome("Nome Alterado");
        borrower.setEmail(owner.getEmail());
        conflict("email", users.writeTransaction(writes -> List.of(
                writes.save(users, borrower), writes.save(users, owner))));
        assertEquals("Pessoa Exemplo", await(users.get(owner.getId())).orElseThrow().getNome());
        assertEquals("borrower@example.com", await(users.get(borrower.getId())).orElseThrow().getEmail());
        assertConsistent();
    }

    @Test
    void emailsAndPhonesCanBeSwappedAtomically() throws Exception {
        var a = user("a", 1);
        var b = user("b", 2);
        await(users.cadastrarTodos(List.of(a, b)));
        String emailA = a.getEmail(), phoneA = a.getTelefone();
        a.setEmail(b.getEmail()); a.setTelefone(b.getTelefone());
        b.setEmail(emailA); b.setTelefone(phoneA);
        await(users.writeTransaction(writes -> List.of(writes.save(users, a), writes.save(users, b))));
        assertEquals("b@example.com", await(users.get(a.getId())).orElseThrow().getEmail());
        assertEquals("a@example.com", await(users.get(b.getId())).orElseThrow().getEmail());
        assertConsistent();
    }

    @Test
    void deletingAnOwnerAndReusingItsValuesCommitsTogether() throws Exception {
        var old = user("a", 1);
        var replacement = user("a", 1);
        await(users.create(old));
        await(users.writeTransaction(writes -> {
            // Planning order does not affect the final-state ownership check.
            var result = writes.create(users, replacement);
            writes.delete(users, old.getId());
            return result;
        }));
        assertTrue(await(users.get(old.getId())).isEmpty());
        assertTrue(await(users.get(replacement.getId())).isPresent());
        assertConsistent();
    }

    @Test
    void saveExampleCreatesOrUpdatesBothDocumentsAndRollsBackOnConflict() throws Exception {
        var user = user("a", 1);
        var category = category("EST");
        await(users.saveRegistration(user, categories, category));
        user.setEmail("changed@example.com");
        category.setCodigo("COOP");
        await(users.saveRegistration(user, categories, category));
        assertEquals("changed@example.com", await(users.get(user.getId())).orElseThrow().getEmail());
        assertEquals("COOP", await(categories.get(category.getId())).orElseThrow().getCodigo());
        assertConsistent();

        await(categories.create(category("OCCUPIED")));
        user.setNome("Nome Rejeitado");
        category.setCodigo("OCCUPIED");
        var error = assertThrows(ExecutionException.class,
                () -> await(users.saveRegistration(user, categories, category)));
        assertEquals("codigo", assertInstanceOf(DuplicateUniqueValueException.class, cause(error)).getField());
        assertEquals("Pessoa Exemplo", await(users.get(user.getId())).orElseThrow().getNome());
        assertEquals("COOP", await(categories.get(category.getId())).orElseThrow().getCodigo());
        assertConsistent();
    }

    @Test
    void exampleCommitsTwoRepositoriesAndTheirReservations() throws Exception {
        var result = await(users.register(user("a", 1), categories, category("EST")));
        assertTrue(await(users.get(result.user().getId())).isPresent());
        assertTrue(await(categories.get(result.category().getId())).isPresent());
        assertConsistent();
    }

    @Test
    void crossRepositoryConflictDoesNotLeaveAUserOrItsReservations() throws Exception {
        await(categories.create(category("EST")));
        assertThrows(ExecutionException.class,
                () -> await(users.register(user("a", 1), categories, category("EST"))));
        assertTrue(await(users.getPage(100).thenApply(page -> page.items())).isEmpty());
        assertEquals(1, await(categories.getPage(100).thenApply(page -> page.items())).size());
        assertConsistent();
    }

    @Test
    void serverCommitFailureRollsBackBothCollectionsAndInitialization() throws Exception {
        var invalid = new FirestoreRepository<CategoriaExemplo, UUID>(db.collection("invalid_categories")) {
            @Override protected Map<String, Object> entityToMap(CategoriaExemplo entity) {
                return Map.of("nome", entity.getNome(), "codigo", entity.getCodigo(), "payload", "x".repeat(1_048_577));
            }
            @Override protected CategoriaExemplo mapToEntity(String id, Map<String, Object> data) {
                return new CategoriaExemplo(idToUUID(id), (String) data.get("nome"), (String) data.get("codigo"));
            }
        };
        var result = users.writeTransaction(writes -> List.of(writes.create(users, user("a", 1)),
                writes.create(invalid, category("EST"))));
        assertThrows(ExecutionException.class, () -> await(result));
        assertEmpty();
        assertEquals(0, db.collection("invalid_categories").get().get().size());
    }

    @Test
    void plannerFailureAndDuplicateDocumentPlansWriteNothing() throws Exception {
        var a = user("a", 1);
        assertThrows(IllegalArgumentException.class, () -> users.cadastrarTodos(List.of(a, a)));
        assertThrows(IllegalStateException.class, () -> users.writeTransaction(writes -> {
            writes.create(users, a);
            throw new IllegalStateException("Planning failed");
        }));
        assertEmpty();
    }

    @Test
    void planIsSealedAndPlannerRunsOnlyOnce() throws Exception {
        var escaped = new AtomicReference<RepositoryWriteTransaction>();
        var calls = new AtomicInteger();
        var result = users.writeTransaction(writes -> {
            calls.incrementAndGet();
            escaped.set(writes);
            return writes.create(users, user("a", 1));
        });
        assertThrows(IllegalStateException.class, () -> escaped.get().create(users, user("b", 2)));
        await(result);
        assertEquals(1, calls.get());
        assertEquals(1, await(users.getPage(100).thenApply(page -> page.items())).size());
        assertConsistent();
    }

    @Test
    void unconstrainedSaveDeletePlanNeedsNoReadsAndStillCommitsAtomically() throws Exception {
        var plain = categoryRepository("plain_categories", "plain", false);
        var removed = category("OLD");
        await(plain.create(removed));
        var created = category("NEW");
        var plan = new RepositoryWriteTransaction(db);
        plan.save(plain, created);
        plan.delete(plain, removed.getId());
        plan.delete(plain, UUID.randomUUID());
        plan.seal();
        db.runTransaction(transaction -> {
            // The SDK rejects any reads after a write. This verifies the zero-read fast path.
            transaction.set(db.collection("plain_markers").document("committed"), Map.of("value", true));
            plan.execute(transaction);
            return null;
        }).get(90, TimeUnit.SECONDS);
        assertTrue(await(plain.get(removed.getId())).isEmpty());
        assertTrue(await(plain.get(created.getId())).isPresent());
        assertTrue(db.collection("plain_markers").document("committed").get().get().exists());
        assertEquals(0, db.collection(COLLECTION_PATH).get().get().size());
        assertEquals(0, db.collection("valores_unicos_controle").get().get().size());
    }

    @Test
    void mixedPlanHandlesUnconstrainedChangesWithoutWeakeningUniqueChecks() throws Exception {
        var plain = categoryRepository("plain_categories", "plain", false);
        var removed = category("OLD");
        await(plain.create(removed));
        var newCategory = category("NEW");
        await(users.writeTransaction(writes -> {
            writes.create(users, user("a", 1));
            writes.save(plain, newCategory);
            writes.delete(plain, removed.getId());
            return null;
        }));
        assertTrue(await(plain.get(removed.getId())).isEmpty());
        assertTrue(await(plain.get(newCategory.getId())).isPresent());
        assertConsistent();
        var rejectedCategory = category("REJECTED");
        conflict("email", users.writeTransaction(writes -> {
            writes.save(plain, rejectedCategory);
            writes.delete(plain, newCategory.getId());
            return writes.create(users, user("a", 2));
        }));
        assertTrue(await(plain.get(rejectedCategory.getId())).isEmpty());
        assertTrue(await(plain.get(newCategory.getId())).isPresent());
        assertConsistent();
    }

    @Test
    void sealedPlanCanRetryWithoutReusingSnapshotsOrPendingReservations() throws Exception {
        var plan = new RepositoryWriteTransaction(db);
        plan.create(users, user("a", 1));
        plan.create(users, user("b", 2));
        plan.seal();
        var attempts = new AtomicInteger();
        db.runTransaction(transaction -> {
            plan.execute(transaction);
            if (attempts.incrementAndGet() == 1) {
                throw new AbortedException("Injected retry after scheduling all writes", null,
                        GrpcStatusCode.of(Status.Code.ABORTED), true);
            }
            return null;
        }).get(90, TimeUnit.SECONDS);
        assertEquals(2, attempts.get());
        assertEquals(2, await(users.getPage(100).thenApply(page -> page.items())).size());
        assertConsistent();
    }

    @Test
    void aFailedPlannerDiscardsAndSealsItsEscapedPlan() throws Exception {
        var escaped = new AtomicReference<RepositoryWriteTransaction>();
        assertThrows(IllegalStateException.class, () -> users.writeTransaction(writes -> {
            escaped.set(writes);
            writes.create(users, user("a", 1));
            throw new IllegalStateException("Planning failed");
        }));
        assertThrows(IllegalStateException.class, () -> escaped.get().create(users, user("b", 2)));
        assertEmpty();
    }

    @Test
    void anotherThreadCannotMutateThePlanWhileItIsBeingBuilt() throws Exception {
        var result = users.writeTransaction(writes -> {
            var captured = writes.create(users, user("a", 1));
            var rejected = CompletableFuture.runAsync(() -> writes.create(users, user("b", 2)));
            assertThrows(java.util.concurrent.CompletionException.class, rejected::join);
            return captured;
        });
        await(result);
        assertEquals(1, await(users.getPage(100).thenApply(page -> page.items())).size());
        assertConsistent();
    }

    @Test
    void repositoriesWithDifferentFirestoreClientsCannotShareAPlan() throws Exception {
        try (var other = connection()) {
            var otherUsers = new UsuarioFirestoreRepository(other);
            assertThrows(IllegalArgumentException.class, () -> users.writeTransaction(writes -> {
                writes.create(users, user("a", 1));
                return writes.create(otherUsers, user("b", 2));
            }));
        }
        assertEmpty();
    }

    @Test
    void inconsistentConfigurationsOrSharedScopesAreRejectedBeforeWriting() throws Exception {
        var noConstraints = categoryRepository("usuarios", "usuarios", false);
        assertThrows(IllegalArgumentException.class, () -> users.writeTransaction(writes -> {
            writes.create(users, user("a", 1));
            return writes.create(noConstraints, category("EST"));
        }));
        var sameScope = categoryRepository("other_categories", "usuarios", true);
        assertThrows(IllegalArgumentException.class, () -> users.writeTransaction(writes -> {
            writes.create(users, user("a", 1));
            return writes.create(sameScope, category("EST"));
        }));
        assertEmpty();
    }

    @Test
    void mapperCannotReturnAnEntityWithADifferentId() throws Exception {
        var invalid = new FirestoreRepository<CategoriaExemplo, UUID>(db.collection("invalid_categories")) {
            @Override protected Map<String, Object> entityToMap(CategoriaExemplo entity) {
                return Map.of("nome", entity.getNome(), "codigo", entity.getCodigo());
            }
            @Override protected CategoriaExemplo mapToEntity(String id, Map<String, Object> data) {
                return new CategoriaExemplo(UUID.randomUUID(), (String) data.get("nome"), (String) data.get("codigo"));
            }
        };
        assertThrows(IllegalArgumentException.class, () -> users.writeTransaction(writes -> {
            writes.create(users, user("a", 1));
            return writes.create(invalid, category("EST"));
        }));
        assertEmpty();
    }

    @Test
    void emptyPlanCompletesAndUnconstrainedBatchesAllowDuplicateValues() throws Exception {
        var empty = users.writeTransaction(writes -> 123);
        assertTrue(empty.isDone());
        assertEquals(123, await(empty));
        assertEmpty();
        var plain = categoryRepository("plain_categories", "plain", false);
        await(users.writeTransaction(writes -> List.of(writes.create(plain, category("EST")),
                writes.create(plain, category("EST")))));
        assertEquals(2, await(plain.getPage(100).thenApply(page -> page.items())).size());
        assertEquals(0, db.collection(COLLECTION_PATH).get().get().size());
        assertEquals(0, db.collection("valores_unicos_controle").get().get().size());
    }

    private FirestoreRepository<CategoriaExemplo, UUID> categoryRepository(String collection, String scope, boolean unique) {
        return new FirestoreRepository<>(db.collection(collection), scope, unique ? java.util.Set.of("codigo") : java.util.Set.of()) {
            @Override protected Map<String, Object> entityToMap(CategoriaExemplo entity) {
                return Map.of("nome", entity.getNome(), "codigo", entity.getCodigo());
            }
            @Override protected CategoriaExemplo mapToEntity(String id, Map<String, Object> data) {
                return new CategoriaExemplo(idToUUID(id), (String) data.get("nome"), (String) data.get("codigo"));
            }
        };
    }

    @Test
    void eightSimultaneousBatchesHaveOneWinnerAndNoPartialCompanions() throws Exception {
        await(users.create(user("warmup", 99)));
        var warmup = await(users.buscarPorEmail("warmup@example.com")).orElseThrow();
        await(users.delete(warmup.getId()));
        List<Supplier<CompletableFuture<?>>> actions = new ArrayList<>();
        List<Usuario> companions = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            var companion = user("companion-" + i, 100 + i);
            var claimant = user("shared", 200 + i);
            companions.add(companion);
            actions.add(() -> users.cadastrarTodos(List.of(companion, claimant)));
        }
        var errors = concurrently(actions);
        int winners = 0;
        for (int i = 0; i < errors.size(); i++) {
            if (errors.get(i) == null) winners++;
            else assertEquals("email", assertInstanceOf(UsuarioDuplicadoException.class, cause(errors.get(i))).getCampo());
            assertEquals(errors.get(i) == null, await(users.get(companions.get(i).getId())).isPresent());
        }
        assertEquals(1, winners);
        assertEquals(2, await(users.getPage(100).thenApply(page -> page.items())).size());
        assertConsistent();
    }

    @Test
    void simultaneousUpdatesOfTheSamePairKeepOneCompleteFinalVersion() throws Exception {
        var originalA = user("a", 1);
        var originalB = user("b", 2);
        await(users.cadastrarTodos(List.of(originalA, originalB)));
        List<Supplier<CompletableFuture<?>>> actions = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int version = i;
            var a = copy(originalA);
            var b = copy(originalB);
            a.setEmail("a-version-" + i + "@example.com");
            b.setEmail("b-version-" + i + "@example.com");
            actions.add(() -> users.writeTransaction(writes -> {
                writes.save(users, a);
                writes.save(users, b);
                return version;
            }));
        }
        for (var error : concurrently(actions)) assertNull(error);
        String emailA = await(users.get(originalA.getId())).orElseThrow().getEmail();
        String emailB = await(users.get(originalB.getId())).orElseThrow().getEmail();
        assertEquals(emailA.substring("a-".length()), emailB.substring("b-".length()));
        assertEquals(2, await(users.getPage(100).thenApply(page -> page.items())).size());
        assertConsistent();
    }

    @Test
    void concurrentDeletionAndValueReuseNeverExposePartialGroups() throws Exception {
        var a = user("a", 1);
        var b = user("b", 2);
        await(users.cadastrarTodos(List.of(a, b)));
        var results = concurrently(List.of(() -> users.writeTransaction(writes -> {
            writes.delete(users, a.getId());
            writes.delete(users, b.getId());
            return null;
        }), () -> users.cadastrarTodos(List.of(user("a", 1), user("b", 2)))));
        assertNull(results.get(0));
        if (results.get(1) != null) assertInstanceOf(UsuarioDuplicadoException.class, cause(results.get(1)));
        assertEquals(results.get(1) == null ? 2 : 0, await(users.getPage(100).thenApply(page -> page.items())).size());
        assertConsistent();
    }

    @Test
    void observerSeesWholeBatchesDuringConcurrentIndependentWrites() throws Exception {
        AtomicBoolean finished = new AtomicBoolean();
        AtomicInteger observations = new AtomicInteger();
        try (var observerExecutor = Executors.newSingleThreadExecutor()) {
            var observer = CompletableFuture.runAsync(() -> {
                try {
                    do {
                        Map<String, Integer> groups = new HashMap<>();
                        for (var document : db.collection("usuarios").get().get(30, TimeUnit.SECONDS).getDocuments()) {
                            String local = document.getString("email").split("@")[0];
                            groups.merge(local.substring(0, local.lastIndexOf('-')), 1, Integer::sum);
                        }
                        for (var count : groups.values()) assertEquals(2, count);
                        observations.incrementAndGet();
                    } while ((!finished.get() || observations.get() < 3) && observations.get() < 100);
                } catch (Exception error) { throw new IllegalStateException(error); }
            }, observerExecutor);
            List<Supplier<CompletableFuture<?>>> actions = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                var a = user("pair-" + i + "-a", 300 + i * 2);
                var b = user("pair-" + i + "-b", 301 + i * 2);
                actions.add(() -> users.cadastrarTodos(List.of(a, b)));
            }
            try { for (var error : concurrently(actions)) assertNull(error); }
            finally { finished.set(true); }
            await(observer);
        }
        assertTrue(observations.get() >= 3);
        assertEquals(8, await(users.getPage(100).thenApply(page -> page.items())).size());
        assertConsistent();
    }

    @Test
    void singletonAndBatchCompetingForOneEmailCannotBothCommit() throws Exception {
        var companion = user("companion", 1);
        var errors = concurrently(List.of(() -> users.cadastrarTodos(List.of(companion, user("shared", 2))),
                () -> users.create(user("shared", 3))));
        assertNotEquals(errors.get(0) == null, errors.get(1) == null);
        assertEquals(errors.get(0) == null, await(users.get(companion.getId())).isPresent());
        assertConsistent();
    }

    private List<Throwable> concurrently(List<Supplier<CompletableFuture<?>>> actions) throws Exception {
        var ready = new CountDownLatch(actions.size());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(actions.size())) {
            List<CompletableFuture<Throwable>> results = new ArrayList<>();
            for (var action : actions) results.add(CompletableFuture.supplyAsync(() -> {
                ready.countDown();
                try {
                    if (!start.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Start gate timed out");
                    return action.get();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            }, executor).thenCompose(future -> future).handle((result, error) -> error));
            try { assertTrue(ready.await(15, TimeUnit.SECONDS)); }
            finally { start.countDown(); }
            List<Throwable> errors = new ArrayList<>();
            for (var result : results) errors.add(await(result));
            return errors;
        }
    }

    private void assertEmpty() throws Exception {
        assertTrue(await(users.getPage(100).thenApply(page -> page.items())).isEmpty());
        assertTrue(await(categories.getPage(100).thenApply(page -> page.items())).isEmpty());
        assertEquals(0, db.collection("valores_unicos_controle").get().get().size());
        assertConsistent();
    }

    private void assertConsistent() throws Exception {
        Map<String, Map<String, Object>> expected = new HashMap<>();
        var persistedUsers = await(users.getPage(100).thenApply(page -> page.items()));
        var emails = new HashSet<String>();
        var phones = new HashSet<String>();
        for (var user : persistedUsers) {
            assertTrue(emails.add(user.getEmail()), "Duplicate persisted email");
            assertTrue(phones.add(user.getTelefone()), "Duplicate persisted phone");
            expectReservation(expected, "usuarios", "email", user.getEmail(), user.getId());
            expectReservation(expected, "usuarios", "telefone", user.getTelefone(), user.getId());
        }
        var codes = new HashSet<String>();
        for (var category : await(categories.getPage(100).thenApply(page -> page.items()))) {
            assertTrue(codes.add(category.getCodigo()));
            expectReservation(expected, "categoria_exemplo", "codigo", category.getCodigo(), category.getId());
        }
        Map<String, Map<String, Object>> actual = new HashMap<>();
        for (var reservation : db.collection(COLLECTION_PATH).get().get().getDocuments()) {
            actual.put(reservation.getId(), reservation.getData());
        }
        assertEquals(expected, actual, "Every persisted unique value must have exactly its current owner's reservation");
    }

    private void expectReservation(Map<String, Map<String, Object>> expected, String scope, String field, String value, UUID id) {
        String key = encode(scope) + "." + encode(field) + "." + encode(value);
        assertNull(expected.put(key, Map.of("entidade", scope, "campo", field, "entidadeId", id.toString())));
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Usuario user(String label, int phone) {
        return Usuario.create("Pessoa Exemplo", label + "@example.com", String.format("679%08d", phone), LocalDate.of(1990, 1, 1));
    }

    private static Usuario copy(Usuario user) {
        return Usuario.reconstitute(user.getId(), user.getNome(), user.getEmail(), user.getTelefone(),
                user.getDataNascimento(), user.getDataCadastro(), user.getStatus());
    }

    private static CategoriaExemplo category(String code) {
        return new CategoriaExemplo(UUID.randomUUID(), "Categoria Exemplo", code);
    }

    private static <T> T await(CompletableFuture<T> result) throws Exception { return result.get(90, TimeUnit.SECONDS); }

    private static Throwable cause(Throwable error) {
        while (error.getCause() != null && !(error instanceof UsuarioDuplicadoException)) error = error.getCause();
        return error;
    }

    private static void conflict(String field, CompletableFuture<?> result) {
        var error = assertThrows(ExecutionException.class, () -> await(result));
        assertEquals(field, assertInstanceOf(UsuarioDuplicadoException.class, cause(error)).getCampo());
    }
}
