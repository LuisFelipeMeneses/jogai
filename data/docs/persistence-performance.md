# Persistence performance and correctness

The following figures describe logical document accesses in this implementation,
not a production latency benchmark or a complete billing estimate. They assume
successful operations, an existing initialization marker, two unique fields
(email and phone), and intact reservations. Transaction retries, empty queries,
index-entry reads, and initialization can change actual usage.

| Operation | Document reads | Document writes | Document deletes |
| --- | ---: | ---: | ---: |
| Create with two unique values | 4 | 3 | 0 |
| Save with unchanged unique values | 4 | 1 | 0 |
| Change one unique value | 5 | 2 | 1 |
| Change both unique values | 6 | 3 | 2 |
| Delete with two unique values | 3 | 0 | 3 |
| Create or save without unique fields | 0 | 1 | 0 |
| Delete without unique fields | 0 | 0 | 1 |
| Read an entity by ID | 1 | 0 | 0 |

## Avoiding redundant writes

A transactional read still verifies every current unique value, even if the
entity's value did not change. An existing reservation owned by the entity is
not written again. This keeps conflict detection and recreates missing
reservations without rewriting valid ones. Updating only a user's name writes
one document instead of rewriting the user and both reservations.

The initialization marker is read inside each constrained save transaction.
It is not replaced with a process-local cache, which could miss database changes.
The first constrained write scans existing documents and prepares reservations
atomically. That operation scales with collection size and unique-field count;
large existing datasets require a planned migration. Normal steady-state writes
access only specific documents, independently of the total entity count.

## Transactional batch reads

`Operation.replace` now fetches its uncached current and changed previous
reservation documents with one transactional `getAll` call. It deduplicates
references and skips documents already read in that callback attempt. A field
mask returns only `entidadeId`, the field needed for ownership verification.
Document identity and existence remain available, so a present document without
an owner is still rejected rather than treated as an available value.

This reduces sequential reservation-read calls and transmitted fields, not the
number of documents read in the table above. For two unique fields, a normal
create or unchanged-value save fetches the two reservation documents together;
changing both fields fetches up to four reservation documents together. The
entity and initialization-marker reads remain separate. Initialization uses the
same batching for each existing entity, rather than building an unbounded batch
for the entire collection.

The temporary reference set is bounded by the fields involved in that replace
call and exists only within one transaction attempt. Projected snapshots remain
in the attempt-local cache. Nothing is cached across transactions or on the
basis of a previous process's ownership decision. Transactional conflict checks
cover all fetched documents, including missing reservation documents.

All ownership checks still precede all writes. The batch's response order is
irrelevant: snapshots are associated by their document references. The commit,
rollback, pending-owner conflict detection, and release rules are unchanged.
No production latency or heap-allocation improvement percentage is claimed.

## Captured data and memory

The domain remains responsible for canonical values. Writes capture those values
once before the asynchronous operation starts and reconstruct an independent
result entity before committing. This reconstruction is intentional: it avoids
returning the caller's mutable entity and detects mapping failures before a
write can commit.

For non-null document values, `Map.copyOf` provides an immutable snapshot and can
reuse immutable maps supplied by mappers. Mutable source maps are defensively
copied. Optional null values are supported with an unmodifiable defensive
`HashMap` snapshot, because `Map.copyOf` rejects nulls. These are shallow snapshots;
entity mappers must still detach any mutable nested values as documented by the
mapping contract.

Extracted unique values use a single private map without a second immutable copy.
Repositories without constraints return an empty shared map instead. Reservation
key encoding reuses a Base64 encoder and avoids streams on the per-value path.
Read snapshots are cached only within each transaction attempt; there is no
unbounded global document or value cache.

## Reads and large results

Reading by ID reconstructs one entity. `queryOne` requests `limit(1)` and handles
that result directly without building a stream or intermediate Optional.
Collection queries reconstruct one entity per returned document.

`getAll()` intentionally materializes the entire collection; application memory
therefore grows with the number and size of its documents. For large datasets,
subclasses should use the existing query helper with a limit and a cursor:
`collection.orderBy(...).startAfter(lastDocument).limit(pageSize)`.
This is a bounded query, not automatic pagination of `getAll()`.

## Validation

The integration suite exercises both constrained and unconstrained repositories,
concurrent writes, rollback, mapping snapshots, optional nulls, and legacy data.
Additional regression tests compare persisted reservation update timestamps to
verify that unchanged reservations are not rewritten. A missing-reservation test
ensures this optimization does not skip ownership verification and repair.
The batched-read regression tests also cover a missing owner field on an existing
reservation and six simultaneous claims for one unique email; only one claim may
succeed and no losing claim may leave an orphan reservation.

Run against the local emulator:

```sh
FIRESTORE_EMULATOR_HOST=127.0.0.1:8185 ./gradlew test :data:javadoc
```

No production database or production latency measurement is involved in these tests.
