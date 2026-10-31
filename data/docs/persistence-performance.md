# Persistence performance and correctness

The following figures describe logical document accesses in this implementation,
not a production latency benchmark or a complete billing estimate. They assume
successful operations, two unique fields
(email and phone), and intact reservations. Transaction retries, empty queries,
and index-entry reads can change actual usage.

| Operation | Document reads | Document writes | Document deletes |
| --- | ---: | ---: | ---: |
| Create with two unique values | 3 | 3 | 0 |
| Save with unchanged unique values | 3 | 1 | 0 |
| Change one unique value | 4 | 2 | 1 |
| Change both unique values | 5 | 3 | 2 |
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

Unchanged values are no longer released in memory and then reserved again. The
release phase retains only changed previous values (all previous values on delete).
The final-value phase still transactionally verifies every desired reservation,
including unchanged values. This removes temporary pending deletions, repeated
reservation-key construction, and reference-set work without skipping ownership
checks. Another document in the same plan still cannot claim an unchanged value.

Collections start empty and all writers use the same reservation protocol. There
is no initialization API, marker read, or legacy-data scan. First writes operate
exactly like subsequent writes and access only specific documents, independently
of the total entity count. Removing the readiness marker eliminates one logical
document read per constrained standalone operation; multi-document plans no longer
read a marker per participating constrained collection. Ownership checks remain
transactional and are never replaced by process-local caches.

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
the write-plan execution groups all old reservation reads and then groups new
uncached reservation reads. Required entity documents are fetched together in the
transaction header. No control documents or collection queries are involved.

The entity-document header now requests only the union of the participating
repositories' unique fields, using a compiled `FieldMask`. Non-unique payloads,
dates, names, and nested objects are not transferred just to determine previous
unique values. Unconstrained creates use an empty mask for their ID-existence
check. Projection preserves document existence and transactional conflict checks;
it changes returned fields, not which document is read. Literal top-level field
names containing dots use `FieldPath.of`, keeping them separate from nested paths.
The mask is compiled once per plan and reused across attempts.

This reduces transferred bytes and snapshot materialization; logical document
read counts in the table remain unchanged. Reservation reads are still grouped
per `replace`, not into one global request for the entire plan, so sequential
read calls can grow with the number of changed documents. No measured production
latency or billing reduction is claimed for field projection.

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

Previous unique values are extracted directly from the stored canonical fields.
Updates no longer reconstruct and map the old entity merely to obtain these
values. Deletion does not reconstruct any entity. The database is assumed to
have been written consistently from the beginning; there is no legacy-value
normalization or migration path.

Because reads project only the necessary fields, extracting previous unique
values also avoids converting unrelated stored payloads into a Java map. Full
replacement writes still use the complete captured entity map, never the projected
snapshot. A regression test uses a large payload plus both a literal dotted field
and a similarly named nested field to verify preservation and correct uniqueness.

Unconstrained standalone `save` no longer constructs an exception-translation
future stage used only by `create`; storage failures continue to propagate.

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

`getAll()` executes one collection query without a page-size limit and returns
all direct documents as entities, including results beyond `MAX_PAGE_SIZE`.
It does not traverse subcollections. Read volume and memory usage grow with the
complete collection; it returns an empty collection when no documents exist.
The unbounded protected query helper remains removed.
`getPage(pageSize, afterId)` orders by document ID; pageSize must be 1–200. It
fetches at most pageSize + 1 documents to detect continuation, reconstructs only
the returned entities, and exposes the last returned ID as an exclusive cursor
when another document exists. There are no offset reads. Memory per page is
bounded by page size and document size; accumulating all pages remains a caller choice.

Filtered queries use `queryPage(query, pageSize, afterId)` with the same bound.
After the first page, this helper reads the cursor document once to supply the
ordered snapshot cursor. It fails explicitly if that document was deleted. The
base ID-ordered method does not need that extra read and tolerates cursor deletion.
Pagination is not a frozen snapshot under concurrent updates. The extra look-ahead
document may be read again on the next page. See
[Pagination](pagination.md).

## Validation

The integration suite exercises both constrained and unconstrained repositories,
concurrent writes from an empty database, rollback, mapping snapshots, and optional nulls.
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

## Multi-document execution

Constrained standalone operations now share the `RepositoryWriteTransaction`
engine with multi-document plans. Planning rejects duplicate document mutations,
so header references are collected directly in an array-backed list, without a
second hash set and its per-document entries. Changed old claims are
released in memory before final claims are checked, permitting atomic transfers
and swaps without rewriting unchanged reservations. Read caches and pending
state are recreated for every transaction attempt. Captured entity data is
reused across attempts, without rerunning the planning callback.

Explicit unconstrained multi-document plans use a native transaction to preserve
atomicity; unconstrained standalone operations retain their direct-operation
fast path. No plan is automatically chunked into non-atomic commits.

## Compiled plans and write-only batches

Successful planning now compiles an immutable list of mutations, the unique
header references, the read mask, and whether unique constraints are involved once. These values depend only
on the captured request and repository configuration; retries reuse them without
rebuilding the reference set. The mutable planning maps for
mutations, collection configurations, and scope ownership are released after
sealing, rather than remaining reachable throughout an asynchronous commit.
Each collection's configuration is captured once instead of allocating another
configuration record for every document in that collection.

The per-attempt document map is pre-sized. Each attempt still reads current
snapshots and creates a fresh reservation operation; no cached database data or
ownership decision crosses an attempt boundary. Failed planning discards the
captured builder state instead of compiling a plan that will never execute.

Explicit unconstrained `save`/`delete` mutations no longer request their documents
in the transaction header. A plan consisting only of these mutations performs no
document reads and does not construct reservation state; native begin/commit
calls still provide atomicity. Unconstrained `create` retains its ID-existence
read, and all constrained mutations retain their required reads. A missing
unconstrained document deletion is sent as an idempotent delete, matching the
standalone delete behavior, instead of first reading whether it exists.

New tests force a retryable abort after scheduling a complete plan and verify
that the second attempt succeeds with correct reservations. Another test stages
a native write before executing an unconstrained save/delete plan: the SDK would
reject any subsequent read, so success verifies the zero-read fast path. Mixed
plans also verify that an unrelated unconstrained write cannot escape rollback
when a constrained document conflicts. No measured heap or production-latency
improvement percentage is claimed; these are structural allocation and request
reductions, with emulator correctness checks.
