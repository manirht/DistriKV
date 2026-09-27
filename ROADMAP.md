# DistriKV — Build Roadmap

A distributed, crash-safe key-value store in Java, built bottom-up from a local
append-only storage engine to a Raft-replicated cluster.

**Chosen approach:** everything hand-rolled — no Raft library, no embedded storage engine.
**Working mode:** tutor. I write specs, interfaces, contracts and tests. You write the
implementations. I review and explain.

---

## How we work together

Each milestone follows the same loop:

1. **You ask for the milestone spec.** Say e.g. *"give me Phase 1.3"*. I write the contract:
   what the class must do, its invariants, the edge cases, and the tests that must pass.
2. **You implement it.** Don't read ahead. Don't ask me for the answer before you've been
   stuck for 20 minutes — being stuck is where the learning happens.
3. **You push and say "review 1.3".** I read your code and tell you what's wrong, what's
   subtly wrong, and what a reviewer at a database company would flag.
4. **You fix it, tests go green, we move on.**

Rules I'll hold you to:

- **No milestone is done until its tests pass.** Not "works when I run it manually."
- **Write the test first when I give you one.** Watch it fail, then make it pass.
- **Every design decision gets a one-paragraph note in `docs/decisions/`.** Why you picked
  fsync-per-write over batched, why 4-byte length prefixes, etc. Future-you will need these,
  and articulating a trade-off is half of understanding it.
- **Ask me "why" questions freely.** Asking me to explain `FileChannel.force()` costs you
  nothing. Asking me to write your `KeyDir` costs you the project.

---

## The whole thing at a glance

```
Phase 1 — Local storage engine (Bitcask)         single JVM, single thread → concurrent
Phase 2 — gRPC server + client                   single node, over the network
Phase 3 — Raft consensus                         3-node cluster, leader election, replication
Phase 4 — Compaction, TTL, hints                 the store stops growing forever
Phase 5 — Chaos, linearizability, benchmarks     prove it actually works
```

**Honest time estimate** for someone learning this as they go, working evenings:
Phase 1 ≈ 2–3 weeks · Phase 2 ≈ 1–2 weeks · Phase 3 ≈ 4–8 weeks · Phase 4 ≈ 2–3 weeks ·
Phase 5 ≈ ongoing. Phase 3 is not a typo. Raft is where everyone underestimates by 3x.

**Rule: do not start Phase N+1 until Phase N's exit criteria pass.** The single most common
way this project dies is adding Raft on top of a storage engine that silently loses data —
then debugging two broken layers at once, which is roughly impossible.

---

## Phase 1 — Local storage engine (Bitcask model)

**Goal:** a single-node, crash-safe, embeddable KV engine. No networking. No threads at
first. When this phase ends you have something genuinely useful on its own.

**Why Bitcask and not an LSM tree:** Bitcask is ~1500 lines and teaches you the append-only
log, the in-memory index, crash recovery and compaction — the same four ideas that LSM trees
use, without also teaching you skiplists, bloom filters, leveled merge policies and
multi-level iterators at the same time. Build an LSM tree later as Phase 6 if you want; you'll
find it much easier having done this.

**The one-sentence model:** writes append to a log and update a RAM index; reads consult the
RAM index and do exactly one disk seek; restart replays the log to rebuild the index.

### Milestones

| # | Milestone | What you build |
|---|-----------|----------------|
| 1.1 | `Key`, `Record`, record framing | The on-disk binary format + `RecordCodec` encode/decode with CRC32 |
| 1.2 | `LogSegment` | One data file. Append a record, return its offset. Read a record at an offset. |
| 1.3 | `KeyDir` | In-memory `Map<Key, ValueLocation>`. Deliberately simple, single-threaded. |
| 1.4 | `BitcaskStore` | Wire 1.1–1.3 into `get`/`put`/`delete`. Tombstones for deletes. |
| 1.5 | `LogReplayer` | On open, scan segments oldest→newest, rebuild the KeyDir. Handle a torn final record. |
| 1.6 | Segment rollover | Active segment hits `maxSegmentBytes` → close it, open a new one. Read path spans segments. |
| 1.7 | Concurrency | Make it safe for N reader threads + 1 writer. `ReadWriteLock` first; understand *why* before optimizing. |

### The record format — your first real design decision

