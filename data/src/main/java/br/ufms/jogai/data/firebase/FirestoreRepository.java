package br.ufms.jogai.data.firebase;

import br.ufms.jogai.data.repository.DuplicateUniqueValueException;
import br.ufms.jogai.data.repository.UniqueValuesFirestoreRepository;
import br.ufms.jogai.domain.shared.Entidade;
import br.ufms.jogai.domain.shared.Repository;
import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutureCallback;
import com.google.api.core.ApiFutures;
import com.google.api.gax.rpc.AlreadyExistsException;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.Transaction;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;

/**
 * Generic Firestore persistence with optional uniqueness constraints on required String fields.
 *
 * <p>Subclasses declare a collection, optionally declare a unique scope and field names, and
 * implement {@link #entityToMap(Entidade)} and {@link #mapToEntity(String, Map)}. The domain owns
 * validation and normalization; mappings must preserve its canonical values and entity IDs.
 * Reconstructed entities must be independent of the original mutable entity. Mappings must not
 * perform external side effects, since reconstruction can also occur in a retried transaction.
 *
 * <p>Without unique fields, writes use Firestore's single-document operations. With unique fields,
 * the entity and its reservations are written in one transaction. A value already owned by the
 * same entity is allowed; another owner's value rejects the entire write. Updates release changed
 * previous values, and deletion releases the deleted entity's reservations.
 *
 * <p>The first constrained write also initializes reservations for existing documents, reconstructing
 * them through the domain first. Invalid or duplicate legacy data aborts initialization. Large
 * collections require a separately planned migration because transaction limits still apply.
 * Changing normalization, removing constraints, or changing scopes also requires migration.
 * All writers must follow this reservation protocol; direct privileged writes can bypass it.
 *
 * <p>Database failures complete returned futures exceptionally. Null arguments, mapping failures,
 * and local SDK validation may throw synchronously. This repository does not own or close the
 * supplied {@link Firestore} client. Existing storage names are preserved for compatibility.
 *
 * @param <E>  the domain entity type
 * @param <ID> the entity identifier type; its String representation must be a valid document ID
 */
