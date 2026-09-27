# LEARNING.md — what to read, and when

Ordered by phase. **★ = read this before you write code for that phase.** Everything else is
for when you're stuck or curious.

Titles are given in full because URLs rot — if a link is dead, search the title.

---

## Read once, up front (the whole-project foundation)

- **★ *Designing Data-Intensive Applications* — Martin Kleppmann.** Chapter 3 (storage engines)
  before Phase 1, Chapter 5 (replication) and Chapter 9 (consistency and consensus) before
  Phase 3. If you read one book for this project, it's this one. Chapter 3 alone will make
  Phase 1 click.
- **"The Log: What every software engineer should know about real-time data's unifying
  abstraction" — Jay Kreps** (LinkedIn engineering blog). Long, informal, and the single best
  explanation of why append-only logs are the backbone of distributed storage.
- **"Notes on Distributed Systems for Young Bloods" — Jeff Hodges** (somethingsimilar.com).
  30 minutes, and it will save you months of wrong instincts.

---

## Phase 1 — Storage engine

- **★ "Bitcask: A Log-Structured Hash Table for Fast Key/Value Data" — Justin Sheehy, David
  Smith (Basho).** ~6 pages. This is the entire design you're implementing. Read it twice.
- **★ "Files are hard" — Dan Luu** (danluu.com/file-consistency/). Everything you believe about
  writing to a file being safe is wrong, and this explains exactly how. Non-optional before you
  write your durability code.
- **"Ensuring data reaches disk" — LWN.net.** The concrete mechanics: page cache, `write` vs
  `fsync` vs `fdatasync`, why directory fsync matters. Pairs with the above.
- **Java NIO `ByteBuffer` javadoc** — specifically `position`, `limit`, `flip`, `rewind`. Not
  glamorous, but you'll misuse them and this is the fix.
- **★ *Java Concurrency in Practice* — Brian Goetz**, chapters 2–4 and 13, before milestone 1.7.
  Old book, still the correct one for the JVM memory model and lock design.
- Source to read *after* you've built yours (not before — reading it first spoils the exercise):
  - **`etcd-io/bbolt`** (Go) — small, readable, a completely different design (B+tree, mmap,
    copy-on-write) which makes a great contrast to your log-structured one.
  - **`basho/bitcask`** (Erlang) — the reference implementation. Small.

## Phase 2 — gRPC and networking

- **★ gRPC Java "Basics" and "Quick start" tutorials** (grpc.io/docs/languages/java/). Enough
  to get milestone 2.1 done.
- **★ "Protocol Buffers — Encoding"** (protobuf.dev/programming-guides/encoding/). Short. Teaches
  you varints and the wire format, so protobuf stops being magic.
- **"Protocol Buffers — Style Guide"** and **"Proto Best Practices"** (protobuf.dev). Read before
  you design `distrikv.proto`; schema mistakes are expensive to undo later.
- **HTTP/2 RFC 9113, sections 1–5** — skim. You mainly want the idea of streams, multiplexing
  and flow control, because gRPC's backpressure behavior falls straight out of it.
- **"gRPC deadlines" — gRPC blog.** Short, and it explains why a client without a deadline is a
  latent outage.

## Phase 3 — Raft

This is the phase where reading is not optional. Do it in this order.

1. **★ "In Search of an Understandable Consensus Algorithm (Extended Version)" — Diego Ongaro,
   John Ousterhout** (raft.github.io/raft.pdf). The extended version, not the conference one.
   Read it end to end. Then read it again with a pen. Figure 2 is the entire algorithm — print
   it and keep it next to you while you code. §5.4.2 is the subtle safety argument that
   everyone's first implementation violates.
2. **★ "Students' Guide to Raft" — Jon Gjengset** (thesquareplanet.com/blog/students-guide-to-raft/).
   Written from grading hundreds of MIT 6.824 Raft implementations. It lists the exact bugs
   you are about to write. Read it before you start, and again when you're stuck.
3. **★ raft.github.io** — the visualizations. Watch an election and a partition play out. Ten
   minutes, enormous intuition payoff.
