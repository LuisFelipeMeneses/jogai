package br.ufms.jogai.data.firebase;

import br.ufms.jogai.domain.shared.Entidade;
import br.ufms.jogai.domain.usuario.Usuario;
import br.ufms.jogai.data.exemplo.CategoriaExemplo;
import br.ufms.jogai.data.exemplo.CategoriaExemploRepository;
import br.ufms.jogai.data.exemplo.RegistrationExampleRepository;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static br.ufms.jogai.data.firebase.UniqueValuesFirestoreRepository.COLLECTION_PATH;

import static org.junit.jupiter.api.Assertions.*;

/** Root collections, subcollections, and unique reservations share the same atomic commit. */
@EnabledIfEnvironmentVariable(named = "FIRESTORE_EMULATOR_HOST", matches = ".+")
class SubcollectionTransactionTest {
    private Firestore db;
    private ItemRepository roots, members, otherMembers, notes;

    @BeforeEach
    void configure() {
        db = FirestoreOptions.newBuilder().setProjectId("demo-jogai-" + UUID.randomUUID().toString().substring(0, 8))
                .setEmulatorHost(System.getenv("FIRESTORE_EMULATOR_HOST")).build().getService();
        roots = new ItemRepository(db.collection("groups"));
        members = new ItemRepository(db.collection("groups").document("group").collection("members"));
        otherMembers = new ItemRepository(db.collection("groups").document("other").collection("members"));
        notes = new ItemRepository(members.collection.document("member").collection("notes"));
    }

    @AfterEach
    void close() throws Exception { db.close(); }

    private void seedHierarchy() throws Exception {
        await(roots.commit(writes -> {
            writes.create(roots, item("group", "root"));
            writes.create(members, item("member", "member"));
            writes.create(notes, item("note", "note"));
        }));
    }

    @Test
    void createsAndExplicitlyDeletesAnEntireKnownHierarchyAtomically() throws Exception {
        seedHierarchy();
        assertEquals("root", await(roots.get("group")).orElseThrow().value);
        assertEquals("member", await(members.get("member")).orElseThrow().value);
        assertEquals("note", await(notes.get("note")).orElseThrow().value);
        assertReservations(3);
        await(roots.commit(writes -> {
            writes.delete(roots, "group");
            writes.delete(members, "member");
            writes.delete(notes, "note");
        }));
        assertTrue(await(roots.get("group")).isEmpty());
        assertTrue(await(members.get("member")).isEmpty());
        assertTrue(await(notes.get("note")).isEmpty());
        assertReservations(0);
    }

    @Test
    void registrationExampleCreatesAndDeletesUserAndNestedCategoryTogether() throws Exception {
        var users = new RegistrationExampleRepository(db);
        var user = Usuario.create("Pessoa Exemplo", "person@example.com", "67996122809", LocalDate.of(1990, 1, 1));
        var categories = new CategoriaExemploRepository(db.collection("usuarios").document(user.getId().toString())
                .collection("categorias"));
        var category = new CategoriaExemplo(UUID.randomUUID(), "Jogos de Tabuleiro", "EST");
        await(users.register(user, categories, category));
        assertTrue(await(users.get(user.getId())).isPresent());
        assertTrue(await(categories.get(category.getId())).isPresent());
        assertEquals(3, db.collection(COLLECTION_PATH).get().get().size());
        await(users.removeRegistration(user.getId(), categories, category.getId()));
        assertTrue(await(users.get(user.getId())).isEmpty());
        assertTrue(await(categories.get(category.getId())).isEmpty());
        assertReservations(0);
    }

    @Test
    void sameLeafIdAndValueInDifferentParentsRemainIndependent() throws Exception {
        await(roots.commit(writes -> {
            writes.create(members, item("same-id", "shared"));
            writes.create(otherMembers, item("same-id", "shared"));
        }));
        assertReservations(2);
        conflict(members.create(item("different-id", "shared")));
        await(members.delete("same-id"));
        conflict(otherMembers.create(item("different-id", "shared")));
        await(members.create(item("replacement", "shared")));
        assertReservations(2);
    }

    @Test
    void duplicateWithinNestedCollectionRejectsTheEntireCreationPlan() throws Exception {
        conflict(roots.commit(writes -> {
            writes.create(roots, item("group", "root"));
            writes.create(members, item("member", "member"));
            writes.create(notes, item("first", "duplicated"));
            writes.create(notes, item("second", "duplicated"));
        }));
        for (var repository : List.of(roots, members, notes)) assertTrue(await(repository.getPage(10)).items().isEmpty());
        assertReservations(0);
    }

    @Test
    void nestedConflictRollsBackRootUpdateChildDeletionAndGrandchildUpdate() throws Exception {
        seedHierarchy();
        await(notes.create(item("occupied", "occupied")));
        conflict(roots.commit(writes -> {
            writes.save(roots, item("group", "changed"));
            writes.delete(members, "member");
            writes.save(notes, item("note", "occupied"));
        }));
        assertEquals("root", await(roots.get("group")).orElseThrow().value);
        assertEquals("member", await(members.get("member")).orElseThrow().value);
        assertEquals("note", await(notes.get("note")).orElseThrow().value);
        assertReservations(4);
    }