public abstract class FirestoreRepository<E extends Entidade<ID>, ID extends Serializable>
        implements Repository<E, ID> {

    /**
     * Shared Firestore client owned by the application.
     */
    protected final Firestore db;

    /**
     * Collection containing the entity documents.
     */
    protected final CollectionReference collection;

    private final String scope;
    private final Set<String> uniqueFields;
    private final UniqueValuesFirestoreRepository uniqueValues;
    private final DocumentReference initializationMarker;

    /**
     * Creates a repository without unique-field constraints.
     *
     * @param collection the non-null entity collection
     */
    protected FirestoreRepository(CollectionReference collection) {
        this(collection, Objects.requireNonNull(collection, "Collection must not be null").getPath(), Set.of());
    }

    /**
     * Creates a repository with optional unique-field constraints.
     *
     * <p>Each field is unique independently, rather than as a composite key. The scope must be
     * stable and exclusive to this entity collection. An empty field set disables reservations.
     * For example: {@code super(db.collection("accounts"), "account", Set.of("email"))}.
     *
     * @param collection   the non-null entity collection
     * @param scope        the non-blank uniqueness namespace
     * @param uniqueFields required String fields, using the names produced by {@code entityToMap}
     * @throws IllegalArgumentException if the scope or a reservation key component is blank
     * @throws NullPointerException     if an argument or a field name is null
     */
    protected FirestoreRepository(CollectionReference collection, String scope, Set<String> uniqueFields) {
        this.collection = Objects.requireNonNull(collection, "Collection must not be null");
        this.db = collection.getFirestore();
        this.scope = Objects.requireNonNull(scope, "Scope must not be null");
        if (scope.isBlank()) {
            throw new IllegalArgumentException("Scope must not be blank");
        }
        this.uniqueFields = Set.copyOf(Objects.requireNonNull(uniqueFields, "Unique fields must not be null"));
        this.uniqueValues = new UniqueValuesFirestoreRepository(db);
        this.initializationMarker = uniqueValues.initializationMarker(collection.getPath(), scope, this.uniqueFields);
    }

    /**
     * Creates a new entity, rejecting an existing ID or another entity's unique values.
     *
     * @param entity the validated entity to create
     * @return a future containing an independent reconstruction of the captured entity data
     * @see #duplicateValueException(String)
     */
    public final CompletableFuture<E> create(E entity) {
        return persist(entity, true);
    }

    /**
     * Creates or completely replaces the document identified by the entity's ID.
     *
     * <p>Unique values owned by the same entity remain valid. On conflict, neither the document
     * nor its reservations changes. Fields absent from the mapping are removed from the document.
     *
     * @param entity the validated entity to save
     * @return a future containing an independent reconstruction of the captured entity data
     */
    @Override
    public final CompletableFuture<E> save(E entity) {
        return persist(entity, false);
    }

    private CompletableFuture<E> persist(E entity, boolean createOnly) {
        Objects.requireNonNull(entity, "Entity must not be null");
        String id = idToStr(entity.getId());
        // Capture once so every transaction attempt uses the same canonical values.
        Map<String, Object> capturedData = captureEntityData(entity);
        Map<String, String> currentValues = extractUniqueValues(capturedData);
        E resultEntity = mapToEntity(id, capturedData);
        DocumentReference reference = collection.document(id);

        if (uniqueFields.isEmpty()) {
            // create enforces ID absence atomically; no preliminary read is needed.
            var write = createOnly ? reference.create(capturedData) : reference.set(capturedData);
            return toCompletableFuture(write)
                    .exceptionallyCompose(error -> CompletableFuture.failedFuture(
                            createOnly && error instanceof AlreadyExistsException ? duplicateValueException("id") : error))
                    .thenApply(result -> resultEntity);
        }

        return toCompletableFuture(db.runTransaction(transaction -> {
            var previous = transaction.get(reference).get();
            if (createOnly && previous.exists()) {
                throw duplicateValueException("id");
            }
            var reservations = uniqueValues.inTransaction(transaction);
            boolean initialize;
            try {
                initialize = prepareInitialization(transaction, reservations);
                reservations.replace(scope, id, previous.exists() ?
                        extractUniqueValues(previous) : Map.of(), currentValues);
            } catch (DuplicateUniqueValueException conflict) {
                throw duplicateValueException(conflict.getField());
            }
            // All reads are complete. Reservations and entity data share one commit.
            reservations.apply();
            if (initialize) {
                // Retain existing stored field names; changing the API requires no data migration.
                transaction.set(initializationMarker, Map.of("colecao", collection.getPath(), "escopo", scope));
            }
            transaction.set(reference, capturedData);
            return resultEntity;
        }));
    }

    /**
     * Deletes a document and releases its owned unique values, atomically when constraints exist.
     *
     * <p>Deleting a missing document succeeds without effect. Legacy documents with constraints
     * are reconstructed through the domain before their reservation keys are determined.
     *
     * @param id the non-null document identifier
     * @return a future completed when deletion succeeds
     */
    @Override
    public final CompletableFuture<Void> delete(ID id) {
        String entityId = idToStr(id);
        DocumentReference reference = collection.document(entityId);
        if (uniqueFields.isEmpty()) {
            return toCompletableFuture(reference.delete()).thenApply(result -> null);
        }
        return toCompletableFuture(db.runTransaction(transaction -> {
            var document = transaction.get(reference).get();
            if (!document.exists()) {
                return null;
            }
            var reservations = uniqueValues.inTransaction(transaction);
            reservations.replace(scope, entityId, extractUniqueValues(document), Map.of());
            reservations.apply();
            transaction.delete(reference);
            return null;
        }));
    }

    private boolean prepareInitialization(Transaction transaction, UniqueValuesFirestoreRepository.Operation reservations)
            throws ExecutionException, InterruptedException {
        if (transaction.get(initializationMarker).get().exists()) {
            return false;
        }
        // Prepare every reservation before scheduling any writes, including the marker itself.
        for (var document : transaction.get(collection).get().getDocuments()) {
            reservations.replace(scope, document.getId(), Map.of(), extractUniqueValues(document));
        }
        return true;
    }

    private Map<String, Object> captureEntityData(E entity) {
        Map<String, Object> data = Objects.requireNonNull(entityToMap(entity), "Entity data must not be null");
        for (Object value : data.values()) {
            if (value == null) {
                // Map.copyOf rejects nulls; preserve optional fields with a defensive snapshot.
                return Map.copyOf(data);
            }
        }
        // The JDK can reuse an immutable source map; mutable maps are defensively copied.
        return Map.copyOf(data);
    }

    private Map<String, String> extractUniqueValues(Map<String, Object> data) {
        if (uniqueFields.isEmpty()) return Map.of();
        Map<String, String> values = HashMap.newHashMap(uniqueFields.size());
        uniqueFields.forEach(field -> {
            if (!(data.get(field) instanceof String value)) {
                throw new IllegalArgumentException("Unique field " + field + " must be a required String");
            }
            values.put(field, value);
        });
        // This map stays private to the persistence flow; no second copy is needed.
        return values;
    }

    private Map<String, String> extractUniqueValues(DocumentSnapshot document) {
        // Reconstruct legacy data through the domain to obtain its canonical values.
        return extractUniqueValues(entityToMap(documentToEntity(document)));
    }

    /**
     * Loads an entity from a document reference.
     *
     * @param reference the non-null document reference
     * @return the reconstructed entity, or an empty Optional if the document is missing
     */
    protected CompletableFuture<Optional<E>> get(DocumentReference reference) {
        Objects.requireNonNull(reference, "Document reference must not be null");
        return toCompletableFuture(reference.get())
                .thenApply(document -> document.exists() ?
                        Optional.of(documentToEntity(document)) :
                        Optional.empty());
    }

    /**
     * Loads an entity by ID.
     *
     * @param id the non-null document identifier
     * @return the entity, or an empty Optional if no document exists
     */
    @Override
    public CompletableFuture<Optional<E>> get(ID id) {
        return get(collection.document(idToStr(id)));
    }

    /**
     * Loads all documents in the configured collection; this operation is not paginated.
     *
     * @return all reconstructed entities
     */
    @Override
    public CompletableFuture<Collection<E>> getAll() {
        return query(collection);
    }

    /**
     * Executes a query and reconstructs its results.
     *
     * @param query the non-null Firestore query, including any desired ordering or limits
     * @return the matching entities
     */
    protected CompletableFuture<Collection<E>> query(Query query) {
        Objects.requireNonNull(query, "Query must not be null");
        return toCompletableFuture(query.get()).thenApply(this::documentsToEntities);
    }

    /**
     * Returns at most one query result. This method does not establish uniqueness.
     *
     * @param query the non-null query; supply ordering when the first result must be predictable
     * @return the first matching entity, or an empty Optional
     */
    protected CompletableFuture<Optional<E>> queryOne(Query query) {
        Objects.requireNonNull(query, "Query must not be null");
        return toCompletableFuture(query.limit(1).get())
                .thenApply(snapshot -> {
                    var documents = snapshot.getDocuments();
                    return documents.isEmpty() ? Optional.empty() : Optional.of(documentToEntity(documents.getFirst()));
                });
    }

    /**
     * Converts a query snapshot using the subclass's entity mapping.
     *
     * @param snapshot the query result
     * @return the reconstructed entities in query order
     */
    protected Collection<E> documentsToEntities(QuerySnapshot snapshot) {
        return snapshot.getDocuments().stream().map(this::documentToEntity).toList();
    }

    /**
     * Parses a UUID document ID.
     *
     * @param id the UUID string
     * @return the parsed UUID
     * @throws IllegalArgumentException if the string is not a valid UUID
     */
    protected static UUID idToUUID(String id) {
        return UUID.fromString(id);
    }

    /**
     * Converts an ID into a document name; subclasses may override for custom identifier types.
     *
     * @param id the non-null entity identifier
     * @return the document ID, which must be stable and unique for the identifier
     */
    protected String idToStr(ID id) {
        return String.valueOf(Objects.requireNonNull(id, "ID must not be null"));
    }

    /**
     * Converts an Instant without losing nanosecond precision.
     *
     * @param instant the non-null instant
     * @return the equivalent Firestore timestamp before any server-side precision conversion
     */
    protected static Timestamp toTimestamp(Instant instant) {
        Objects.requireNonNull(instant, "Instant must not be null");
        return Timestamp.ofTimeSecondsAndNanos(instant.getEpochSecond(), instant.getNano());
    }

    /**
     * Stores a date as midnight UTC, independent of the machine's default time zone.
     *
     * @param date the date to convert
     * @return a timestamp representing the start of that date in UTC
     */
    protected static Timestamp toTimestamp(LocalDate date) {
        return toTimestamp(date.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    /**
     * Converts a timestamp to an Instant without losing its nanosecond component.
     *
     * @param timestamp the non-null Firestore timestamp
     * @return the equivalent instant
     */
    protected static Instant toInstant(Timestamp timestamp) {
        Objects.requireNonNull(timestamp, "Timestamp must not be null");
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    /**
     * Extracts the calendar date in UTC.
     *
     * @param timestamp the timestamp to convert
     * @return its UTC date
     */
    protected static LocalDate toLocalDate(Timestamp timestamp) {
        return toInstant(timestamp).atZone(ZoneOffset.UTC).toLocalDate();
    }

    /**
     * Adapts an ApiFuture, preserving its result or failure using the common ForkJoinPool.
     * Cancellation of the returned future is not propagated to the source future.
     *
     * @param apiFuture the source asynchronous operation
     * @param <T>       the result type
     * @return the adapted future
     */
    protected <T> CompletableFuture<T> toCompletableFuture(ApiFuture<T> apiFuture) {
        CompletableFuture<T> future = new CompletableFuture<>();
        ApiFutures.addCallback(apiFuture, new ApiFutureCallback<>() {
            @Override
            public void onSuccess(T result) {
                future.complete(result);
            }

            @Override
            public void onFailure(Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        }, ForkJoinPool.commonPool());
        return future;
    }

    /**
     * Captures a persistable representation of a valid domain entity.
     *
     * @param entity the source entity
     * @return a non-null map containing the canonical unique values; non-unique fields may be null
     * <p><strong>Implementation contract:</strong> Do not normalize values here. Preserve domain values and copy mutable nested
     * structures so subsequent changes to the original entity cannot alter the captured write.
     */
    protected abstract Map<String, Object> entityToMap(E entity);

    /**
     * Supplies the exception used for duplicate IDs and unique-field conflicts.
     *
     * @param field the conflicting persisted field name, or {@code "id"} for an existing ID
     * @return the exception to propagate; defaults to DuplicateUniqueValueException
     * <p><strong>Implementation contract:</strong> Override to translate infrastructure conflicts into an application's domain exception.
     */
    protected RuntimeException duplicateValueException(String field) {
        return new DuplicateUniqueValueException(field);
    }

    /**
     * Reconstructs an existing document through the domain mapper.
     *
     * @param document an existing document snapshot
     * @return an independent domain entity
     */
    protected final E documentToEntity(DocumentSnapshot document) {
        return mapToEntity(document.getId(), document.getData());
    }

    /**
     * Reconstructs an independent domain entity for reads and captured write results.
     *
     * @param id   the document ID to preserve
     * @param data the persisted or captured data; this map may be unmodifiable
     * @return a non-null entity whose construction enforces domain invariants
     * <p><strong>Implementation contract:</strong> Do not mutate the supplied map or generate a new ID. Reconstruct mutable nested
     * values independently, and apply validation and normalization through the domain itself.
     */
    protected abstract E mapToEntity(String id, Map<String, Object> data);
}