4. **Diego Ongaro's PhD thesis, "Consensus: Bridging Theory and Practice"** (github.com/ongardie/dissertation).
   The paper is the summary; the thesis is the manual. Chapter 6 (membership changes),
   chapter 7 (snapshots) and chapter 8 (client interaction, including ReadIndex and lease
   reads) are the parts the paper leaves thin — you'll need them for 3.7 and 3.8.
5. **MIT 6.5840 (formerly 6.824) — Distributed Systems**, lecture notes and videos
   (pdos.csail.mit.edu). Lectures 5–8 cover Raft. The labs are the same problem you're solving;
   the notes are excellent even without doing them in Go.
6. **"Consistency models" — Jepsen** (jepsen.io/consistency). The lattice diagram is the clearest
   single explanation of linearizability vs the weaker models anywhere. Essential for 3.8.
7. **Any Jepsen analysis report — e.g. "Jepsen: etcd 3.4.3" or "Jepsen: MongoDB 4.2.6" — Kyle
   Kingsbury.** Read one in full. It teaches you what rigorous distributed-systems testing
   actually looks like, and it's entertaining.
8. Source to read when your implementation is *mostly* working, to compare approaches:
   - **`etcd-io/raft`** (Go) — the most widely deployed Raft implementation. Clean separation of
     the consensus module from storage and transport; steal that idea.
   - **Apache Ratis** (Java) — the closest thing to "what you're building, productionized in
     Java". Useful for seeing how the JVM-specific problems get solved.
   - **`hashicorp/raft`** (Go) — smaller and easier to read end to end than etcd's.

## Phase 4 — Compaction, snapshots, TTL

- **★ Bitcask paper again** — the merge/hint-file sections, which you skipped the first time.
- **★ Ongaro thesis chapter 7 (snapshotting)** and the `InstallSnapshot` RPC in figure 13 of
  the Raft paper.
- **"WiscKey: Separating Keys from Values in SSD-conscious Storage" — Lu et al. (FAST '16).**
  Why value separation and compaction I/O amplification matter. Explains the cost model you're
  now paying.
- **RocksDB wiki — "Compaction" and "Write Amplification".** Practical, hard-won, and directly
  applicable to your compaction policy in 4.4.
- **POSIX `rename()` specification** plus the directory-fsync discussion in the LWN article
  from Phase 1. Re-read it before writing 4.2 — this is the code where crash-consistency bugs
  destroy data.

## Phase 5 — Testing, chaos, benchmarks

- **★ "Testing Distributed Systems with Deterministic Simulation" — Will Wilson (FoundationDB),
  Strange Loop 2014.** 40-minute talk. Probably the highest-value hour in this entire list: it
  will change how you write tests for the rest of your career, and it's exactly the technique
  for milestone 3.9.
- **TigerBeetle's writing on their simulator (the "VOPR")** and their deterministic-testing
  blog posts. A modern, very readable take on the same idea.
- **★ "How NOT to Measure Latency" — Gil Tene.** Talk or slides. Coordinated omission will make
  your Phase 5 benchmark numbers a lie, and you won't notice unless you've seen this.
- **`anishathalye/porcupine`** — a linearizability checker (Go). Even if you don't use it, read
  the README to understand what checking a history actually involves.
- **Jepsen's `elle` / `knossos`** — the state of the art in consistency checking. Read about
  Elle's approach even if you never run it.
- **"Simple Testing Can Prevent Most Critical Failures" — Yuan et al. (OSDI '14).** An empirical
  study of real distributed-system failures. Sobering and practical: most catastrophes come from
  error-handling code that was never tested.
- **`tc netem` and `iptables` man pages** — your actual chaos tooling for 5.4.

---

## A note on how to read the Raft paper

People bounce off it by reading it like a blog post. Instead:

1. First pass, 45 minutes, no notes. Just get the shape: roles, terms, two RPCs.
2. Second pass with Figure 2 printed. For every rule in the figure, find the paragraph in the
   text that justifies it, and write one sentence on *what breaks if you omit that rule*. That
   exercise is where actual understanding happens, and it's exactly what §5.4.2 demands.
3. Third pass after you've implemented election (3.4). It reads completely differently once
   you've had the bugs.

Do not skip to implementing after pass one. That's the path where you spend six weeks
debugging something the paper explained in a sentence.
