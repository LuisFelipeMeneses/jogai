package br.ufms.jogai.data.firebase;

import br.ufms.jogai.domain.shared.Entidade;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldMask;
import com.google.cloud.firestore.FieldPath;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

/**
 * A write plan created by {@link FirestoreRepositoryV2#writeTransaction}.
 * Captures data once and creates fresh execution state on each database retry.
 * This object exposes no raw SDK transaction: callers cannot accidentally write before reads
 * or commit reservations separately. At most one mutation per document is accepted.
 * Planning is confined to the creating thread; asynchronous writes belong to separate plans.
 * Sealing compiles stable request metadata once and releases builder-only maps. Database retries
 * reuse only this metadata; snapshots and ownership decisions are always reconstructed.
 */
public final class RepositoryWriteTransactionV2 {
    private enum Kind {CREATE, SAVE, DELETE}

    private record Mutation(FirestoreRepositoryV2<?, ?, ?> repository, DocumentReference reference,
                            Kind kind, Object data, Map<String, String> values) {
    }

    private record Configuration(String scope, java.util.Set<String> fields) {
    }

    // Only immutable plan metadata is reused. SDK snapshots and ownership state remain attempt-local.
    private record ExecutionPlan(List<Mutation> mutations, DocumentReference[] references,
                                 FieldMask readMask, boolean hasUniqueFields) {
    }

    private final Firestore firestore;
    private final long planningThreadId = Thread.currentThread().threadId();
    private final UniqueValuesFirestoreRepository uniqueValues;
    private Map<DocumentReference, Mutation> pendingMutations = new LinkedHashMap<>();
    private Map<String, Configuration> configurations = new HashMap<>();
    private Map<String, String> scopes = new HashMap<>();
    private ExecutionPlan plan;
    private boolean sealed;

    RepositoryWriteTransactionV2(Firestore firestore) {
        this.firestore = firestore;
        this.uniqueValues = new UniqueValuesFirestoreRepository(firestore);
    }

    /**
     * Plans a create that rejects an existing ID or a conflicting final unique value.
     *
     * @param repository participating repository sharing the plan's Firestore client
     * @param entity     valid domain entity; its data is captured before this method returns
     * @param <E>        entity type
     * @param <ID>       identifier type
     * @return independent captured entity, also suitable for the plan's result
     */
    public <E extends Entidade<ID>, ID extends Serializable, D extends br.ufms.jogai.data.model.EntityDocument<E>> E create(FirestoreRepositoryV2<E, ID, D> repository, E entity) {
        checkOpen();
        return addCaptured(repository, repository.capture(entity), true);
    }

    /**
     * Plans a full-document create or replacement.
     *
     * @param repository participating repository
     * @param entity     valid domain entity to snapshot
     * @param <E>        entity type
     * @param <ID>       identifier type
     * @return independent captured entity
     */
    public <E extends Entidade<ID>, ID extends Serializable, D extends br.ufms.jogai.data.model.EntityDocument<E>> E save(FirestoreRepositoryV2<E, ID, D> repository, E entity) {
        checkOpen();
        return addCaptured(repository, repository.capture(entity), false);
    }

    <E> E addCaptured(FirestoreRepositoryV2<?, ?, ?> repository, FirestoreRepositoryV2.CapturedEntity<E> captured, boolean createOnly) {
        add(new Mutation(repository, captured.reference(), createOnly ? Kind.CREATE : Kind.SAVE,
                captured.data(), captured.uniqueValues()));
        return captured.entity();
    }

    /**
     * Plans deletion and release of owned unique values. A missing document is a no-op.
     *
     * @param repository participating repository
     * @param id         non-null identifier
     * @param <E>        entity type
     * @param <ID>       identifier type
     */
    public <E extends Entidade<ID>, ID extends Serializable, D extends br.ufms.jogai.data.model.EntityDocument<E>> void delete(FirestoreRepositoryV2<E, ID, D> repository, ID id) {
        checkOpen();
        add(new Mutation(repository, repository.documentReference(id), Kind.DELETE, Map.of(), Map.of()));
    }

    private void add(Mutation mutation) {
        checkOpen();
        var repository = Objects.requireNonNull(mutation.repository());
        if (repository.db != firestore)
            throw new IllegalArgumentException("Repositories must share the same Firestore client");
        if (pendingMutations.containsKey(mutation.reference())) {
            throw new IllegalArgumentException("A document may be changed only once per transaction: " + mutation.reference().getPath());
        }
        String path = repository.collection.getPath();
        var previous = configurations.get(path);
        if (previous != null) {
            if (!previous.scope().equals(repository.uniqueScope()) || !previous.fields().equals(repository.uniqueFields())) {
                throw new IllegalArgumentException("Repositories for the same collection must use identical unique constraints");
            }
        } else {
            String scopeOwner = scopes.get(repository.uniqueScope());
            if (!repository.uniqueFields().isEmpty() && scopeOwner != null && !scopeOwner.equals(path)) {
                throw new IllegalArgumentException("A unique scope cannot belong to different collections in one plan");
            }
            configurations.put(path, new Configuration(repository.uniqueScope(), repository.uniqueFields()));
            if (!repository.uniqueFields().isEmpty()) scopes.put(repository.uniqueScope(), path);
        }
        pendingMutations.put(mutation.reference(), mutation);
    }