Here's a starting point. **Don't just copy it — I want you to be able to defend every field.**

```
 0        4          12      13       17       21        29
 +--------+----------+-------+--------+--------+---------+---------+-----------+
 | crc32  |timestamp | flags | keyLen | valLen |expiresAt|   key   |   value   |
 |   4B   |    8B    |  1B   |   4B   |   4B   |   8B    |  keyLen |  valLen   |
 +--------+----------+-------+--------+--------+---------+---------+-----------+
          |<--------------------- CRC covers this --------------------------->|
```

Header = 29 bytes. `flags` bit 0 = tombstone. `expiresAt` = 0 means no expiry.

Questions I will ask you in review, so think about them now:

- Why is the CRC *first* and not last? (What can you do before you've read the whole record?)
- Why include `expiresAt` in Phase 1 when TTL is a Phase 4 feature?
- `keyLen` is 4 bytes = up to 2GB keys. Wasteful? What would you actually pick and why?
- Is `timestamp` for correctness or for debugging? What breaks if two records have the same one?
- Big-endian or little-endian, and does it matter here? (`ByteBuffer` defaults to big-endian.)

### Design decisions you must make and document

1. **Durability policy.** `fsync` on every write (safe, ~100s of writes/sec on a spinning disk,
   still slow on SSD), every N writes, every N millis, or never (fast, loses the tail on power
   loss). Most real systems make this configurable. Yours should too — `StoreConfig.syncPolicy`.
   Write down what each setting actually promises the caller.
2. **`byte[]` vs a wrapped `Key` type.** `byte[]` has identity equality in Java — a
   `HashMap<byte[], _>` will never find anything you put in it. This is the single most common
   bug in hand-rolled Java KV stores. I've given you `Key` already; read its `equals`/`hashCode`
   and make sure you understand why it must copy the array defensively.
3. **What `get` returns for a missing key.** `null`, `Optional<byte[]>`, or throw. Pick one,
   be consistent, note the perf implication of `Optional` on a hot path.
4. **Value size limit.** Bitcask keeps *all* keys in RAM forever. What's your budget? At 64-byte
   keys plus a ~40-byte `ValueLocation` plus HashMap overhead, roughly how many keys fit in 4GB?
   Compute it — this number is the honest limit of your design and you should know it.

### Exit criteria — all of these must pass before Phase 2

- [ ] `put`/`get`/`delete` round-trip for 100k random keys, values 1B–1MB.
- [ ] **Crash test:** write 100k keys, `kill -9` the JVM mid-write, restart, every
      acknowledged key reads back correctly and the torn tail record is discarded cleanly.
- [ ] **Oracle test:** run 1M random ops against both your store and a `HashMap`, assert they
      agree at every step. (This finds bugs no hand-written test will.)
- [ ] Corrupt a byte in the middle of a data file → the affected record is detected via CRC and
      rejected, and the store still opens.
- [ ] Segment rollover works: a store with 10 segments reads keys from all of them.
- [ ] 4 reader threads + 1 writer for 60s → no exceptions, no torn reads, no lost writes.
- [ ] Reopening a 1GB store takes a time you've measured and written down. (You'll make this
      fast in Phase 4 with hint files. Knowing the "before" number is the point.)

### Concepts to actually learn here

Append-only logs · why sequential I/O beats random I/O by ~100x even on SSD · `FileChannel`
vs `RandomAccessFile` vs `MappedByteBuffer` · what `fsync`/`force()` does and what the page
cache and disk write cache do behind your back · CRC32 and why checksums go on every record ·
tombstones and why deletes are writes · torn writes and atomicity of partial appends ·
`ByteBuffer` position/limit/flip (you will get this wrong at least once) · `ReadWriteLock`
and why one writer + N readers is the natural fit here.

### Traps

- **`ByteBuffer.flip()`.** You'll forget it. Symptom: reads return zeros or nothing.
- **Assuming a single `write()` is atomic.** It isn't. A crash can leave half a record. This is
  exactly what your CRC + torn-tail handling exists for.
- **Testing only the happy path.** A storage engine that works when nothing goes wrong has
  demonstrated nothing. The whole value of Phase 1 is the crash tests.
- **`File.delete()` on Windows / open file handles.** Close your channels in tests or your temp
  dirs will leak and tests will pass locally and fail in CI.

