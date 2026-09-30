# 006 — Concurrency: one writer, N readers, and the gap between them

**Date:** 2026-09-30
**Phase/milestone:** 1.7
**Status:** accepted

## Context

Milestone 1.7 asks for safety under N reader threads and one writer. The scaffold's javadoc is
explicit that the interesting question is *not* which lock class to use: a `put` must append to
the log **and** update the index, and those are not one atomic operation. A
`ConcurrentHashMap` makes each individual map operation safe while doing nothing at all about
that gap.

## Decision

One `ReentrantReadWriteLock` per store. Writers (`put`, `delete`) hold the write lock across
validate → append → fsync → index update. Readers hold the read lock only for the index lookup
and segment resolution, then release it and perform the disk read outside the lock. `KeyDir`
stays a plain `HashMap`. Every segment stays open for the store's lifetime.

## Why

**The write lock spans append *and* index update because the gap between them is the whole
problem.** If the index were updated outside the lock, two writers could append records A then B
for one key and update the index in the order B then A, leaving the index pointing at the older
record — a lost write with no error anywhere. Holding one lock across both steps makes the pair
atomic with respect to other writers and to readers.

**The ordering inside the lock is append → fsync → index, and it is not arbitrary.** Making the
index visible before the data is durable means a reader can be served a value that a subsequent
power loss destroys, after `put` already returned success. Index-last is the entire discipline of
write-ahead logging, and it is the same principle Raft's persistent state needs at milestone 3.1.

**Readers do their disk I/O outside the lock, which is safe for three specific reasons** — worth
enumerating because the safety comes from the design, not from the lock:

1. **Written bytes are immutable.** The log is append-only, so once a record's bytes are at an
   offset, nothing ever rewrites them. A `ValueLocation` obtained under the read lock stays valid
   indefinitely.
2. **Every segment stays open.** The hazard the scaffold names — a reader holding a
   `ValueLocation` while a rollover happens, then finding its segment closed — is removed by never
   closing a segment until the store closes. Reads have to span segments anyway
   (`BitcaskStoreTest.readsSpanSegments`), so keeping them open is required regardless. The cost
   is one file descriptor per segment, which is a real limit worth noting: a store with tens of
   thousands of segments will exhaust the process fd limit, and Phase 4 compaction is what keeps
   that count down.
3. **Positional reads are thread-safe.** `FileChannel.read(ByteBuffer, position)` does not touch
   the channel's own position, so any number of threads can read one channel concurrently. This
   is why `LogSegment` uses the positional form from the start rather than seek-then-read — the
   seeking form would have made this milestone require a lock per segment.

**A concurrent delete during a reader's disk read is acceptable, not a bug.** The reader returns
a value that was live when it consulted the index. That is a legal linearisation — the read simply
ordered before the delete. Being precise about which invariant is actually being asserted is most
of concurrent testing, which is why `BitcaskStoreTest.concurrentWritersToOneKey` asserts "the
final value is one of the values written" rather than "the last one": with concurrent writers
there is no observer who can define *last*.

**`KeyDir` stays a plain `HashMap`, guarded by the store's lock.** Swapping in a
`ConcurrentHashMap` would make the map operations individually safe and leave the append/index
gap exactly as broken, while adding the illusion of having addressed it. The distinction between
a thread-safe data structure and a correct concurrent algorithm is the actual lesson here.
`KeyDir` therefore documents itself as not thread-safe and names the lock that protects it,
rather than pretending to be safe on its own.

**fsync happens inside the write lock, and this is the throughput cost of correctness.** Under
`EVERY_WRITE` that stalls every reader for hundreds of microseconds per write, because the read
lock cannot be acquired while the write lock is held. Moving the fsync outside would break the
index-last ordering above. The right fix is group commit — amortising one fsync across several
queued writes — and that belongs in Phase 5 where there is a benchmark to prove it helps. Until
then the cost is documented rather than optimised away on a hunch.

## Options considered

- **A — `ConcurrentHashMap` and no store-level lock.** Rejected above: addresses the visible
  symptom and not the actual race.
- **B — `synchronized` on every method.** Correct and simple, and it serialises readers against
  each other for no reason — reads are the common case and they contend on nothing but the index.
- **C — Hold the read lock across the disk read.** Simpler to justify, and it would make every
  read block the next write for the duration of a syscall. Unnecessary given immutability plus
  always-open segments.
- **D — Copy-on-write index snapshots, lock-free reads.** Faster reads still. Rejected for Phase
  1 as premature: it trades a well-understood lock for allocation pressure proportional to the
  write rate, with no measurement yet to justify it.

## Consequences

**Easy:** readers scale with cores on the I/O path; a `ValueLocation` can be held without
coordination; `LogSegment`'s positional-read API makes this nearly free.

**Harder:** one file descriptor per segment forever, so segment count is now an operational
limit; writes serialise against each other completely, so write throughput is one thread's worth
no matter the core count; readers stall during fsync.

**Revisit when:** Phase 5 benchmarking, for group commit (the fsync stall) and for whether reads
contend measurably on the read lock at all. Also Phase 4, which changes the immutability
assumption in reason 1 — compaction *deletes* segments, so a reader holding a `ValueLocation`
into a segment being merged needs either reference counting or a grace period before the file is
unlinked. That is the single most important consequence of this decision to carry forward.