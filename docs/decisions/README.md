# Design decisions

One short file per decision. Name them `NNN-short-title.md`, numbered in the order you made them.

## Why bother

Two reasons, and the second is the real one.

1. **Future-you will not remember.** In Phase 4 you will look at your fsync placement and have no
   idea whether it's load-bearing or an accident. A three-paragraph note settles it in 30 seconds.
2. **Writing the trade-off down is how you find out whether you understand it.** If you can't
   articulate what the alternative would have cost, you didn't make a decision — you picked the
   first thing that compiled. This is the single cheapest way to convert "I built a thing" into
   "I understand why it's built that way", and it's the difference between a project that reads
   well on a CV and one you can defend for 45 minutes under questioning.

Keep them short. A page is plenty. An unwritten decision is worth more than a decision document
nobody finishes.

## Template

```markdown
# NNN — Title

**Date:** YYYY-MM-DD
**Phase/milestone:** 1.4
**Status:** accepted | superseded by NNN

## Context
What forced a choice here? What constraints were real?

## Options considered
- **A** — how it works, what it costs.
- **B** — how it works, what it costs.

## Decision
What I picked.

## Why
The reasoning. Specifically: what does A buy me that B doesn't, and what am I giving up?

## Consequences
What this makes easy. What it makes hard. What I'll have to revisit, and when.
```

## Recorded so far

- [001 — No Spring Boot in the core; plain Java modules](001-no-spring-boot.md)
- [002 — On-disk record format](002-record-format.md)
- [003 — Durability: sync policy, fsync scope, and the data-directory lock](003-durability.md)
- [004 — API shape, error handling, and the store's honest capacity](004-api-and-capacity.md)
- [005 — Recovery: torn tails, real corruption, and what `open` does about each](005-recovery-and-corruption.md)
- [006 — Concurrency: one writer, N readers, and the gap between them](006-concurrency.md)
- [007 — Phase 1 baseline measurements](007-phase1-baseline-measurements.md)

## Decisions ROADMAP.md asks you for

Phase 1:

- [x] Record format — every field justified (including why `expiresAt` is reserved early) → [002](002-record-format.md)
- [x] Durability policy — what each `SyncPolicy` actually promises a caller → [003](003-durability.md)
- [x] `Optional<byte[]>` vs `null` from `get` (`KeyValueStore` argues both sides; pick one) → [004](004-api-and-capacity.md)
- [x] Key/value size limits, and the RAM cost of the KeyDir at your target key count → [004](004-api-and-capacity.md)
- [x] What `open()` does when it finds corruption mid-segment → [005](005-recovery-and-corruption.md)
- [x] `putIfNewer` on an exact timestamp tie → [002](002-record-format.md)
- [x] Your concurrency model, and what a reader can observe mid-write → [006](006-concurrency.md)

Phase 2:

- [ ] `bytes` vs `string` in the proto
- [ ] Missing key: gRPC `NOT_FOUND` vs `found=false`
- [ ] Thread model, and what happens when the gRPC pool exceeds your write lock

Phase 3:

- [ ] Where the Raft log lives — reuse segments or a purpose-built file
- [ ] Threading model per node (strongly consider a single-threaded event loop)
- [ ] 0-indexed or 1-indexed log, stated once and never deviated from
- [ ] ReadIndex vs lease reads for linearizable reads

Phase 4:

- [ ] Compaction trigger policy
- [ ] The full list of crash points during compaction, and why each is safe