---

## Phase 2 — gRPC server and client

**Goal:** the Phase 1 engine, reachable over the network, safe under concurrent clients.

The engine is *unchanged* in this phase. If you find yourself editing `BitcaskStore` to make
the server work, stop — that's a sign Phase 1's API was wrong, and we should fix the API
deliberately rather than by accident.

### Milestones

| # | Milestone | What you build |
|---|-----------|----------------|
| 2.1 | `distrikv.proto` | Service + message definitions. Get the Maven protobuf plugin generating stubs. |
| 2.2 | `KeyValueServiceImpl` | Thin adapter: proto request → engine call → proto response. No logic here. |
| 2.3 | Server bootstrap | `DistriKvServer` with config (port, data dir), clean startup/shutdown, shutdown hook. |
| 2.4 | Error mapping | Engine exceptions → gRPC `Status` codes. `NOT_FOUND` vs `INVALID_ARGUMENT` vs `INTERNAL`. |
| 2.5 | Java client | `DistriKvClient` wrapping the blocking stub with a sane API and timeouts. |
| 2.6 | Streaming | `Scan` as a server-streaming RPC. Teaches you flow control and backpressure. |
| 2.7 | Load test | Concurrent clients hammering one node. Find where it falls over, and why. |

### Design decisions

1. **`bytes` vs `string` in the proto.** Keys are arbitrary bytes. `string` in protobuf must be
   valid UTF-8. Choose correctly and know why.
2. **Should `Get` on a missing key be an error or a response with `found=false`?** Both are
   defensible. gRPC status codes are not free — think about cost and about client ergonomics.
3. **Thread model.** gRPC gives you a pool. Your engine has one writer lock. What happens when
   200 threads all want to write? Measure it before you theorize.
4. **Timeouts and deadlines.** A client with no deadline is a bug. Where do you set them?
5. **Max message size.** The default is 4MB. Your engine accepts 1MB values. Is that consistent?

### Exit criteria

- [ ] `mvn clean install` generates stubs and the server starts on a configurable port.
- [ ] Round-trip over the network: put then get from a separate JVM.
- [ ] Ungraceful client disconnect mid-request leaves no leaked threads or file handles.
- [ ] 50 concurrent clients × 10k ops each → zero errors, zero lost writes, and you have
      written down throughput and p99.
- [ ] `SIGTERM` → in-flight requests complete, data is fsynced, process exits 0.
- [ ] A binary key containing `0x00` and invalid-UTF-8 bytes round-trips intact.

### Concepts

Protobuf wire format and why it's compact · gRPC unary vs streaming · HTTP/2 multiplexing ·
deadlines propagating across hops · `StreamObserver` and `onNext`/`onCompleted`/`onError`
contracts · backpressure and what happens without it · gRPC status code semantics · graceful
shutdown as a real engineering problem.

---

## Phase 3 — Raft consensus

**Goal:** 3 nodes, one leader, replicated log, linearizable writes, survives any single-node
failure with zero data loss.

This is the hard part and the reason the project is worth doing. Budget 4–8 weeks. Read the
Raft paper (all of it, twice) before writing a line.

**Non-negotiable rule for this phase: no wall-clock timing in tests.** Your Raft
implementation must accept an injectable clock and an injectable transport, or your tests will
be flaky forever and you'll stop trusting them, and then they're worthless. Design for this
from milestone 3.1.

### Milestones

| # | Milestone | What you build |
|---|-----------|----------------|
| 3.1 | State and persistence | `currentTerm`, `votedFor`, `log[]` — durably. Raft's safety proof assumes these survive a crash. |
| 3.2 | `RaftLog` | Append, truncate-from-index, term-at-index, last-index/term. Deceptively fiddly; test it hard. |
| 3.3 | Role state machine | Follower / Candidate / Leader with explicit, tested transitions. |
| 3.4 | Leader election | `RequestVote` RPC, randomized timeouts (150–300ms), term rules, split-vote recovery. |
| 3.5 | Log replication | `AppendEntries`, `nextIndex`/`matchIndex`, consistency check, follower log repair. |
| 3.6 | Commit and apply | Majority match → advance `commitIndex` → apply to your Phase 1 engine, in order, exactly once. |
| 3.7 | Client interaction | Write goes to leader; followers redirect. Handle "leader unknown" and client retries. |
| 3.8 | Linearizable reads | Naive reads from the leader can be stale after a partition. Implement ReadIndex, then leases. |
| 3.9 | Deterministic test harness | Simulated network: drop, delay, reorder, partition. Run thousands of randomized scenarios. |

