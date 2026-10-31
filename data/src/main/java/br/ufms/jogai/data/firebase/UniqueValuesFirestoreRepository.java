package br.ufms.jogai.data.firebase;

import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldMask;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Manages reservations for canonical String values in a caller-owned transaction.
 * This repository never starts or commits transactions. Reservation field names
 * are retained for storage compatibility; Base64 encoding is reversible, not encryption.
 */
public final class UniqueValuesFirestoreRepository {

    private static final Base64.Encoder KEY_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final FieldMask OWNER_MASK = FieldMask.of("entidadeId");

    /** Shared collection name for persistence code and integration tests. */
    public static final String COLLECTION_PATH = "_uniqueValues_";

    private final CollectionReference collection;

    /**
     * Creates a reservation repository without reading or writing any documents.
     * @param firestore the application-owned Firestore client
     */
    public UniqueValuesFirestoreRepository(Firestore firestore) {
        collection = Objects.requireNonNull(firestore).collection(COLLECTION_PATH);
    }

    /**
     * Creates attempt-local reservation state for a caller-owned transaction.
     * @param transaction the transaction that will commit all related writes
     * @return a fresh operation that must not be shared across retry attempts
     */
    public Operation inTransaction(Transaction transaction) {
        return new Operation(Objects.requireNonNull(transaction));
    }

    private DocumentReference reference(String scope, String field, String value) {
        // Encode each component separately to avoid ambiguous reservation keys.
        return collection.document(encode(scope) + "." + encode(field) + "." + encode(value));
    }

    private static String encode(String value) {
        if (Objects.requireNonNull(value).isBlank()) {
            throw new IllegalArgumentException("Reservation key components must not be blank");
        }
        return KEY_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private record Reservation(String scope, String field, String entityId) {
        Map<String, Object> data() {
            return Map.of("entidade", scope, "campo", field, "entidadeId", entityId);
        }
    }

    /**
     * Use one operation per transaction callback attempt; finish reads before apply().
     */
    public final class Operation {

        private final Transaction transaction;
        private final Map<DocumentReference, DocumentSnapshot> readDocuments = new LinkedHashMap<>();
        // A null value schedules deletion; an absent entry schedules no change.
        private final Map<DocumentReference, Reservation> pendingChanges = new LinkedHashMap<>();
        private boolean applied;

        private Operation(Transaction transaction) {
            this.transaction = transaction;
        }

        /**
         * Checks all new values before preparing the release of changed previous values.
         * Uncached reservation documents are fetched together inside the transaction, returning
         * only their owner field. Missing documents and missing owner fields remain distinguishable.
         * @param scope the unique namespace
         * @param entityId the reservation owner's ID
         * @param previousValues previously persisted canonical values
         * @param currentValues desired canonical values; empty when deleting an entity
         * @throws ExecutionException if a database read fails
         * @throws InterruptedException if a database read is interrupted
         * @throws DuplicateUniqueValueException if a value belongs to another entity
         */
        public void replace(String scope, String entityId,
                               Map<String, String> previousValues, Map<String, String> currentValues)
                throws ExecutionException, InterruptedException {
            checkNotApplied();
            Objects.requireNonNull(entityId);
            if (currentValues.isEmpty() && previousValues.isEmpty()) return;
            Set<DocumentReference> references = HashSet.newHashSet(currentValues.size() + previousValues.size());
            currentValues.forEach((field, value) -> references.add(reference(scope, field, value)));
            previousValues.forEach((field, value) -> {
                if (!Objects.equals(value, currentValues.get(field))) {
                    references.add(reference(scope, field, value));
                }
            });
            references.removeAll(readDocuments.keySet());
            if (!references.isEmpty()) {
                // Batch reads retain transaction conflict detection, including for missing documents.
                for (var document : transaction.getAll(references.toArray(DocumentReference[]::new), OWNER_MASK).get()) {
                    readDocuments.put(document.getReference(), document);
                }
            }
            for (var field : currentValues.entrySet()) {
                reserve(scope, field.getKey(), field.getValue(), entityId);
            }
            for (var field : previousValues.entrySet()) {
                if (!Objects.equals(field.getValue(), currentValues.get(field.getKey()))) {
                    release(scope, field.getKey(), field.getValue(), entityId);
                }
            }
        }

        /**
         * Checks persisted and pending ownership, then schedules a reservation in memory.
         * @param scope the unique namespace
         * @param field the constrained field name
         * @param value the canonical value, already validated and normalized by the domain
         * @param entityId the reservation owner's ID
         * @throws ExecutionException if a database read fails
         * @throws InterruptedException if a database read is interrupted
         * @throws DuplicateUniqueValueException if another owner occupies the value
         * @throws IllegalStateException if writes have already been applied
         */
        public void reserve(String scope, String field, String value, String entityId)
                throws ExecutionException, InterruptedException {
            Objects.requireNonNull(entityId);
            DocumentReference reference = reference(scope, field, value);
            DocumentSnapshot existing = read(reference);
            Reservation pending = pendingChanges.get(reference);
            if (pending != null) {
                if (!entityId.equals(pending.entityId())) throw new DuplicateUniqueValueException(field);
                return;
            }
            boolean released = pendingChanges.containsKey(reference);
            if (existing.exists() && !entityId.equals(existing.getString("entidadeId")) && !released) {
                throw new DuplicateUniqueValueException(field);
            }
            if (existing.exists() && entityId.equals(existing.getString("entidadeId"))) {
                // Ownership was verified by a transactional read: do not rewrite an unchanged reservation.
                // Also cancel any release scheduled earlier in this operation.
                pendingChanges.remove(reference);
            } else {
                pendingChanges.put(reference, new Reservation(scope, field, entityId));
            }
        }

        /**
         * Schedules release only if the supplied entity owns the persisted or pending reservation.
         * @param scope the unique namespace
         * @param field the constrained field name
         * @param value the previous canonical value
         * @param entityId the reservation owner's ID
         * @throws ExecutionException if a database read fails
         * @throws InterruptedException if a database read is interrupted
         * @throws IllegalStateException if writes have already been applied
         */
        public void release(String scope, String field, String value, String entityId)
                throws ExecutionException, InterruptedException {
            Objects.requireNonNull(entityId);
            DocumentReference reference = reference(scope, field, value);
            DocumentSnapshot existing = read(reference);
            Reservation pending = pendingChanges.get(reference);
            // Never release another entity's reservation, even if existing data is inconsistent.
            if (pending != null) {
                if (entityId.equals(pending.entityId())) {
                    if (existing.exists()) pendingChanges.put(reference, null);
                    else pendingChanges.remove(reference);
                }
            } else if (entityId.equals(existing.getString("entidadeId"))) {
                pendingChanges.put(reference, null);
            }
        }

        private DocumentSnapshot read(DocumentReference reference)
                throws ExecutionException, InterruptedException {
            checkNotApplied();
            DocumentSnapshot document = readDocuments.get(reference);
            if (document == null) {
                document = transaction.get(reference).get();
                readDocuments.put(reference, document);
            }
            return document;
        }

        /**
         * Schedules the final writes on the supplied transaction; this method does not commit.
         * After this call, the caller must not attempt further reads in the transaction.
         * @throws IllegalStateException if this operation has already been applied
         */
        public void apply() {
            checkNotApplied();
            applied = true;
            pendingChanges.forEach((reference, reservation) -> {
                if (reservation == null) transaction.delete(reference);
                else transaction.set(reference, reservation.data());
            });
        }

        private void checkNotApplied() {
            if (applied) {
                throw new IllegalStateException("This reservation operation has already been applied");
            }
        }
    }
}