    private void checkOpen() {
        if (sealed) throw new IllegalStateException("This write plan is already sealed");
        if (Thread.currentThread().threadId() != planningThreadId) {
            throw new IllegalStateException("Write plans must be built synchronously on their creating thread");
        }
    }

    void seal() {
        checkOpen();
        var mutations = List.copyOf(pendingMutations.values());
        // add() already guarantees one mutation per reference; a second hash set adds no protection.
        var references = new ArrayList<DocumentReference>(mutations.size());
        var fields = new HashSet<String>();
        boolean hasUniqueFields = false;
        for (var mutation : mutations) {
            var repository = mutation.repository();
            if (mutation.kind() == Kind.CREATE || !repository.uniqueFields().isEmpty()) {
                references.add(mutation.reference());
            }
            if (!repository.uniqueFields().isEmpty()) {
                hasUniqueFields = true;
                fields.addAll(repository.uniqueFields());
            }
        }
        // FieldPath.of treats a configured name containing dots as one literal top-level field.
        // An empty mask retains document existence/identity for unconstrained create checks.
        var mask = FieldMask.of(fields.stream().map(FieldPath::of).toArray(FieldPath[]::new));
        plan = new ExecutionPlan(mutations, references.toArray(DocumentReference[]::new), mask, hasUniqueFields);
        sealed = true;
        releasePlanningState();
    }

    // A failed planner must not compile or retain partially captured mutations.
    void discard() {
        sealed = true;
        plan = null;
        releasePlanningState();
    }

    private void releasePlanningState() {
        pendingMutations = null;
        configurations = null;
        scopes = null;
    }

    boolean isEmpty() {
        return plan.mutations().isEmpty();
    }

    void execute(Transaction transaction) throws ExecutionException, InterruptedException {
        if (!sealed || plan == null)
            throw new IllegalStateException("A successful write plan must be sealed before execution");
        // Reference arrays are private plan metadata; the SDK only reads them.
        Map<DocumentReference, DocumentSnapshot> documents = plan.references().length == 0
                ? Map.of() : HashMap.newHashMap(plan.references().length);
        if (plan.references().length != 0) {
            for (var document : transaction.getAll(plan.references(), plan.readMask()).get()) {
                documents.put(document.getReference(), document);
            }
        }
        for (var mutation : plan.mutations()) {
            if (mutation.kind() == Kind.CREATE && documents.get(mutation.reference()).exists()) {
                throw mutation.repository().duplicateValueException("id");
            }
        }
        if (plan.hasUniqueFields()) applyUniqueChanges(transaction, documents);
        for (var mutation : plan.mutations()) {
            switch (mutation.kind()) {
                case CREATE -> transaction.create(mutation.reference(), mutation.data());
                case SAVE -> transaction.set(mutation.reference(), mutation.data());
                case DELETE -> {
                    if (mutation.repository().uniqueFields().isEmpty() || documents.get(mutation.reference()).exists()) {
                        transaction.delete(mutation.reference());
                    }
                }
            }
        }
    }

    private void applyUniqueChanges(Transaction transaction, Map<DocumentReference, DocumentSnapshot> documents)
            throws ExecutionException, InterruptedException {
        var reservations = uniqueValues.inTransaction(transaction);
        // Release changed previous claims before checking final claims, enabling atomic swaps/transfers.
        for (var mutation : plan.mutations()) {
            var repository = mutation.repository();
            var previous = documents.get(mutation.reference());
            if (!repository.uniqueFields().isEmpty() && previous.exists()) {
                var previousValues = repository.extractUniqueValues(previous.getData());
                // This private map is mutable. Unchanged claims still get their transactional
                // ownership check below, without scheduling a release only to cancel it later.
                previousValues.entrySet().removeIf(field -> field.getValue().equals(mutation.values().get(field.getKey())));
                reservations.replace(repository.uniqueScope(), mutation.reference().getId(),
                        previousValues, Map.of());
            }
        }
        for (var mutation : plan.mutations()) {
            var repository = mutation.repository();
            if (!repository.uniqueFields().isEmpty() && mutation.kind() != Kind.DELETE) {
                try {
                    reservations.replace(repository.uniqueScope(), mutation.reference().getId(), Map.of(), mutation.values());
                } catch (DuplicateUniqueValueException conflict) {
                    throw repository.duplicateValueException(conflict.getField());
                }
            }
        }
        // No SDK reads are allowed after this point.
        reservations.apply();

    }

}
