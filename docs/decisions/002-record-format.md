# 002 — On-disk record format

**Date:** 2026-09-30
**Phase/milestone:** 1.1
**Status:** accepted

## Context

The 29-byte header layout was given as a starting point in ROADMAP.md, with the instruction to be
able to defend every field rather than copy it. This records those defences, plus the three
decisions the layout leaves open (tie-breaking, unknown flags, byte order enforcement).

```
 0        4          12      13       17       21        29
 +--------+----------+-------+--------+--------+---------+---------+-----------+
 | crc32  |timestamp | flags | keyLen | valLen |expiresAt|   key   |   value   |
 |   4B   |    8B    |  1B   |   4B   |   4B   |   8B    |  keyLen |  valLen   |
 +--------+----------+-------+--------+--------+---------+---------+-----------+
          |<--------------------- CRC covers this --------------------------->|
```

## Decision

Keep the layout as given. Big-endian. CRC32 over `[4, end)`. `flags` bit 0 = tombstone, all
other bits reserved and rejected on read. Decode validates length fields before allocating
anything.

## Why

**The CRC is first because a checksum you have to trust corrupt data to find is useless.** With
the CRC last, locating it requires `HEADER_BYTES + keyLen + valLen` — so you would use
unvalidated length fields to find the field that validates them. Circular. At a fixed offset 0
the checksum is readable before anything else is believed. The decode order that falls out is:
read the fixed header, bounds-check the lengths, checksum exactly that span, and only then
interpret the bytes.

**The CRC covers the header, not just the payload.** Silently-wrong metadata is worse than a
silently-wrong value, because recovery makes decisions with it: a corrupt `timestamp` changes
which of two records for a key wins, and a corrupt `keyLen` changes where the next record
starts. `RecordCodecTest.corruptTimestamp` is precisely the test that fails if you checksum only
key and value.

**`expiresAt` is reserved now, though TTL is milestone 4.5, because the field sits inside the CRC
span.** Adding it in Phase 4 would change what the checksum covers, making every previously
written record undecodable — which means a format-version byte and two decode paths maintained
forever. 8 bytes per record now buys that away entirely. This is the cheapest possible moment to
avoid a format migration: there is no data yet.

**`keyLen` at 4 bytes is genuinely wasteful and stays anyway.** It permits a 2GB key, which is
absurd; `StoreConfig.maxKeyBytes` defaults to 4KB, so 2 bytes would cover every real key. The
reason not to: Java's `short` is signed, so a 2-byte unsigned length needs `& 0xFFFF` masking at
every site, and a missed mask is a silent wrap-around bug in the one code path where a wrong
length means reading someone else's bytes. That risk buys back 2 bytes out of a 29-byte header —
under 2% of a record with a 100-byte value. The real defence against a bad length is not a
narrow field, it is validating against `maxKeyBytes` before allocating, which decode does.

**`timestamp` is for correctness, not debugging.** `KeyDir.putIfNewer` uses it during replay to
decide which of two records for the same key wins. Today that is belt-and-braces, because
scanning segments oldest-to-newest already puts later writes last. In Phase 4 it becomes
load-bearing: once compaction emits merged segments, a high segment id no longer means recent
data, and the timestamp is the only ordering left.

**Ties are the real problem, and there are two of them.** Wall-clock millis can repeat (two puts
in the same millisecond) and can move backwards (NTP step). So:

- `putIfNewer` treats an equal timestamp as *newer* (`>=`), meaning the later-scanned record
  wins. Recovery's contract is therefore to scan in ascending `(segmentId, offset)` order, which
  `LogReplayer` guarantees. This is written down because a tie-break that exists only in
  someone's head gets reversed by accident.
- The store clamps each new record's timestamp to `max(lastIssued, now)`, so timestamps never go
  backwards within one running store even if the system clock does. This does not survive a
  restart, but across a restart the segment ordering resolves it anyway.
- Phase 4 hazard, noted now: compaction must emit at most one record per key, so equal-timestamp
  pairs never survive into merged output where scan order would no longer save us.

**Big-endian because it is `ByteBuffer`'s default, and it does not matter here.** Nothing in this
format is compared as raw bytes across machines, and keys are never `memcmp`'d — they go through
`Key.equals`, which compares element-wise. It would matter a great deal for an LSM tree, where
lexicographic key ordering is supposed to fall out of byte ordering for free. That it is
irrelevant here is one more reason Bitcask is the easier first build. `decode` nevertheless forces
`BIG_ENDIAN` on its view of the caller's buffer, so a caller who hands us a little-endian buffer
gets correct results rather than silent garbage.

**Unknown `flags` bits are rejected, not ignored.** If the CRC passes and an unrecognised bit is
set, the record was written deliberately by something that understood more than we do. There is
no version field to negotiate with, so interpreting the record as if the bit were absent risks
returning a value that a newer writer considered dead. Fail loud.

## Options considered

- **A — CRC last, as a trailer.** Conventional in some formats and it makes streaming writes
  marginally simpler. Rejected for the circularity above.
- **B — 2-byte `keyLen` + 4-byte `valLen`.** Saves 2 bytes per record. Rejected: unsigned-short
  handling in Java is a bug farm for a sub-2% win.
- **C — Add a monotonic sequence number for a true total order.** This is the clean fix for
  timestamp ties and is what a production system should do. Rejected for now because it changes
  the given header and the `(>=` + scan order) contract is sufficient while the only producer of
  segments is the append path. Revisit at milestone 4.2, where compaction starts producing
  segments out of chronological order.
- **D — A format version byte.** Deferred. Reserving `expiresAt` removes the near-term need, and
  the `flags` byte has 7 free bits available to signal a future layout if it comes to that.

## Consequences

**Easy:** decode can reject hostile input without allocating; corruption anywhere in the record,
header included, is caught; TTL in Phase 4 needs no format change.

**Harder:** 29 bytes of header per record is heavy for tiny values — a 1-byte value costs 30
bytes on disk, a 30x amplification. Acceptable because Bitcask's target is values much larger
than its keys, and because compaction in Phase 4 reclaims the dead ones. Worth re-measuring in
Phase 5 before claiming a space-efficiency number.

**Revisit when:** milestone 4.2 (compaction reorders segments — see option C) or Phase 5
benchmarking shows header overhead dominating for the workload being measured.