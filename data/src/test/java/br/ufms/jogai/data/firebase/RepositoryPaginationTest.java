package br.ufms.jogai.data.firebase;

import br.ufms.jogai.domain.shared.Entidade;
import br.ufms.jogai.domain.shared.Page;
import br.ufms.jogai.domain.shared.Repository;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "FIRESTORE_EMULATOR_HOST", matches = ".+")
class RepositoryPaginationTest {

    private Firestore db;
    private ItemRepository repository;

    @BeforeEach
    void configure() {
        db = FirestoreOptions.newBuilder().setProjectId("demo-jogai-" + UUID.randomUUID().toString().substring(0, 8))
                .setEmulatorHost(System.getenv("FIRESTORE_EMULATOR_HOST")).build().getService();
        repository = new ItemRepository(db);
    }

    @AfterEach
    void close() throws Exception {
        db.close();
    }

    private void seed() throws Exception {
        await(repository.writeTransaction(writes -> {
            for (char id = 'a'; id <= 'g'; id++)
                writes.create(repository,
                        new Item(String.valueOf(id), "Same", (id - 'a') % 2 == 0 ? "A" : "B"));
            return null;
        }));
    }

    @Test
    void getAllReturnsAnEmptyCollectionThroughTheRepositoryInterface() throws Exception {
        Repository<Item, String> contract = repository;
        assertTrue(await(contract.getAll()).isEmpty());
    }

    @Test
    void getAllIncludesMoreThanOneMaximumPageAndDoesNotTraverseSubcollections() throws Exception {
        int count = FirestoreRepository.MAX_PAGE_SIZE + 3;
        await(repository.writeTransaction(writes -> {
            for (int i = 0; i < count; i++) {
                writes.create(repository, new Item("item-" + i, "Name " + i, "A"));
            }
            return null;
        }));
        repository.collection.document("item-0").collection("children").document("nested")
                .set(Map.of("name", "Nested", "group", "A")).get();
        var all = await(repository.getAll());
        var ids = all.stream().map(Item::getId).collect(java.util.stream.Collectors.toSet());
        assertEquals(count, all.size());
        assertEquals(count, ids.size());
        for (int i = 0; i < count; i++) assertTrue(ids.contains("item-" + i));
        assertFalse(ids.contains("nested"));
        var page = await(repository.getPage(FirestoreRepository.MAX_PAGE_SIZE));
        assertEquals(FirestoreRepository.MAX_PAGE_SIZE, page.items().size());
        assertTrue(page.nextCursor().isPresent());
    }

    @Test
    void identifierPagesAreBoundedExclusiveAndComplete() throws Exception {
        seed();
        String cursor = null;
        var ids = new HashSet<String>();
        int pages = 0;
        do {
            var page = await(repository.getPage(2, cursor));
            assertTrue(page.items().size() <= 2);
            for (var item : page.items()) assertTrue(ids.add(item.getId()), "Repeated entity across pages");
            cursor = page.nextCursor().orElse(null);
            pages++;
        } while (cursor != null);
        assertEquals(4, pages);
        assertEquals(java.util.Set.of("a", "b", "c", "d", "e", "f", "g"), ids);
        var end = await(repository.getPage(2, "g"));
        assertTrue(end.items().isEmpty());
        assertTrue(end.nextCursor().isEmpty());
    }

    @Test
    void lookAheadDocumentIsNotReconstructedOrReturned() throws Exception {
        seed();
        repository.mappings.set(0);
        var page = await(repository.getPage(2));
        assertEquals(List.of("a", "b"), page.items().stream().map(Item::getId).toList());
        assertEquals("b", page.nextCursor().orElseThrow());
        assertEquals(2, repository.mappings.get());
    }

    @Test
    void documentIdCursorRemainsUsableAfterItsDocumentIsDeleted() throws Exception {
        seed();
        var first = await(repository.getPage(2));
        await(repository.delete("b"));
        var second = await(repository.getPage(2, first.nextCursor().orElseThrow()));
        assertEquals(List.of("c", "d"), second.items().stream().map(Item::getId).toList());
    }

    @Test
    void orderedFilteredPagesHandleTiesAndNeverIncludeOtherGroups() throws Exception {
        seed();
        var first = await(repository.byGroup("A", 2, null));
        var second = await(repository.byGroup("A", 2, first.nextCursor().orElseThrow()));
        assertEquals(List.of("a", "c"), first.items().stream().map(Item::getId).toList());
        assertEquals(List.of("e", "g"), second.items().stream().map(Item::getId).toList());
        assertTrue(second.nextCursor().isEmpty());
    }

    @Test
    void missingOrderedCursorFailsExplicitlyInsteadOfRestarting() throws Exception {
        seed();
        var first = await(repository.byGroup("A", 2, null));
        await(repository.delete(first.nextCursor().orElseThrow()));
        var error = assertThrows(ExecutionException.class,
                () -> await(repository.byGroup("A", 2, first.nextCursor().orElseThrow())));
        assertInstanceOf(IllegalArgumentException.class, error.getCause());
    }

    @Test
    void invalidLimitsFailAndEmptyPagesHaveNoCursor() throws Exception {
        for (int limit : new int[]{0, -1, FirestoreRepository.MAX_PAGE_SIZE + 1}) {
            assertThrows(IllegalArgumentException.class, () -> repository.getPage(limit));
            assertThrows(IllegalArgumentException.class, () -> repository.byGroup("A", limit, null));
        }
        var page = await(repository.getPage(FirestoreRepository.MAX_PAGE_SIZE));
        assertTrue(page.items().isEmpty());
        assertTrue(page.nextCursor().isEmpty());
    }

    private static <T> T await(CompletableFuture<T> result) throws Exception {
        return result.get(60, TimeUnit.SECONDS);
    }

    private static final class Item extends Entidade<String> {
        private final String name, group;

        private Item(String id, String name, String group) {
            super(id);
            this.name = name;
            this.group = group;
        }
    }

    private static final class ItemRepository extends FirestoreRepository<Item, String> {
        private final AtomicInteger mappings = new AtomicInteger();

        private ItemRepository(Firestore firestore) {
            super(firestore.collection("items"));
        }

        @Override
        protected Map<String, Object> entityToMap(Item entity) {
            return Map.of("name", entity.name, "group", entity.group);
        }

        @Override
        protected Item mapToEntity(String id, Map<String, Object> data) {
            mappings.incrementAndGet();
            return new Item(id, (String) data.get("name"), (String) data.get("group"));
        }

        CompletableFuture<Page<Item, String>> byGroup(String group, int size, String cursor) {
            return queryPage(collection.whereEqualTo("group", group).orderBy("name"), size, cursor);
        }
    }
}
