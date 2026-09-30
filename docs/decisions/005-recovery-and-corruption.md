# 005 — Recovery: torn tails, real corruption, and what `open` does about each

**Date:** 2026-09-30
**Phase/milestone:** 1.5
**Status:** accepted

## Context

Milestone 1.5 is where the store either keeps its durability promise or quietly breaks it. Both a
crash and a failing disk surface identically — as a record that will not decode — and recovery has
to respond to them in opposite ways. `BitcaskStore.open` then has to decide what a caller sees.

## Decision

A decode failure at the very end of the highest-numbered segment is a torn tail: discard it,
truncate the file to the last good offset, report the byte count. A decode failure anywhere else
is corruption: `open` throws `CorruptRecordException` naming the file, the offset and the reason.
Segment id gaps are normal. Zero-byte segments are valid. Segments are replayed in ascending
`(segmentId, offset)` order, buffered in 1MB chunks.

## Why

**The two failures must be distinguished by position, because nothing in the bytes distinguishes
them.** A half-written record and a bit-rotted record both fail the CRC. What separates them is
that a crash can only ever tear the *last* write to the *active* segment — every earlier record
was followed by a successful append, which proves it was complete. So position is the evidence,
and the rule is mechanical: last record of the highest segment and at EOF means crash; anything
else means damage.

**A torn tail is discarded because it was never acknowledged.** The process died between the
`write` and the data reaching disk, so `put` never returned to any client. Dropping it loses
nothing that was promised. This is the normal case after `kill -9`, not an exotic one, and
recovery handles it without drama or warnings.

**The file is truncated rather than left in place, and this is a small decision with a large
consequence.** Leave the garbage and the next append lands after it, which puts a permanently
undecodable hole in the middle of a segment — and by the rule above, a bad record mid-segment
means every future recovery fails. The store would work once and then refuse to open forever.
`CrashRecoveryTest.writableAfterRecovery` is the test that catches skipping this.

**Corruption mid-segment throws, rather than skipping the record.** Skipping is the worst
available response: the previous record for that key is still in the log, so the key silently
reverts to an older value. No exception is raised, no log line is written, and no user can explain
why their data went backwards. That is a worse outcome than refusing to start, because a store
that will not open gets fixed and a store that lies does not.

**`open` throws rather than degrading to read-only.** Degraded mode is a legitimate design — real
stores offer all three of throw, degrade and quarantine under configuration — but it adds a mode
to reason about, with no caller in existence yet to choose it, and a half-usable store is the
hardest thing to operate. Throwing is the behaviour a caller can least misunderstand. The message
carries the file, the offset and which check failed, because these messages get read during a
crash that cannot be reproduced.

**Segment id gaps are normal, not corruption.** Phase 4 compaction merges segments and deletes
its inputs, so ids 1, 2, 5 is the expected steady state afterwards. `discoverSegments` must not
require contiguity — a store that demands it works today and refuses to open after the first
compaction.

**Zero-byte segments are valid.** They are produced by crashing after rollover creates the file
and before anything is appended, which is a real window. A zero-length file scans to zero records
and contributes nothing.

**Segments are sorted numerically, not lexicographically.** The zero-padded names make the two
agree, so this looks cosmetic; it is not. `Files.list` gives no ordering guarantee at all, and
relying on the padding is exactly the implicit coupling that breaks when someone reasonably
changes the naming later. Sorting `10.data` against `9.data` as strings puts them the wrong way
round, and the result is a store that loses its most recent writes while passing every other test.

**Replay buffers in 1MB chunks rather than one syscall per record.** At 1GB the difference is
minutes versus seconds, and Phase 1's exit criteria require measuring the reopen time — so the
difference is visible rather than theoretical. Records straddling a chunk boundary are handled by
compacting the buffer and refilling, which also means the buffer must be at least as large as the
largest possible record (`HEADER_BYTES + maxKeyBytes + maxValueBytes`) or a legal record could
never be assembled.

**A tombstone removes the key even if a newer value exists in a later segment — ordering saves
us.** Scanning ascending means the later value is applied after the tombstone and wins. This is
correct today and stops being correct the moment compaction reorders segments, which is why
`putIfNewer` compares timestamps explicitly rather than relying on the scan order alone. See 002
for the tie-breaking rule that comparison depends on.

## Options considered

- **A — Skip bad records and continue.** Rejected: silent data loss, above.
- **B — Degraded read-only mode on corruption.** Deferred, not rejected. It becomes worth having
  when there is an operator who would rather serve stale data than nothing; there is no such
  caller yet.
- **C — Quarantine the bad file and carry on.** Also silent data loss, with the added problem
  that a quarantined segment takes every key whose newest record lived in it back to an older
  value.
- **D — Trust the file length instead of walking records.** Faster, and wrong: it is exactly the
  metadata a crash can leave stale, which is why `LogSegment.sync` uses `force(true)` (see 003).

## Consequences

**Easy:** a `kill -9` is a non-event; real corruption is loud and names its location; the
post-compaction world of non-contiguous segment ids already works.

**Harder:** a single bad byte mid-segment makes the store refuse to open, with no built-in
recovery tool. That is the intended trade-off, but it means Phase 4 should add a repair path that
can rebuild from the surviving records with explicit operator consent. `ReplayResult` exists
precisely so recovery can be debugged from a log after a crash that will not reproduce —
`truncatedTailBytes` must be asserted zero in the clean-restart test and non-zero in the kill
test, or there is no evidence the torn-tail path ever runs.

**Revisit when:** milestone 4.2 (compaction, which invalidates the segment-order assumption) and
milestone 4.3 (hint files, which change how the index is rebuilt and must fall back to a full
replay when a hint file is itself corrupt).