    @Test
    void mixedPlanTransfersAChildUniqueValueAndDeletesItsKnownDescendants() throws Exception {
        seedHierarchy();
        await(roots.commit(writes -> {
            writes.create(members, item("replacement", "member"));
            writes.save(roots, item("group", "changed"));
            writes.delete(members, "member");
            writes.delete(notes, "note");
        }));
        assertEquals("changed", await(roots.get("group")).orElseThrow().value);
        assertTrue(await(members.get("member")).isEmpty());
        assertEquals("member", await(members.get("replacement")).orElseThrow().value);
        assertTrue(await(notes.get("note")).isEmpty());
        assertReservations(2);
    }

    @Test
    void deletingOnlyAParentDoesNotSilentlyDeleteItsChildrenOrTheirReservations() throws Exception {
        seedHierarchy();
        await(roots.delete("group"));
        assertTrue(await(members.get("member")).isPresent());
        assertTrue(await(notes.get("note")).isPresent());
        assertReservations(2);
        conflict(members.create(item("other", "member")));
    }

    @Test
    void concurrentCrossCollectionPlansNeverLeaveALosingCompanionOrDuplicateChild() throws Exception {
        var first = roots.commit(writes -> {
            writes.create(roots, item("companion-a", "a"));
            writes.create(members, item("claimant-a", "shared"));
        }).handle((ignored, error) -> error);
        var second = roots.commit(writes -> {
            writes.create(roots, item("companion-b", "b"));
            writes.create(members, item("claimant-b", "shared"));
        }).handle((ignored, error) -> error);
        await(CompletableFuture.allOf(first, second));
        var errorA = await(first);
        var errorB = await(second);
        assertNotEquals(errorA == null, errorB == null);
        Throwable failure = errorA != null ? errorA : errorB;
        while (failure.getCause() != null) failure = failure.getCause();
        assertEquals("value", assertInstanceOf(DuplicateUniqueValueException.class, failure).getField());
        assertEquals(errorA == null, await(roots.get("companion-a")).isPresent());
        assertEquals(errorA == null, await(members.get("claimant-a")).isPresent());
        assertEquals(errorB == null, await(roots.get("companion-b")).isPresent());
        assertEquals(errorB == null, await(members.get("claimant-b")).isPresent());
        assertEquals(1, await(roots.getPage(10)).items().size());
        assertEquals(1, await(members.getPage(10)).items().size());
        assertReservations(2);
    }

    @Test
    void idsCannotEscapeTheConfiguredCollectionThroughRelativePaths() throws Exception {
        var escaped = item("parent/children/same-id", "shared");
        assertThrows(IllegalArgumentException.class, () -> roots.create(escaped));
        assertThrows(IllegalArgumentException.class, () -> roots.save(escaped));
        assertThrows(IllegalArgumentException.class, () -> roots.delete(escaped.getId()));
        assertThrows(IllegalArgumentException.class, () -> roots.get(escaped.getId()));
        assertThrows(IllegalArgumentException.class, () -> roots.getPage(10, escaped.getId()));
        assertThrows(IllegalArgumentException.class, () -> roots.commit(writes -> {
            writes.create(members, item("valid", "valid"));
            writes.delete(roots, escaped.getId());
        }));
        assertTrue(await(members.getPage(10)).items().isEmpty());
        assertFalse(db.document("groups/parent/children/same-id").get().get().exists());
        assertReservations(0);
    }

    private void assertReservations(int expected) throws Exception {
        var reservations = db.collection(COLLECTION_PATH).get().get().getDocuments();
        assertEquals(expected, reservations.size());
        for (var reservation : reservations) {
            var owner = db.collection(reservation.getString("entidade"))
                    .document(reservation.getString("entidadeId")).get().get();
            assertTrue(owner.exists(), "Reservation must reference an existing owner in its full collection path");
            assertEquals("value", reservation.getString("campo"));
        }
    }

    private static Item item(String id, String value) { return new Item(id, value); }

    private static void conflict(CompletableFuture<?> future) {
        Throwable error = assertThrows(ExecutionException.class, () -> await(future));
        while (error.getCause() != null) error = error.getCause();
        assertEquals("value", assertInstanceOf(DuplicateUniqueValueException.class, error).getField());
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(60, TimeUnit.SECONDS); }

    private static final class Item extends Entidade<String> {
        private final String value;
        private Item(String id, String value) { super(id); this.value = value; }
    }

    private static final class ItemRepository extends FirestoreRepository<Item, String> {
        private ItemRepository(CollectionReference collection) { super(collection, java.util.Set.of("value")); }
        @Override protected Map<String, Object> entityToMap(Item item) { return Map.of("value", item.value); }
        @Override protected Item mapToEntity(String id, Map<String, Object> data) { return item(id, (String) data.get("value")); }
        private CompletableFuture<Void> commit(Consumer<RepositoryWriteTransaction> planner) {
            return writeTransaction(writes -> { planner.accept(writes); return null; });
        }
    }
}
