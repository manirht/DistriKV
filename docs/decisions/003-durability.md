# 003 — Durability: sync policy, fsync scope, and the data-directory lock

**Date:** 2026-09-30
**Phase/milestone:** 1.2 / 1.4
**Status:** accepted

## Context

ROADMAP.md Phase 1 decision #1: what does this store actually promise a caller when `put`
returns? `StoreConfig.SyncPolicy` offers `EVERY_WRITE`, `INTERVAL` and `NEVER`, and the honest
answer differs for each. Separately, `LogSegment.sync` has to choose between `force(false)` and
`force(true)`, and new segment files raise the directory-fsync question.

## Decision

`EVERY_WRITE` is the default. `LogSegment.sync` uses `force(true)`. Creating a segment file
fsyncs the containing directory. A store holds an exclusive `FileLock` on `<dataDir>/LOCK` for
its whole lifetime.

### What each policy promises, stated plainly

| Policy | Promise when `put` returns | Loses on power loss | Loses on `kill -9` |
|---|---|---|---|
| `EVERY_WRITE` | the write is on the physical device | nothing | nothing |
| `INTERVAL` | the write is in the OS page cache | up to `syncInterval` of acknowledged writes | nothing |
| `NEVER` | the write is in the OS page cache | everything not yet flushed by the OS | nothing |

The `kill -9` column is the one that surprises people and it is worth being exact about: SIGKILL
destroys the *process*, while the page cache belongs to the *kernel*, which keeps running. So
every policy survives `kill -9`, and Phase 1's crash tests pass even under `NEVER`. Those tests
prove the recovery path handles a torn tail; they do not prove durability. Genuine power-loss
testing needs a VM that can be hard-reset or a fault-injecting filesystem, and is out of scope
for Phase 1.

## Why

**`EVERY_WRITE` is the default because a store that claims crash-safety in its README should be
crash-safe when you run it unconfigured.** The cost is real and, now that it has been measured,
worse than this document originally guessed. The first draft said "hundreds of microseconds,
capping throughput in the low thousands of writes per second". The crash harness actually
sustains **267 writes/s** on this machine — about **3.75 ms per `force(true)`** on APFS over
internal NVMe, an order of magnitude slower than estimated. See
[007](007-phase1-baseline-measurements.md).

That gap matters more than the absolute number: it means an unconfigured store is ~1,700x slower
than the same store under `NEVER` (460 K writes/s), so the default is a genuinely expensive
choice rather than a mildly conservative one. It stays the default anyway — an unconfigured store
should keep the promise the README makes, and a user who wants throughput can choose `INTERVAL`
knowingly. But it does move group commit (option D) from "nice Phase 5 optimisation" to the
single highest-value change available to the write path.

**`force(true)`, not `force(false)`, because we only ever append.** `force(false)` flushes file
contents but not necessarily file metadata, and the file *length* is metadata. After a crash,
recovery walks the file to its end — so a stale length means the tail records are invisible, and
those are precisely the records that were most recently acknowledged. Paying for a metadata flush
to avoid losing acknowledged writes is not a close call. It does mean `EVERY_WRITE` costs two
device round-trips on some filesystems rather than one; that is the price of the promise in the
table above.

**Creating a segment fsyncs the directory, because a file's existence is directory metadata.**
`force()` on the file itself says nothing about whether the directory entry pointing at it
survived. This barely matters in Phase 1, where losing a brand-new empty segment costs nothing.
It matters enormously at milestone 4.2, where compaction writes a merged segment and then deletes
its inputs: if the merged file's directory entry is lost after the inputs are gone, the data is
gone permanently. Doing it correctly from the start means that code path is already right when it
becomes dangerous.

**The `LOCK` file exists because `createActive` refusing to clobber is not enough.** That check
stops a second process from truncating segment 1, but a second process would simply discover the
existing segments, replay them, and open the *next* id as its own active segment. Now two writers
are appending to one logical store with two independent in-memory indexes, each unaware of the
other's writes. Every read is a coin flip and the damage is silent. An OS-level advisory lock
turns that into an immediate, obvious failure at `open`. This is what real databases do, and it
costs about fifteen lines.

## Options considered

- **A — `INTERVAL` as the default,** which is what most production systems actually run. Rejected
  as a *default* because it makes the out-of-the-box behaviour weaker than the documentation, but
  it remains the setting to reach for under real write load, and the promise ("we may lose up to
  100ms of acknowledged writes") is stated above so it can be quoted honestly.
- **B — `force(false)` plus an explicit `force(true)` only at `close`.** Cheaper per write.
  Rejected: it makes `EVERY_WRITE` a lie in exactly the scenario the policy exists for.
- **C — No lock file, rely on `createActive` failing.** Rejected above.
- **D — Group commit / batched fsync.** One fsync amortised across several concurrent writes,
  which is how real systems get both durability and throughput. Deferred to Phase 5, where there
  will be a benchmark to prove it helps. It is an optimisation of `EVERY_WRITE`, not a change to
  its promise.

## Consequences

**Easy:** the default configuration keeps the promise the README makes; the directory-fsync path
is already correct before Phase 4 makes it load-bearing; two JVMs on one data directory fail
immediately instead of corrupting each other.

**Harder:** default write throughput is bounded by device fsync latency, not by the code — so
Phase 1 benchmark numbers will look unimpressive, and that is correct rather than a bug to fix.
`sync()` is called while the write lock is held (see 006), so readers stall for the duration of
an fsync under `EVERY_WRITE`.

**Revisit when:** Phase 5 benchmarking, for group commit (option D) and for whether
`force(true)`'s second round-trip is measurable on the target device.