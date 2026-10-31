# Atomic multi-document repository writes

`FirestoreRepository.writeTransaction` lets a child repository combine mutations
from one or more repositories in one Firestore transaction. It preserves the
shared unique-value protocol instead of exposing raw transaction writes that
could bypass reservations.

Collections start empty and every writer uses the same canonical field mappings
and reservation protocol. Plans work immediately without administrative preparation,
readiness markers, or legacy-data scans. See [Pagination](pagination.md).

## Concrete user example

`UsuarioFirestoreRepository.cadastrarTodos` is a production example, also exposed
by the domain's `UsuarioRepository` contract:

```java
@Override
public CompletableFuture<Collection<Usuario>> cadastrarTodos(Collection<Usuario> usuarios) {
    Objects.requireNonNull(usuarios);
    return writeTransaction(writes -> usuarios.stream()
            .map(usuario -> writes.create(this, usuario))
            .toList());
}
```

Usage:

```java
UsuarioRepository users = new UsuarioFirestoreRepository(firestore);
users.cadastrarTodos(List.of(firstUser, secondUser))
        .thenAccept(created -> System.out.println("Created: " + created.size()));
```

If either email or phone conflicts, the entire group fails: no new user or
reservation is persisted.

For a child method that creates missing documents or replaces existing ones,
use `writes.save` in the same callback. The compiled example also provides:

```java
public CompletableFuture<Registration> saveRegistration(
        Usuario user, CategoriaExemploRepository categories, CategoriaExemplo category) {
    return writeTransaction(writes -> new Registration(
            writes.save(this, user), writes.save(categories, category)));
}
```

The same operation can mix `create`, `save`, and `delete`. Uniqueness is checked
for every final value, and any conflict rejects all documents and reservations.
`save` is a complete replacement/upsert; omitted fields are removed, so the
mapper must provide the intended full document.

## Multiple repositories

The compiled example `RegistrationExampleRepository`, in
`data/src/test/java/br/ufms/jogai/data/exemplo`, extends the user repository and
combines a user with a category:

```java
public CompletableFuture<Registration> register(
        Usuario user, CategoriaExemploRepository categories, CategoriaExemplo category) {
    return writeTransaction(writes -> new Registration(
            writes.create(this, user),
            writes.create(categories, category)));
}
```

`Registration` is a record containing the captured user and category. The category
repository has its own unique code; all three unique values and both entity
documents share one commit. Repositories must be constructed with the same
Firestore client instance. The example stays in test sources to avoid adding a
new category feature to the production domain.

## Supported plan operations

- `writes.create(repository, entity)` rejects an existing document ID.
- `writes.save(repository, entity)` creates or fully replaces a document.
- `writes.delete(repository, id)` deletes an existing document and its owned claims.

Create and save return reconstructed entities with captured values. The planner's
return value becomes the future's result only after a successful commit.
A missing document deletion is a no-op.

## Collections and subcollections

Construct a repository with the exact `CollectionReference` it owns. The overload
`super(collection, Set.of("email"))` uses the full collection path as its unique
scope, including all parent document IDs. Thus identically named subcollections
under different parents have independent uniqueness constraints. Within each
collection, different document IDs cannot own the same configured value.

For example, the compiled category repository now accepts a collection reference:

```java
public CategoriaExemploRepository(CollectionReference collection) {
    super(collection, Set.of("codigo"));
}
```

The existing registration example can create a root user and a nested category:

```java
var users = new RegistrationExampleRepository(firestore);
var categories = new CategoriaExemploRepository(firestore.collection("usuarios")
        .document(user.getId().toString()).collection("categorias"));
users.register(user, categories, category).get();
users.removeRegistration(user.getId(), categories, category.getId()).get();
```

Both methods are compiled in test sources. `removeRegistration` plans the known
parent and child deletions, along with their unique reservations, in one commit:

```java
return writeTransaction(writes -> {
    writes.delete(this, userId);
    writes.delete(categories, categoryId);
    return null;
});
```

Other child methods can mix `writes.create`, `writes.save`, and `writes.delete`
for arbitrary participating repositories, including more deeply nested collections.
All repositories must use the same Firestore client. Deleting a parent alone
does not delete descendants; explicitly plan every known child deletion that
must belong to the atomic operation. There is no recursive collection scan or
automatic split into separate commits.

An entity ID is always one document name, not a relative path. For example,
`parent/children/id` is rejected by CRUD methods, transaction deletes, and page
cursors. Use the subcollection's repository with ID `id` instead. Otherwise,
two distinct paths sharing a leaf ID could be mistaken for the same reservation
owner. Explicit custom scopes remain supported but must belong to one collection.