### The five bugs everyone writes

Know these in advance and you'll still write two of them:

1. **Committing an entry from a previous term by counting replicas alone.** See §5.4.2 of the
   paper and the figure-8 scenario. This one silently corrupts data.
2. **Not resetting the election timer on a valid `AppendEntries`.** Endless elections, no
   progress, cluster looks "up" and does nothing.
3. **Persisting state after replying to an RPC instead of before.** Breaks safety on crash.
4. **Off-by-one on log indices.** Raft is 1-indexed in the paper; Java lists are 0-indexed.
   Pick one convention, write it at the top of the file in a comment, never deviate.
5. **Applying committed entries out of order, or twice, after a restart.** Your state machine
   must be idempotent with respect to replay, or track `lastApplied` durably.

### Design decisions

1. **Where does the Raft log live?** Reuse your Bitcask segments, or a separate purpose-built
   file? They have genuinely different access patterns — Raft needs index-ordered scans and
   truncation. Think before reusing.
2. **One thread per node, or a thread pool?** A single-threaded event loop per node is far
   easier to reason about and to test deterministically. Strongly consider it.
3. **Batching `AppendEntries`.** One RPC per entry is correct and slow. Batching is where
   throughput comes from — but it complicates the retry logic.
4. **Snapshotting.** The log grows forever without it. You can defer it to Phase 4, but decide
   now, because it affects your log API.

### Exit criteria

- [ ] 3 nodes elect exactly one leader within 1s of startup, 100 runs out of 100.
- [ ] `kill -9` the leader → a new leader within ~1s, no committed write lost, 50 runs.
- [ ] Network partition (2 | 1) → majority side keeps serving, minority side rejects writes
      and does *not* serve stale reads.
