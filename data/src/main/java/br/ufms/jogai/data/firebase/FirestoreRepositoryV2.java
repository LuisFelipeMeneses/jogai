package br.ufms.jogai.data.firebase;

import br.ufms.jogai.data.model.EntityDocument;
import br.ufms.jogai.data.model.EntityDocumentMapper;
import br.ufms.jogai.domain.shared.Entidade;
import br.ufms.jogai.data.model.Unique;
import br.ufms.jogai.domain.shared.Page;
import br.ufms.jogai.domain.shared.Repository;
import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutureCallback;
import com.google.api.core.ApiFutures;
import com.google.api.gax.rpc.AlreadyExistsException;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

/**
 * Firestore repository variant that writes and reads typed document POJOs through the SDK mapper.
 *
 * @param <E>  domain entity type
 * @param <ID> entity identifier type
 * @param <D>  Firestore document DTO type
 */
public abstract class FirestoreRepositoryV2<
        E extends Entidade<ID>,
        ID extends Serializable,
        D extends EntityDocument<E>>
        implements Repository<E, ID> {

    /** Maximum number of entities returned by a page. */
    public static final int MAX_PAGE_SIZE = 200;

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
    private final EntityDocumentMapper<E, D> documentMapper;

    /**
     * Creates a repository without unique-field constraints.
     */
    private final Class<D> documentType;

    protected FirestoreRepositoryV2(CollectionReference collection, Class<D> documentType,
                                    EntityDocumentMapper<E, D> documentMapper) {
        this(collection, documentType, uniqueFields(documentType), documentMapper);
    }

    /**
     * Creates a repository whose unique values are scoped to the full collection path.
     * This also isolates identically named subcollections under different parent documents.
     * @param collection the root collection or subcollection managed by this repository
     * @param uniqueFields required canonical String fields, unique within this collection
     */
    protected FirestoreRepositoryV2(CollectionReference collection, Class<D> documentType, Set<String> uniqueFields,
                                    EntityDocumentMapper<E, D> documentMapper) {
        this(collection, Objects.requireNonNull(collection, "Collection must not be null").getPath(), documentType,
                uniqueFields, documentMapper);
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
    protected FirestoreRepositoryV2(CollectionReference collection, String scope, Class<D> documentType,
                                    Set<String> uniqueFields, EntityDocumentMapper<E, D> documentMapper) {
        this.collection = Objects.requireNonNull(collection, "Collection must not be null");
        this.db = collection.getFirestore();
        this.documentType = Objects.requireNonNull(documentType, "Document type must not be null");
        this.documentMapper = Objects.requireNonNull(documentMapper, "Document mapper must not be null");
        this.scope = Objects.requireNonNull(scope, "Scope must not be null");
        if (scope.isBlank()) {
            throw new IllegalArgumentException("Scope must not be blank");
        }
        this.uniqueFields = union(uniqueFields(documentType), uniqueFields);
        for (String field : this.uniqueFields) {
            if (field.isBlank()) throw new IllegalArgumentException("Unique field names must not be blank");
        }
    }

    private static Set<String> uniqueFields(Class<?> documentType) {
        Objects.requireNonNull(documentType, "Document type must not be null");
        Set<String> fields = new HashSet<>();
        if (documentType.isRecord()) {
            for (RecordComponent component : documentType.getRecordComponents()) {
                if (component.isAnnotationPresent(Unique.class)) fields.add(component.getName());
            }
        }
        for (Class<?> type = documentType; type != null && type != Object.class; type = type.getSuperclass()) {
            for (var field : type.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.isAnnotationPresent(Unique.class)) {
                    fields.add(field.getName());
                }
            }
            for (var method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Unique.class) && method.getParameterCount() == 0) {
                    String name = method.getName();
                    if (name.startsWith("get") && name.length() > 3) name = Character.toLowerCase(name.charAt(3)) + name.substring(4);
                    fields.add(name);
                }
            }
        }
        return Set.copyOf(fields);
    }

    private static Set<String> union(Set<String> annotated, Set<String> configured) {
        Set<String> result = new HashSet<>(annotated);
        result.addAll(Objects.requireNonNull(configured, "Unique fields must not be null"));
        return Set.copyOf(result);
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
        var captured = capture(entity);
        if (uniqueFields.isEmpty()) {
            var write = createOnly ? captured.reference().create(captured.data()) : captured.reference().set(captured.data());
            var persisted = toCompletableFuture(write);
            if (createOnly) {
                persisted = persisted.exceptionallyCompose(error -> CompletableFuture.failedFuture(
                        error instanceof AlreadyExistsException ? duplicateValueException("id") : error));
            }
            return persisted.thenApply(result -> captured.entity());
        }
        return writeTransaction(writes -> writes.addCaptured(this, captured, createOnly));
    }

    /**
     * Plans and commits multiple repository writes in one Firestore transaction.
     *
     * <p>The planner runs once, synchronously, to capture data; database retries do not rerun it.
     * Use its create/save/delete methods instead of invoking asynchronous repository writes inside
     * the planner. All participating repositories must share this Firestore client. Each document
     * may be changed only once per plan. No database reads or writes happen during planning.
     *
     * <p>Execution reads required documents, prepares shared unique reservations, then applies
     * every document and reservation together. Changed values may be transferred or swapped between
     * participating entities if the final state is unique. Any conflict or commit failure rejects
     * the whole plan. Empty plans complete without database access. Collections and subcollections
     * can participate together; construct a repository for each full collection path.
     * Deleting a parent does not implicitly delete its descendants: plan each child deletion.
     *
     * @param planner callback that prepares writes and returns the result to publish after commit
     * @param <R>     result type
     * @return a future completed only after all writes commit, or exceptionally on failure
     */
    protected final <R> CompletableFuture<R> writeTransaction(Function<RepositoryWriteTransactionV2, R> planner) {
        Objects.requireNonNull(planner, "Transaction planner must not be null");
        var writes = new RepositoryWriteTransactionV2(db);
        R result;
        try {
            result = planner.apply(writes);
            writes.seal();
        } catch (RuntimeException | Error failure) {
            writes.discard();
            throw failure;
        }
        if (writes.isEmpty()) return CompletableFuture.completedFuture(result);
        return toCompletableFuture(db.runTransaction(transaction -> {
            writes.execute(transaction);
            return result;
        }));
    }

    record CapturedEntity<E>(DocumentReference reference, Map<String, Object> data,
                             Map<String, String> uniqueValues, E entity) {
    }

    final CapturedEntity<E> capture(E entity) {
        Objects.requireNonNull(entity, "Entity must not be null");
        var reference = documentReference(entity.getId());
        String id = reference.getId();
        D document = Objects.requireNonNull(documentMapper.toDocument(entity), "Document mapper must not return null");
        Map<String, Object> data = captureDocumentData(document);
        E result = Objects.requireNonNull(document.toEntity(), "Document mapper must not return null");
        if (!id.equals(idToStr(result.getId()))) {
            throw new IllegalArgumentException("Entity mapper must preserve the document ID");
        }
        return new CapturedEntity<>(reference, data, extractUniqueValues(data), result);
    }

    final String uniqueScope() {
        return scope;
    }

    final Set<String> uniqueFields() {
        return uniqueFields;
    }

    /**
     * Deletes a document and releases its owned unique values, atomically when constraints exist.
     *
     * <p>Deleting a missing document succeeds without effect. Previous unique values are read
     * directly from the persisted canonical fields, without reconstructing the entity.
     *
     * @param id the non-null document identifier
     * @return a future completed when deletion succeeds
     */
    @Override
    public final CompletableFuture<Void> delete(ID id) {
        if (uniqueFields.isEmpty()) {
            return toCompletableFuture(documentReference(id).delete()).thenApply(result -> null);
        }
        return writeTransaction(writes -> {
            writes.delete(this, id);
            return null;
        });
    }

    private Map<String, Object> captureDocumentData(D document) {
        Map<String, Object> data = new HashMap<>();
        if (!documentType.isRecord()) throw new IllegalArgumentException("Firestore document type must be a record");
        for (RecordComponent component : documentType.getRecordComponents()) {
            if (component.isAnnotationPresent(com.google.cloud.firestore.annotation.DocumentId.class)) continue;
            try {
                data.put(component.getName(), component.getAccessor().invoke(document));
            } catch (IllegalAccessException | InvocationTargetException error) {
                throw new IllegalStateException("Could not read document field " + component.getName(), error);
            }
        }
        return Collections.unmodifiableMap(data);
    }

    final Map<String, String> extractUniqueValues(Map<String, Object> data) {
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
        return get(documentReference(id));
    }

    /**
     * Loads every document directly contained in this repository's collection.
     *
     * <p>This method has no page-size limit and materializes the entire result in memory.
     * Read volume and memory usage grow with collection size. Subcollections are not traversed.
     * Use {@link #getPage(int)} when a bounded result is preferable.
     *
     * @return all reconstructed entities, or an empty collection if no documents exist
     */
    @Override
    public final CompletableFuture<Collection<E>> getAll() {
        return toCompletableFuture(collection.get()).thenApply(this::documentsToEntities);
    }

    /**
     * Loads a bounded page in document-ID order. The cursor remains usable if its document is deleted.
     * @param pageSize maximum entities returned, from 1 to MAX_PAGE_SIZE
     * @param afterId exclusive document-ID cursor, or null for the first page
     * @return entities and an optional cursor, based on one extra look-ahead document
     */
    @Override
    public final CompletableFuture<Page<E, ID>> getPage(int pageSize, ID afterId) {
        validateLimit(pageSize, MAX_PAGE_SIZE);
        Query query = collection.orderBy(FieldPath.documentId());
        if (afterId != null) query = query.startAfter(documentReference(afterId).getId());
        return readPage(query, pageSize);
    }

    /**
     * Executes one bounded page of a filtered or ordered query.
     * @param query query filters and ordering; do not supply a conflicting start cursor
     * @param pageSize maximum returned entities, from 1 to MAX_PAGE_SIZE
     * @param afterId ID of the last returned entity from the same query, or null
     * @return the bounded page; a missing cursor document fails instead of restarting pagination
     * <p>Ordered queries use a snapshot cursor, requiring one extra document read after the first
     * page. Pagination is not a frozen database snapshot; concurrent edits can change later pages.
     */
    protected final CompletableFuture<Page<E, ID>> queryPage(Query query, int pageSize, ID afterId) {
        Objects.requireNonNull(query, "Query must not be null");
        validateLimit(pageSize, MAX_PAGE_SIZE);
        if (afterId == null) return readPage(query, pageSize);
        return toCompletableFuture(documentReference(afterId).get()).thenCompose(cursor -> {
            if (!cursor.exists()) throw new IllegalArgumentException("Page cursor document no longer exists");
            return readPage(query.startAfter(cursor), pageSize);
        });
    }

    private CompletableFuture<Page<E, ID>> readPage(Query query, int pageSize) {
        return toCompletableFuture(query.limit(pageSize + 1).get()).thenApply(snapshot -> {
            var documents = snapshot.getDocuments();
            int count = Math.min(pageSize, documents.size());
            var items = new ArrayList<E>(count);
            for (int i = 0; i < count; i++) items.add(documentToEntity(documents.get(i)));
            Optional<ID> next = documents.size() > pageSize
                    ? Optional.of(items.getLast().getId()) : Optional.empty();
            return new Page<>(items, next);
        });
    }

    private static void validateLimit(int limit, int maximum) {
        if (limit < 1 || limit > maximum) {
            throw new IllegalArgumentException("Limit must be between 1 and " + maximum);
        }
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

    // IDs identify direct children only. Relative paths would escape the configured collection
    // and make distinct documents with the same leaf ID appear to own the same reservation.
    final DocumentReference documentReference(ID id) {
        String value = Objects.requireNonNull(idToStr(id), "Mapped ID must not be null");
        if (value.isEmpty() || value.indexOf('/') >= 0) {
            throw new IllegalArgumentException("ID must be a single document name, not a relative path");
        }
        return collection.document(value);
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
        D dto = Objects.requireNonNull(document.toObject(documentType), "Firestore returned an empty document");
        return Objects.requireNonNull(dto.toEntity(), "Document mapper must not return null");
    }

//    protected abstract D toDocument(E entity);

//    private final Class<D> documentType;
//
//    protected FirestoreRepositoryV2(CollectionReference collection, Class<D> documentType) {
//        super(collection, uniqueFields(documentType));
//        this.documentType = Objects.requireNonNull(documentType);
//    }
//
//    protected abstract D toDocument(E entity);
//
//    protected abstract D fromDocument(DocumentSnapshot document);
//
//    protected E toEntity(D document) {
//        return document.toEntity();
//    }
//
//    @Override
//    protected final Map<String, Object> entityToMap(E entity) {
//        return uniqueValues(toDocument(entity));
//    }
//
//    @Override
//    protected final Object entityToDocument(E entity, Map<String, Object> mappedData) {
//        return toDocument(entity);
//    }
//
//    @Override
//    protected final E copyEntity(String id, E entity, Map<String, Object> mappedData) {
//        return toEntity(toDocument(entity));
//    }
//
//    @Override
//    protected final E snapshotToEntity(DocumentSnapshot document) {
//        return toEntity(fromDocument(document));
//    }
//
//    @Override
//    protected final E mapToEntity(String id, Map<String, Object> data) {
//        throw new UnsupportedOperationException("V2 repositories reconstruct entities from document DTOs");
//    }
//
//    @Override
//    protected final String uniqueValueToKey(String field, Object value) {
//        if (value == null) {
//            throw new IllegalArgumentException("Unique Firestore field " + field + " must not be null");
//        }
//        return canonicalValue(value);
//    }
//
//    private static Set<String> uniqueFields(Class<?> documentType) {
//        Objects.requireNonNull(documentType, "Document type must not be null");
//        if (!documentType.isRecord()) {
//            throw new IllegalArgumentException("Firestore document type must be a record");
//        }
//        return java.util.Arrays.stream(documentType.getRecordComponents())
//                .filter(component -> component.isAnnotationPresent(Unique.class))
//                .map(RecordComponent::getName)
//                .collect(java.util.stream.Collectors.toUnmodifiableSet());
//    }
//
//    private Map<String, Object> uniqueValues(D document) {
//        Map<String, Object> values = new HashMap<>();
//        for (RecordComponent component : documentType.getRecordComponents()) {
//            if (!component.isAnnotationPresent(Unique.class)) continue;
//            try {
//                Object value = component.getAccessor().invoke(document);
//                values.put(component.getName(), value);
//            } catch (IllegalAccessException | InvocationTargetException error) {
//                throw new IllegalStateException(
//                        "Could not read unique Firestore field " + component.getName(), error);
//            }
//        }
//        return values;
//    }
//
//    private static String canonicalValue(Object value) {
//        switch (value) {
//            case null -> {
//                return "null";
//            }
//            case String text -> {
//                return text;
//            }
//            case Boolean bool -> {
//                return "boolean:" + bool;
//            }
//            case Number number -> {
//                return "number:" + canonicalNumber(number);
//            }
//            case Timestamp timestamp -> {
//                return "timestamp:" + timestamp.getSeconds() + ":" + timestamp.getNanos();
//            }
//            case Date date -> {
//                Instant instant = date.toInstant();
//                return "timestamp:" + instant.getEpochSecond() + ":" + instant.getNano();
//            }
//            case GeoPoint point -> {
//                return "geopoint:" + canonicalNumber(point.getLatitude()) + ":" +
//                        canonicalNumber(point.getLongitude());
//            }
//            case Blob blob -> {
//                return "blob:" + Base64.getEncoder().encodeToString(blob.toBytes());
//            }
//            case byte[] bytes -> {
//                return "blob:" + Base64.getEncoder().encodeToString(bytes);
//            }
//            case DocumentReference reference -> {
//                return "reference:" + reference.getFirestore().getOptions().getProjectId() + ":" + reference.getPath();
//            }
//            case Enum<?> enumeration -> {
//                return enumeration.name();
//            }
//            case Collection<?> collection -> {
//                return "list:" + collection.stream().map(FirestoreRepositoryV2::canonicalValue)
//                        .map(FirestoreRepositoryV2::frame).reduce("", String::concat);
//            }
//            case Map<?, ?> map -> {
//                Map<String, Object> sorted = new TreeMap<>();
//                map.forEach((key, nestedValue) -> {
//                    if (!(key instanceof String stringKey)) {
//                        throw new IllegalArgumentException("Firestore map keys must be strings");
//                    }
//                    sorted.put(stringKey, nestedValue);
//                });
//                StringBuilder result = new StringBuilder("map:");
//                sorted.forEach((key, nestedValue) -> result.append(frame(key)).append(frame(canonicalValue(nestedValue))));
//                return result.toString();
//            }
//            default -> {
//            }
//        }
//        if (value.getClass().isArray()) {
//            StringBuilder result = new StringBuilder("list:");
//            for (int i = 0; i < Array.getLength(value); i++) {
//                result.append(frame(canonicalValue(Array.get(value, i))));
//            }
//            return result.toString();
//        }
//        throw new IllegalArgumentException(
//                "Unsupported unique Firestore field type: " + value.getClass().getName());
//    }
//
//    private static String frame(String value) {
//        return value.length() + ":" + value;
//    }
//
//    private static String canonicalNumber(Number value) {
//        if (value instanceof Double number) {
//            if (number.isNaN()) return "NaN";
//            if (number == Double.POSITIVE_INFINITY) return "+Infinity";
//            if (number == Double.NEGATIVE_INFINITY) return "-Infinity";
//        }
//        if (value instanceof Float number) {
//            if (number.isNaN()) return "NaN";
//            if (number == Float.POSITIVE_INFINITY) return "+Infinity";
//            if (number == Float.NEGATIVE_INFINITY) return "-Infinity";
//        }
//        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer ||
//                value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal ||
//                value instanceof Float || value instanceof Double)) {
//            throw new IllegalArgumentException("Unsupported numeric unique field type: " + value.getClass().getName());
//        }
//        return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
//    }
}
