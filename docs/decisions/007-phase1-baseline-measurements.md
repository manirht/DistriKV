# 007 — Phase 1 baseline measurements

**Date:** 2026-09-30
**Phase/milestone:** 1 (exit criteria)
**Status:** accepted

## Context

Two Phase 1 exit criteria are measurements rather than assertions, and both exist to be compared
against later: the 1GB reopen time is the "before" number that milestone 4.3's hint files have to
beat by 10x, and the write throughput is what the Phase 5 sync-policy curve is drawn against. A
measurement nobody wrote down cannot be improved on.

## Hardware and configuration

| | |
|---|---|
| Machine | Apple silicon (arm64), macOS 26.6.2, APFS on internal NVMe |
| JDK | Homebrew OpenJDK 25.0.3, compiled against the Java 25 toolchain |
| Heap | 2GB (`maxHeapSize` on the test task) |
| Sync policy | `NEVER` — see the caveat below |
| Segment size | 64MB |

## Reopen time for a 1GB store

Produced by `CrashRecoveryTest.Performance.measureReopenTime`, tagged `slow`:

```
./gradlew :distrikv-storage:test -PincludeSlow --tests '*CrashRecoveryTest$Performance*' --info
```

```
bytes written    : 1,073,742,348 (1.00 GB)
keys             : 1,003,598
value size       : 1,024 B
segments         : 17
write time       : 2,182 ms
REOPEN TIME      : 420 ms
replay rate      : 2,438 MB/s
per-key replay   : 0.42 us
```

**420 ms to rebuild a 1M-key index from 1GB of segments.** That is the number milestone 4.3 has
to beat.

### The caveat that makes this number smaller than it looks

The file was written moments before it was read, so all 1GB was in the OS page cache. 2.4 GB/s is
a memory-bandwidth figure, not a disk one — it is measuring the decode loop, not the storage
device. A genuinely cold reopen (after a reboot, or on a machine whose page cache has moved on)
is bounded by sequential read throughput instead, and on this hardware that would put a 1GB
replay in the region of 0.5–1.5 s rather than 0.42 s.

Both numbers are worth having and they answer different questions. The warm number says the
decode path costs ~0.42 µs per record, which is what hint files actually remove. The cold number
says what a user waits for. This measurement is the warm one; the cold one needs a cache-drop the
test harness does not currently do, and is worth adding before claiming a Phase 4 improvement,
because hint files change how many bytes are read and a warm-cache comparison would flatter them.

### Why this is the design's real weakness

The 420 ms is not the interesting cost. The index for 1M keys is roughly 150 MB of heap here
(~18-byte keys; see 004 for the per-key arithmetic), and every key that has ever been written and
not deleted costs that RAM for as long as the store is open. Reopen time is fixable with hint
files. The RAM is not fixable without changing the index, which is why an LSM tree is the stretch
goal rather than a nice-to-have.

## Write throughput

From the same run, and from `BitcaskStoreTest.Scale.hundredThousandKeys`:

| Workload | Policy | Result |
|---|---|---|
| 1M × 1KB values, sequential | `NEVER` | 1GB in 2,182 ms ≈ **460 K writes/s**, 492 MB/s |
| 100,200 keys, sizes 1B–1MB, put + get + delete | `NEVER` | 0.78 s for ~270 MB written and read back |

These are page-cache numbers and make no durability claim whatsoever. Under
`EVERY_WRITE` — the default, and the only policy that promises a `put` survives power loss — the
ceiling is set by device fsync latency, not by any of this code. The crash harness
(`CrashWriter`, which runs `EVERY_WRITE` because that is the promise it is testing) is the place
to read that number off, and drawing the full policy curve is a Phase 5 job.

The gap between those two columns *is* the durability trade-off, and having both measured is the
point. Quoting 460 K writes/s for this store without naming the sync policy would be dishonest.

## fsync cost, and the real `EVERY_WRITE` ceiling

Measured from the crash harness (`CrashWriter`, which runs `EVERY_WRITE` because that is the
promise being tested), writing 8KB values:

| | |
|---|---|
| Acknowledged writes | 100,000 |
| Wall clock to acknowledge them | ~370 s |
| **Sustained rate** | **~270 writes/s** |
| **Implied cost per `force(true)`** | **~3.7 ms** |

This corrects a guess in [003](003-durability.md), which estimated "hundreds of microseconds,
capping throughput in the low thousands of writes per second". The real figure is roughly an
order of magnitude worse. Two things contribute: APFS `F_FULLFSYNC` issues a genuine device
barrier rather than just flushing the page cache, and `force(true)` flushes metadata as well as
data, which 003 chose deliberately so a crash cannot leave a stale file length.

So the honest summary of this store's write path is:

| Policy | Measured | What it promises |
|---|---|---|
| `NEVER` | ~460,000 writes/s | nothing beyond process crash |
| `EVERY_WRITE` | ~270 writes/s | survives power loss |

A factor of ~1,700. That is the durability trade-off with numbers on it, and it is why group
commit — amortising one fsync across a batch of queued writes — is the most valuable single
change available to the write path in Phase 5, rather than the afterthought 003 first treated it
as.

## Crash recovery at the exit-criteria scale

`CrashRecoveryTest.HardKill.acknowledgedWritesSurvive`, run with
`-Ddistrikv.crash.acks=100000`:

- A child JVM wrote with `EVERY_WRITE` and printed each key **after** its `put` returned.
- After 100,000 acknowledgements the parent sent SIGKILL (`destroyForcibly`).
- The store reopened, and **all 100,000 acknowledged keys read back byte-for-byte.**
- Total runtime 6 m 9 s, essentially all of it fsync latency.

What this proves: recovery handles a torn tail without losing anything that was acknowledged.
What it does **not** prove: durability against power loss. SIGKILL destroys the process, but the
page cache belongs to the kernel, which keeps running — so these keys would have survived even
under `NEVER`. Establishing the stronger claim needs a VM that can be hard-reset or a
fault-injecting filesystem, and is out of scope for Phase 1.

## Test-suite runtime

The whole 95-test suite runs in about 7 seconds with the `slow` tag excluded, which is the number
that actually matters day to day — a suite slow enough to skip is a suite that does not exist.
The `slow` tag covers the 1GB measurement and the 100k-key round-trip.

## Consequences

**Revisit when:**

- **Milestone 4.3 (hint files).** Compare against 420 ms warm, and add a cold-cache measurement
  first so the comparison is honest.
- **Phase 5 (benchmarks).** Replace the `NEVER` throughput figures with the full sync-policy
  curve, measured per policy, with fsync latency for this device stated alongside.
