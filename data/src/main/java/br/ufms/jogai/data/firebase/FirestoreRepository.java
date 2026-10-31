package br.ufms.jogai.data.firebase;

import br.ufms.jogai.domain.shared.Entidade;
import br.ufms.jogai.domain.shared.Repository;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutureCallback;
import com.google.api.core.ApiFutures;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

public abstract class FirestoreRepository<E extends Entidade<ID>, ID extends Serializable>
        implements Repository<E, ID> {

    protected final Firestore db;

    protected final CollectionReference collection;

    protected FirestoreRepository(CollectionReference collection) {
        this.collection = Objects.requireNonNull(collection);
        this.db = collection.getFirestore();
    }

    @Override
    public CompletableFuture<E> save(E entity) {
        Objects.requireNonNull(entity, "A entidade não pode ser nula");
        ID id = Objects.requireNonNull(entity.getId(), "A entidade deve possuir um identificador");

        DocumentReference docRef = collection.document(idToStr(id));

        return toCompletableFuture(docRef.set(entityToMap(entity)))
                .thenApply(result -> entity);
    }

    protected DocumentReference save(Transaction transaction, E entity) {
        Objects.requireNonNull(transaction, "A transação não pode ser nula");
        Objects.requireNonNull(entity, "A entidade não pode ser nula");

        ID id = Objects.requireNonNull(entity.getId(), "A entidade deve possuir um identificador");

        DocumentReference docRef = collection.document(idToStr(id));

        transaction.set(docRef, entityToMap(entity));

        return docRef;
    }

    @Override
    public CompletableFuture<Void> delete(ID id) {
        Objects.requireNonNull(id, "O id não pode ser nulo");

        DocumentReference docRef = collection.document(idToStr(id));

        return toCompletableFuture(docRef.delete())
                .thenApply(result -> null);
    }

    protected void delete(Transaction transaction, ID id) {
        Objects.requireNonNull(transaction, "A transação não pode ser nula");
        Objects.requireNonNull(id, "O id não pode ser nulo");

        transaction.delete(collection.document(idToStr(id)));
    }

    protected CompletableFuture<Optional<E>> get(DocumentReference docRef) {
        Objects.requireNonNull(docRef, "A referência ao documento não pode ser nula");

        return toCompletableFuture(docRef.get())
                .thenApply(document -> {
                    if (!document.exists()) {
                        return Optional.empty();
                    }
                    return Optional.of(documentToEntity(document));
                });
    }

    @Override
    public CompletableFuture<Optional<E>> get(ID id) {
        Objects.requireNonNull(id, "O id não pode ser nulo");

        return get(collection.document(idToStr(id)));
    }

    @Override
    public CompletableFuture<Collection<E>> getAll() {
        return query(collection);
    }

    protected CompletableFuture<Collection<E>> query(Query query) {
        Objects.requireNonNull(query, "A query não pode ser nula");

        return toCompletableFuture(query.get())
                .thenApply(this::documentsToEntities);
    }

    protected CompletableFuture<Optional<E>> queryOne(Query query) {
        Objects.requireNonNull(query, "A query não pode ser nula");

        return toCompletableFuture(query.limit(1).get())
                .thenApply(snapshot -> snapshot.getDocuments()
                        .stream()
                        .findFirst()
                        .map(this::documentToEntity));
    }

    protected Collection<E> documentsToEntities(QuerySnapshot snapshot) {
        return snapshot.getDocuments()
                .stream()
                .map(this::documentToEntity)
                .toList();
    }

    protected static UUID idToUUID(String id) {
        return UUID.fromString(id);
    }

    protected String idToStr(ID id) {
        return String.valueOf(Objects.requireNonNull(id));
    }

    protected static Timestamp toTimestamp(Instant instant) {
        Objects.requireNonNull(instant, "O instant não pode ser nulo");

        return Timestamp.ofTimeSecondsAndNanos(instant.getEpochSecond(), instant.getNano());
    }

    protected static Timestamp toTimestamp(LocalDate date) {
        return toTimestamp(date.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    protected static Instant toInstant(Timestamp timestamp) {
        Objects.requireNonNull(timestamp, "O timestamp não pode ser nulo");

        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    protected static LocalDate toLocalDate(Timestamp timestamp) {
        return toInstant(timestamp).atZone(ZoneOffset.UTC).toLocalDate();
    }

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

    protected abstract Map<String, Object> entityToMap(E entity);

    protected abstract E documentToEntity(DocumentSnapshot document);
}
