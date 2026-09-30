# 004 — API shape, error handling, and the store's honest capacity

**Date:** 2026-09-30
**Phase/milestone:** 1.3 / 1.4
**Status:** accepted

## Context

ROADMAP.md Phase 1 decisions #2, #3 and #4, plus the questions the scaffold's javadoc asks
directly: `byte[]` versus a wrapped `Key`, what `get` returns for a missing key, whether
`StorageException` should be unchecked, whether a zero-length key is legal, what a closed store
does, and how many keys actually fit in RAM.

## Decision

Keys are always `Key`, never `byte[]`. `get` returns `Optional<byte[]>`. `StorageException` stays
unchecked. Zero-length keys are rejected; zero-length values are legal. Any method on a closed
store throws `IllegalStateException`; `close` is idempotent. A `delete` of an absent key writes
nothing. The index never points at a tombstone.

## Why

**`Key` over `byte[]`, and this one is not a judgement call.** `byte[]` has identity equality in
Java, so `HashMap<byte[], _>` never finds anything put into it. The code looks completely
reasonable and fails silently, which makes it the single most common bug in hand-rolled Java KV
stores. `KeyDirTest.twoDistinctArraysWithSameBytes` is the regression test that will stop anyone
"optimising" `Key` away later.

The defensive copies in `Key.of` and `Key.toByteArray` cost two allocations per round-trip.
`Key.wrapNoCopy` and `Key.backingArray` exist as documented-unsafe escape hatches, and the split
adopted here is:

- `encode` uses `backingArray()`. This is the sanctioned use — a write path that only reads the
  bytes and never retains them.
- `decode` uses `Key.of()`, even though `wrapNoCopy` is *provably* safe there: the array is
  freshly allocated inside `decode`, filled once, and nothing else ever holds a reference. It is
  the exact case the javadoc names as legitimate.

Using `of()` anyway is deliberate. ROADMAP.md says to get Phase 1 green and come back in Phase 5
with a profiler, and the likely finding is that one array copy does not register next to a
syscall. Logged as a Phase 5 item so it is measured rather than guessed.

**`Optional<byte[]>` from `get`, keeping the scaffold's choice.** The case against is real: it
allocates a wrapper on the hottest path in the system, and high-performance stores return `null`
or fill a caller-supplied buffer for exactly that reason. But the cost needs to be sized honestly
before it decides anything. A `get` already allocates a `byte[]` for the value and performs a
positional read — hundreds of microseconds if the page is not cached. A 16-byte `Optional`, which
escape analysis will frequently eliminate outright, is noise against that. Meanwhile `null` for
"absent" is indistinguishable from `null` for "I have a bug", and every caller of this interface
is code that does not exist yet (Phase 2's gRPC adapter, Phase 3's state machine). Clarity wins
until a benchmark says otherwise.

Note what `get` deliberately cannot express: never-written, deleted, and expired are all "empty".
A store wanting "has this key ever existed" would have to expose more, and it would have to keep
tombstones queryable rather than dropping them from the index.

**`StorageException` stays unchecked, agreeing with the scaffold.** Almost every failure here is
unrecoverable at the call site — if the segment file cannot be read, no caller of `get` has a
fallback. Checked exceptions would put a `catch` block with no sensible body into Raft's apply
loop in Phase 3. The layer that genuinely can act is Phase 2's gRPC adapter, mapping failures to
status codes, and it will catch `StorageException` explicitly. The cost is that the compiler stops
reminding anyone that I/O can fail; the mitigation is a single documented base type and saying so
on the interface.

**Zero-length keys rejected.** A zero-length key is almost always an uninitialised caller buffer
or an empty string that should have been caught upstream, and it is indistinguishable from "no key
supplied". Rejecting costs nothing and turns a silent oddity into an `IllegalArgumentException`.
Zero-length *values* stay legal and must remain distinguishable from a tombstone — that is a real
use (a key used as a set member) and `RecordCodecTest.emptyValueIsNotATombstone` pins it.

**A closed store throws `IllegalStateException`, and `close` is idempotent.** Returning empty from
a closed store would make shutdown look like data loss. This becomes a live race in Phase 2 where
the server's shutdown hook can run concurrently with in-flight requests, and a clear exception is
far easier to diagnose there than a mysteriously empty read.

**Deleting an absent key writes nothing and returns false.** The alternative — always append a
tombstone — means a loop of deletes over missing keys grows the log without bound while carrying
zero information. This makes `delete`'s on-disk effect depend on in-memory state, which is safe
because the index is derived from the log: if the key is absent from the index, no live record for
it exists in the log, so there is nothing for a tombstone to shadow.

**The index never points at a tombstone.** `delete` removes the entry outright. So a `get` never
spends a disk read only to discover the record is dead, and `BitcaskStore.get` is an index lookup,
one positional read, and a decode. Expiry is different and stays a read-time check, because a
record can be indexed while live and become expired later without anything writing to the log.

## The honest capacity limit

Bitcask keeps every live key in RAM forever, so this number *is* the design's ceiling. Per live
key, 64-byte keys, 64-bit JVM with compressed oops (heap under 32GB):

| Component | Bytes |
|---|---|
| `Key` object — header 12, `byte[]` ref 4, cached `hash` 4, aligned | 24 |
| the key's `byte[64]` — header 12, length 4, 64 data | 80 |
| `ValueLocation` — header 12, `int` 4, `long` 8, `int` 4, `long` 8, aligned | 40 |
| `HashMap.Node` — header 12, `hash` 4, three refs at 4 | 32 |
| table slot amortised at 0.75 load factor | 5 |
| **Total per key** | **~181** |

- **4GB heap:** ~23M keys theoretically, ~16M in practice once GC headroom and in-flight values
  are accounted for.
- **100M keys:** ~18GB of index alone, so ~27GB of heap to run comfortably — and above 32GB
  compressed oops switch off, every reference becomes 8 bytes, and the per-key figure jumps by
  roughly 20%.

This is why `maxValueBytes` defaults to 16MB rather than something larger: a value is fully
buffered in heap during both encode and decode, so it sets the per-operation heap spike, and
16MB × concurrent writers is the real transient ceiling. It also keeps at least four records in a
64MB segment, which keeps rollover meaningful.

Phase 4's hint files change the *reopen time* for this index, not its size. Nothing in this design
makes 100M keys cheap; the fix is a different index (an LSM tree keeps keys on disk), which is the
stretch goal, and being able to say why is the point of computing this.

## Options considered

- **A — `null` return from `get`.** Faster, conventional in high-performance stores. Deferred to
  a Phase 5 benchmark rather than rejected.
- **B — Checked `StorageException`.** Rejected: no useful handler at most call sites.
- **C — Allow zero-length keys.** Harmless in principle. Rejected as more likely to mask a caller
  bug than to serve a real use.
- **D — Always append a tombstone on delete.** Simpler to reason about (`delete` is then a pure
  function of its argument). Rejected for the unbounded-growth reason above.

## Consequences

**Easy:** the `byte[]` equality trap is structurally impossible; absent-versus-bug is
unambiguous; shutdown races produce a clear exception; the store's capacity is a number that can
be quoted rather than guessed at.

**Harder:** one `Optional` and one array copy per read that a profiler may later want back;
`keys()` materialises the whole key set, which is fine at Phase 1 scale and is why Phase 2's
`Scan` RPC is server-streaming instead.

**Revisit when:** Phase 5, with a profiler, for `Optional` (option A) and `Key.wrapNoCopy` in
`decode`.