The planning callback runs **once, synchronously**, on its creating thread.
Entities are captured before the asynchronous database operation starts. The plan
is compiled and sealed when the callback succeeds; failed planning is discarded. Mutating a sealed plan, or building it from
another thread, is rejected. Empty plans complete without database access.

Do not call `repository.create/save/delete` or launch asynchronous tasks inside
the planner: those are independent operations, not members of this plan. Use the
`writes` methods directly. This API plans writes from supplied entity snapshots;
it does not expose arbitrary transaction reads or implement version-based
compare-and-set updates. Full replacements retain their usual last-committer
semantics for non-unique fields.

## Execution and retries

Every Firestore callback attempt creates fresh snapshots and reservation state:

1. Read required entity documents together, projecting only configured unique fields. Unconstrained
   save/delete operations need no prior document read; creates retain the ID check.
2. Reject IDs that already exist for planned creates.
3. Extract previous unique values directly from persisted canonical fields.
4. Prepare release of changed previous unique values; unchanged values retain their owner.
5. Verify and reserve every participating document's final unique values.
6. After all reads finish, schedule reservations and entity mutations.
7. Commit all writes together, or apply none of them.

Retries repeat only the database execution, not the user planning callback. No
read snapshots or pending ownership decisions are retained across attempts.
The projected entity snapshots are never used as replacement data; writes use the
complete maps captured during planning. All operations in an attempt share one reservation operation, so duplicate final
values in different documents of the same plan are rejected before commit.

## Swaps and transfers

The final state must be unique. A transaction can swap two users' emails/phones
or delete an old owner while creating another owner with the released values:

```java
return writeTransaction(writes -> {
    writes.delete(this, previousUser.getId());
    return writes.create(this, replacementUser);
});
```

The replacement may be queued before the deletion: planning order does not
change the final-state check. A transfer is allowed only after the operation has
verified and prepared release by the current owner. A pending new owner cannot
be erased by a repeated stale release from the former owner.

Creating two users with the same email or phone is still invalid, even if both
writes occur in a single transaction. Standalone and multi-document writes use
the same constrained transaction machinery and can safely compete.

## Configuration and lifecycle guards

A plan rejects:

- More than one mutation for the same document; combine desired edits in the entity first.
- Different Firestore client instances.
- Different scope/field configurations for the same collection.
- Reuse of a unique scope by different participating collections.
- A mapper that reconstructs a different document ID or returns null.

Scopes must remain exclusive and stable across the application's writers.
The guards above validate participating repositories; they do not prevent direct
privileged writes elsewhere from ignoring the protocol. Native Firestore limits
still apply. Plans are never silently split into smaller commits, since that
would break atomicity. Legacy data without reservations is outside this contract.

## Audit fixes

- Optional null fields were incorrectly passed to `Map.copyOf`, which rejects
  nulls. They now use an unmodifiable defensive HashMap snapshot; non-null maps
  keep the existing immutable-map reuse optimization.
- The Firebase credential loader used filesystem paths for classpath resources,
  which fails for JAR URLs. It now loads a stream and initializes the shared
  client lazily. Resource-loading tests use synthetic files and JARs, with no
  real credentials or Firebase application initialization.
- Captured results now verify that the mapper preserves the requested ID.

## Tests

`RepositoryWriteTransactionTest` verifies complete persisted user/category state
and exact reservation ownership after each scenario. The tests cover:

- Duplicated IDs and unique fields within a plan, and conflicts with stored documents.
- Successful cross-collection commits and rollback on mapping, planning, uniqueness,
  or server commit failures.
- Atomic swaps, deletion with value reuse, stale releases, and incompatible repositories.
- Sealing, single planning-callback execution, and rejection of cross-thread plan mutation.
- Eight simultaneous two-user groups competing for one email, with exactly one
  complete winner and no partial companion users or orphan reservations.
- Concurrent replacements of the same two users, retaining one complete final version.
- Concurrent standalone and group writes, and concurrent deletion/value reuse.
- Query observers that sample only complete groups while independent batches commit.

Concurrent contenders use executor threads and start gates outside the SDK
transaction. Gates never hold database locks while waiting for another contender.
`SubcollectionTransactionTest` also checks three-level creation/deletion,
independent scopes under different parents, nested-conflict rollback, transfers,
concurrent cross-collection plans, and rejection of path-valued IDs.
These emulator tests strengthen regression coverage; they are not a
production throughput benchmark or a formal proof for every possible schedule.

```sh
FIRESTORE_EMULATOR_HOST=127.0.0.1:8185 ./gradlew test :data:javadoc
```

Sealing releases the builder-only maps and stores immutable request metadata for
retries. This does not cache snapshots or ownership decisions. A forced-abort
regression test validates execution of the sealed plan in a fresh attempt. The
public API and child-repository examples above remain unchanged.