- [ ] Partition heals → minority node catches up automatically, logs converge byte-for-byte.
- [ ] A follower with a diverged log gets repaired correctly (test the truncation path
      explicitly — it's the one people never test).
- [ ] 10k randomized scenarios in the simulated-network harness, zero safety violations.
- [ ] A linearizability checker (Porcupine/Elle-style, or one you write) finds no violation in
      a concurrent history with faults injected.

### Concepts

The Raft paper end to end · safety vs liveness · why a majority quorum works · term as a
logical clock · log matching property · the election restriction (§5.4.1) · why committed
entries from old terms need care · ReadIndex and lease reads · FLP impossibility and why
consensus needs timing assumptions for liveness but not safety · deterministic simulation
testing as a technique (read what FoundationDB and TigerBeetle do here).

---

## Phase 4 — Compaction, TTL, hint files

**Goal:** the store stops growing without bound, restarts get fast, TTL works.

### Milestones

| # | Milestone | What you build |
|---|-----------|----------------|
| 4.1 | Compaction worker | Background thread: read immutable segments, keep only live records, write a merged segment, atomically swap. |
| 4.2 | Atomic swap | Write to a temp file, fsync, atomic rename, fsync the *directory*, then update the KeyDir. Get this wrong and a crash mid-compaction eats data. |
| 4.3 | Hint files | Alongside each compacted segment, write a key→location index so startup skips the full scan. |
| 4.4 | Compaction policy | When to compact? Dead-byte ratio, segment age, write amplification. Measure, then tune. |
| 4.5 | TTL | Honor `expiresAt`: lazy expiry on read, real reclamation during compaction. |
| 4.6 | Raft log truncation | Snapshot the state machine, discard the prefix, `InstallSnapshot` RPC for lagging followers. |

### The crash-consistency question

Compaction is the single most dangerous code in the project, because it's the only place that
*deletes* data. Before you write it, answer: at every single point where the process could die
during compaction, does the store still open and contain every acknowledged write? Write those
cases down as a list, then write a test for each. A fault-injection harness that kills the
process at a random point during compaction is the right tool here.

Also: `rename()` is atomic, but the *directory entry* isn't durable until you fsync the
directory itself. Most people don't know this. Now you do.

### Exit criteria

- [ ] A store with 90% dead records shrinks to ~10% of its size after compaction.
- [ ] Reads and writes continue correctly *during* compaction.
- [ ] Killed at 20 different points mid-compaction → reopens cleanly every time, zero data loss.
- [ ] Hint files cut the 1GB reopen time by ≥10x versus your Phase 1 measurement.
- [ ] Expired keys are invisible to `get` immediately and physically gone after compaction.
- [ ] A follower that's 100k entries behind catches up via snapshot install.

### Concepts

Write amplification and space amplification · garbage collection in log-structured storage ·
atomic file replacement and directory fsync · POSIX rename semantics · lazy vs eager deletion ·
Raft snapshotting and the `InstallSnapshot` RPC · background work without stalling the
foreground (the thing every GC and every LSM compactor fights with).

---

## Phase 5 — Chaos, correctness, benchmarks

**Goal:** stop believing it works and start demonstrating it.

### Milestones

| # | Milestone | What you build |
|---|-----------|----------------|
| 5.1 | Metrics | Prometheus/Micrometer: Raft role, term, log size, commit lag, RPC latency histograms, compaction stats. |
| 5.2 | Structured logging | Node id + term + index on every line, or you will never debug a distributed bug. |
| 5.3 | Docker Compose cluster | 3 nodes + Prometheus + Grafana, one command. |
| 5.4 | Chaos harness | Scripted `kill -9`, SIGSTOP (the nastiest — a node that's alive but frozen), clock skew, `tc netem` latency/loss, `iptables` partitions, disk-full. |
| 5.5 | Linearizability checking | Record real client histories under fault injection, check them offline. |
| 5.6 | Benchmarks | Throughput and p50/p99/p999 vs. value size, vs. cluster size, vs. sync policy. |
| 5.7 | Write it up | README with architecture diagram, benchmark numbers, and — most valuable of all — a "bugs I found and how" section. |

### Exit criteria

- [ ] 1-hour chaos run with continuous writes and random faults: zero lost acknowledged writes,
      zero linearizability violations.
- [ ] A Grafana dashboard where you can *see* a leader election happen.
- [ ] Benchmark numbers you can defend, including the sync-policy trade-off curve.
- [ ] A written post-mortem of the three worst bugs you hit. This section is what makes the
      project impressive to other engineers — anyone can push code to GitHub; almost nobody can
      explain a consensus bug they found and fixed.

### Concepts

Linearizability vs sequential consistency vs eventual consistency · the difference between
crash faults and the gray failures that actually hurt (slow nodes, frozen nodes, asymmetric
partitions) · latency percentiles and why averages lie · coordinated omission in benchmarking
(read Gil Tene) · what Jepsen actually does and why it finds so many bugs.

---

## Stretch goals, once Phase 5 is green

Roughly in order of value-per-unit-effort:

1. **Sharding / consistent hashing** — multiple Raft groups, a key→group router. This is what
   turns a replicated store into a *scalable* one, and it's the most common follow-up question.
2. **Membership changes** — add/remove nodes at runtime (Raft §6, joint consensus). Genuinely
   tricky, very impressive.
3. **An LSM-tree engine** behind the same `KeyValueStore` interface, so you can benchmark the
   two head to head. Far easier now than it would have been in Phase 1.
4. **Transactions** — multi-key atomic writes, MVCC snapshot reads.
5. **Follower reads** with lease-based staleness bounds.

---

## Repository layout

```
DistriKV/
├── ROADMAP.md              this file
├── LEARNING.md             what to read, per phase
├── pom.xml                 parent (Java 21, dependency management)
├── distrikv-storage/       Phase 1 — the engine. No network deps. Ever.
├── distrikv-proto/         Phase 2 — .proto files, generated stubs
├── distrikv-server/        Phase 2 — gRPC service, node bootstrap
├── distrikv-raft/          Phase 3 — consensus. Depends on storage, not on server.
└── docs/decisions/         one short file per design decision you make
```

The module boundaries are the point. `distrikv-storage` must never import gRPC or Raft
classes — if it ever needs to, a layer has leaked and we fix the design.

---

## Getting started right now

```bash
cd ~/personal/DistriKV
mvn clean install          # should pass with all Phase 1 tests failing/disabled
```

Then say: **"give me the Phase 1.1 spec"** and we start.
