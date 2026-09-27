# 001 — No Spring Boot in the core; plain Java modules

**Date:** 2026-09-25
**Phase/milestone:** 0 (project setup)
**Status:** accepted

## Context

Spring Boot is the default way to start a Java project, and it's the day-job stack. The question
is whether DistriKV should be a Spring Initializr project.

## Decision

No. `distrikv-storage`, `distrikv-raft` and `distrikv-proto` stay plain Java with zero framework
dependencies. `distrikv-server` may get a thin Spring Boot shell later (see below), but not now.

## Why

**1. Test startup time, and this is the decisive one.** Phase 1's crash tests fork a child JVM,
write, and `kill -9` it — dozens of times per run. Phase 3's exit criteria call for 10,000
randomized cluster scenarios, each spinning up three nodes. A bare JVM starts in ~80ms. A Spring
Boot context takes 1–3 seconds. At 10,000 × 3 nodes that is the difference between a test suite
that runs in minutes and one that runs overnight — and a test suite you won't run is a test suite
that doesn't exist. This alone settles it.

**2. Raft needs to own its clock and its threads.** Milestone 3.9 requires an injectable clock
and transport so tests can advance time by hand and simulate partitions without sockets.
Spring's lifecycle management, `@Scheduled`, and `@Async` all want to own exactly those things.
Fighting a framework for control of time is a bad use of the eight weeks Phase 3 will take.

**3. Dependency injection solves a problem this project doesn't have.** Spring's DI earns its
keep when there are dozens of interchangeable beans with tangled lifecycles. A KV node has a
store, a Raft peer set, and a gRPC server — three objects, wired in about twelve lines of
`main`. `new BitcaskStore(config)` is not a problem in search of a container.

**4. It would obscure the thing being learned.** The value of this project is understanding
consensus and crash-safe storage. Every hour spent on `@Configuration` classes is an hour not
spent on the figure-8 commit rule.

**5. Real systems of this kind don't use it.** Kafka, Cassandra, ZooKeeper, Apache Ratis —
plain Java. That isn't an appeal to authority so much as evidence about what infrastructure code
actually needs, which is different from what a service layer needs.

## Options considered

- **A — Spring Boot from the start.** Config binding via `@ConfigurationProperties`, Actuator
  health/metrics endpoints free, `grpc-spring-boot-starter` for the server. Costs: everything
  above.
- **B — Plain Java everywhere.** Chosen. Config is a record (`StoreConfig` already is one),
  metrics come from Micrometer, which works standalone with a `PrometheusMeterRegistry` and a
  tiny HTTP server — no Spring required.
- **C — Plain core, optional Spring shell at the edge.** The fallback if B turns out to be
  annoying. Deferred, not rejected — see below.

## Consequences

**Easy:** fast tests, deterministic Raft, no framework in the way, and a storage engine that can
be published as a standalone library later.

**Harder:** hand-rolling YAML/CLI config parsing (small — a record plus a parser), and wiring
Micrometer + a metrics endpoint manually in Phase 5 instead of getting Actuator for free. Both
are hours, not weeks.

**Revisit when:** Phase 2 (server bootstrap) or Phase 5 (observability), if the manual wiring
starts to hurt. Because the module boundaries are clean, adding Spring Boot to `distrikv-server`
alone at that point is a pom change plus a `main` class — the cost of deferring this decision is
close to zero, while the cost of starting with it is paid in every test run from now on. That
asymmetry is the actual argument.

**Hard rule that survives any revisit:** `distrikv-storage` and `distrikv-raft` never take a
framework dependency. If Spring ever appears in either module's pom, something has gone wrong.
