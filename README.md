# DistriKV

A distributed, crash-safe key-value store in Java — built from scratch, bottom-up: an
append-only local storage engine, a gRPC layer, hand-written Raft consensus, compaction and TTL,
then chaos testing and benchmarks.

No Raft library. No embedded storage engine. That's the point.

**Status:** Phase 1 complete — local storage engine. All exit criteria pass (one deliberate
deviation on corruption handling, noted in [ROADMAP.md](ROADMAP.md#exit-criteria)). Phase 2 next.

| | |
|---|---|
| Tests | 97, none skipped — 95 in the fast suite (~7s), 2 tagged `slow` |
| 1GB reopen | 420 ms (1M keys, 17 segments, warm cache) |
| Writes/s | ~460,000 under `NEVER` · ~270 under `EVERY_WRITE` (fsync-bound) |
| Crash-tested | 100,000 acknowledged writes, SIGKILL, all recovered byte-for-byte |

---

## Start here

- **[ROADMAP.md](ROADMAP.md)** — the five phases, milestone by milestone, with exit criteria.
- **[LEARNING.md](LEARNING.md)** — what to read before each phase.
- **[docs/decisions/](docs/decisions/)** — why things are built the way they are.

## Build

```bash
java25 && ./gradlew build
```

Gradle comes from the wrapper — nothing to install. The wrapper JVM needs **JDK 17+**, and the
code compiles against a **Java 25** toolchain.

Two independent JDKs are in play and it's worth knowing which is which:

| | Which JDK | Set by |
|---|---|---|
| The JVM Gradle itself runs on | whatever `JAVA_HOME` points at (must be 17+) | your `java21` / `java25` shell shortcut |
| The JDK that compiles the code | always 25, regardless of the above | `toolchain` in `build.gradle` |

So `java21 && ./gradlew build` also works — Gradle still compiles with 25. What does *not* work
is the default shell, which is JDK 11:

```
Gradle requires JVM 17 or later to run. Your build is currently configured to use JVM 11.
```

Run `java21` or `java25` first, or change the default in `~/.zshrc`.

Gradle locates the toolchain via `org.gradle.java.installations.paths` in `gradle.properties`,
which lists the Homebrew paths behind those shortcuts. On a machine without JDK 25 installed, the
`foojay-resolver` plugin in `settings.gradle` downloads one instead.

Check what Gradle can see:

```bash
./gradlew -q javaToolchains
```

**No Spring Boot.** See [docs/decisions/001-no-spring-boot.md](docs/decisions/001-no-spring-boot.md).

Run just the suite you're working on:

```bash
./gradlew :distrikv-storage:test --tests '*RecordCodecTest'
```

The long-running tests are tagged `slow` and excluded by default — the 1GB reopen measurement and
the 100k-key round-trip. The scale knobs on the others default low so the suite stays fast; the
Phase 1 exit criteria were verified at their full stated scale like this:

```bash
./gradlew :distrikv-storage:test -PincludeSlow \
  -Ddistrikv.oracle.ops=1000000 \
  -Ddistrikv.concurrency.seconds=60 \
  -Ddistrikv.crash.acks=100000
```

## Architecture

```
┌─────────────────────────────────────────────┐
│ distrikv-server    gRPC service, bootstrap  │  phase 2
├─────────────────────────────────────────────┤
│ distrikv-raft      election, replication    │  phase 3
├─────────────────────────────────────────────┤
│ distrikv-storage   Bitcask engine, recovery │  phase 1  ← you are here
└─────────────────────────────────────────────┘
  distrikv-proto     wire contract (shared)
```

Writes go client → gRPC → Raft leader → replicated to a quorum → applied to the local storage
engine on every node. Reads go through the leader with a ReadIndex check, so they can't be served
stale from a partitioned node.

The layering rule: **`distrikv-storage` never imports gRPC or Raft.** If it needs to, a boundary
has leaked and the design gets fixed rather than worked around.

## Module map

| Module | Phase | What's in it |
|---|---|---|
| `distrikv-storage` | 1 | Append-only log, in-memory KeyDir, CRC record framing, crash recovery, compaction |
| `distrikv-proto` | 2 | `.proto` definitions and generated stubs |
| `distrikv-server` | 2 | gRPC service impl, node bootstrap, config, lifecycle |
| `distrikv-raft` | 3 | Role state machine, `RequestVote`, `AppendEntries`, commit/apply, snapshots |

Only `distrikv-storage` is active in `settings.gradle`; uncomment the others as you reach
their phases.
