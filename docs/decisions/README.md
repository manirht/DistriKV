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

## Decisions ROADMAP.md asks you for

Phase 1:

- [ ] Record format — every field justified (including why `expiresAt` is reserved early)
- [ ] Durability policy — what each `SyncPolicy` actually promises a caller
- [ ] `Optional<byte[]>` vs `null` from `get` (`KeyValueStore` argues both sides; pick one)
- [ ] Key/value size limits, and the RAM cost of the KeyDir at your target key count
- [ ] What `open()` does when it finds corruption mid-segment
- [ ] `putIfNewer` on an exact timestamp tie
- [ ] Your concurrency model, and what a reader can observe mid-write

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
