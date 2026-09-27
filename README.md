# DistriKV

A distributed, crash-safe key-value store in Java — built from scratch, bottom-up: an
append-only local storage engine, a gRPC layer, hand-written Raft consensus, compaction and TTL,
then chaos testing and benchmarks.

No Raft library. No embedded storage engine. That's the point.

**Status:** Phase 1 in progress — local storage engine.

---

## Start here

- **[ROADMAP.md](ROADMAP.md)** — the five phases, milestone by milestone, with exit criteria.
- **[LEARNING.md](LEARNING.md)** — what to read before each phase.
- **[docs/decisions/](docs/decisions/)** — why things are built the way they are.

## Build

```bash
mvn clean install
```

Requires **JDK 25** and Maven 3.9+.

Check that Maven is actually using it, not just your shell's `java`:

```bash
java25 && mvn -v      # the "Java version:" line must say 25
```

If `mvn -v` reports an older JDK, your `java25` shortcut is setting `PATH` but not `JAVA_HOME` —
Maven reads `JAVA_HOME`, so it will happily compile with the wrong compiler and give you
confusing `release 25 not supported` errors. Fix the shortcut to export both.

**No Spring Boot.** See [docs/decisions/001-no-spring-boot.md](docs/decisions/001-no-spring-boot.md).

Expect failures at first, and that's correct: `RecordCodecTest` has live assertions waiting for
milestone 1.1, and the rest of Phase 1 is `@Disabled` stubs you enable as you implement each
piece. Run just the one you're working on:

```bash
mvn -pl distrikv-storage test -Dtest=RecordCodecTest
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

Only `distrikv-storage` is active in the parent `pom.xml`; uncomment the others as you reach
their phases.
