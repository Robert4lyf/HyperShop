# Phone ↔ watch protocol

The rules here are implemented twice and checked three ways:

| Where | What |
|---|---|
| `android/core/.../sync/WatchProtocol.kt` | keys, message types, results (mirrored by `garmin/source/Protocol.mc` in Run 2; `tools/check_protocol_parity.py`) |
| `android/core/.../sync/WatchCodec.kt` | encode/decode, untrusted-input checks |
| `android/core/.../sync/SnapshotPager.kt` | paged snapshots under a byte cap |
| `android/core/.../sync/rules/PhoneSyncModel.kt` | the phone's rules (Room version: Step 5.2d) |
| `android/core/.../sync/rules/WatchSyncModel.kt` | the watch's rules (Monkey C port: Step 6.2) |
| `testdata/sync/*.json` | vectors, replayed by `SyncVectorTest` through the codec and pager |
| `tools/sync-model/model.py` | an independent model, explored exhaustively (`verify.sh` runs `--small`) |

## Envelope

Every payload is a flat Connect IQ dictionary with 1–3 character keys:
`v` (protocol version, currently 1), `t` (type), `ep` (list epoch), and from
the watch `ii` (install id). A payload whose `v` differs is dropped. New keys
and new message types do not bump `v`; a change in the shape or meaning of an
existing message does (BangGym's rule). Only String, Int, Long, Boolean, List
and Map cross the wire.

## Messages

**Watch → phone**

| `t` | Keys | When |
|---|---|---|
| `hello` | `lr` cached listRev, `wn` watch clock (s), `at` ackedThrough | app start, reconnect, and whenever `helloDue` |
| `ops` | `op`: list of `[seq, itemId, done, seenListRev, ts]`, consecutive seqs, ≤ 32 | one batch in flight at a time |
| `bye` | — | `onStop`, best effort |

**Phone → watch**

| `t` | Keys |
|---|---|
| `snap` | `lr`, `at` ackedThrough, `ax` `[[seq, result]…]` for acked seqs whose result is not `ok`, `pg`, `pgs`, `ai` `[base, [code, name, left, hidden]…]`, `it` `[[id, name, sub, aisleIdx, done, rev]…]`, `mo` items left off |

**Payload probe** (debug watch builds; additive, so no version bump)

| `t` | Direction | Keys |
|---|---|---|
| `prb` | watch → phone | `n` reply size wanted (≤ 8192), `pd` padding (≤ 8192 chars, optional) |
| `prb` | phone → watch | `n` the request's size as the phone counted it, `sd` this reply's size, `pd` padding up to `n` asked |

Sizes are `PayloadSize` estimates, the measure `PayloadCap` is in. The watch
asks for growing replies (phone → watch), then sends growing padding (watch →
phone), and shows the largest that got through each way.

A page with `pgs = 0` is **ack-only**. The phone **always** replies: a full
snapshot to `hello` and to a batch that changed the list, an ack-only page
otherwise. It also pushes a snapshot when the list changes
(`distinctUntilChanged`) and on `APP_READY`.

## Phone rules (one Room transaction per batch)

1. **Install tracking.** An unknown install starts at `lastSeq = hello.at`, or
   `firstSeq − 1` for a batch. A `hello` whose `at` exceeds `lastSeq`
   fast-forwards it (the watch voided seqs when it adopted a new epoch).
2. **Sequence.** For each op: `seq ≤ lastSeq` → duplicate, ignored (its result
   is re-sent from the results kept per install); `seq ≠ lastSeq + 1` → stop,
   the watch goes back to `at + 1`; else apply, record the result, `lastSeq = seq`.
   Results are kept until the watch reports the seq settled (`hello.at`, or a
   batch's `firstSeq − 1`; a batch with a gap is ignored, claim included), at
   most 64 per install, and `ax` carries only those:
   sending every non-`ok` result ever recorded could not fit the payload cap
   once a handful of `noop`s had piled up, and the watch then resent for ever.
3. **Wrong epoch.** The op's result is `epoch`; nothing changes, but the seq is
   consumed.
4. **Unknown item.** Resolve through `ItemAlias`. Still unknown or deleted →
   `rejected`.
5. **Tick.** Cover the uncovered contributions with `createdListRev ≤
   seenListRev` with a new `Tick(WATCH, installId, seq)`. Nothing to cover →
   `noop` (or `partial` if newer uncovered ones exist). Newer ones left
   uncovered → `partial`.
6. **Untick.** Undo this install's most recent watch `Tick` on the item. None →
   `noop`. Item still DONE (another tick) → `still_done`.

Every change bumps `listRev` and the item's `rev`.

## Watch rules

- **Ops.** Selecting an item toggles it. If an op for that item has not been
  sent yet it is collapsed (removed, and replaced only if the result differs
  from the item's state without it). Ops that have a seq are never collapsed,
  so assigned seqs are always a contiguous prefix of the outbox.
- **Seqs.** Assigned when an op is first put in a batch; `nextSeq` is written
  to Storage before sending and never reset (not even by an epoch change).
- **Sending.** One batch (≤ 8, or the measured limit) in flight. No reply →
  back off 5 s → 15 s → 60 s and resend from `ackedThrough + 1`. Flush on
  `onStop`. Outbox full → ticking blocked ("Sync with phone").
- **Acks.** Any reply ends the in-flight batch. Ops with `seq ≤ at` leave the
  outbox; results come from `ax` (default `ok`): `partial` → "1 more",
  `rejected` → "Changed on phone". An `ok` op stays drawn on top of the snapshot
  until a snapshot at least as new as the ack's `lr` arrives.
- **Snapshots.** Pages are kept per `lr` until pages `0 … pgs−1` are all there;
  then the snapshot replaces the cache. Pages with a lower `lr` than the cache
  are ignored (their acks are still processed). An ack-only page whose `lr` is
  newer than the cache sets `helloDue`.
- **Epoch.** A page with a different `ep`: drop the outbox ("N watch ticks
  discarded (list replaced)"), clear the cache, keep `nextSeq`, set
  `ackedThrough = nextSeq − 1` and `helloDue`. That hello voids the discarded
  seqs on the phone (phone rule 1), so go-back-N never waits on a seq that will
  not come.
- **View.** Snapshot, then acked-`ok` ops not yet in a snapshot, then the outbox.

## Payload size and paging

`PayloadSize` over-estimates the Connect IQ encoding; `PayloadCap` defaults to
384 bytes × 16 pages until the Hand-off B probe measures the Forerunner 245.
Each page carries the envelope and acks first. Then aisles, then items. When
it all needs more than `maxPages`, drop in order: DONE items (except ticked in
the last 30 minutes or in the current aisle), then shorten names to 16
characters, then unticked items from the end of the store order. `mo` counts
what was left off; each aisle's `left` counts all its unticked items, so the
watch can show "+N on phone".

## Test vectors

`testdata/sync/<name>.json`: `{name, group, description, cap?, outboxCap?,
batchSize?, epoch?, steps}`. A step is `{"do": …}` or `{"expect": {…}}`.

Actions: `phone.add {name, contribs}`, `phone.addContribution {item}`,
`phone.removeContribution {item}`, `phone.tick {item}`, `phone.untick {item}`,
`phone.delete {item}`, `phone.rekey {from, to}`, `phone.newEpoch {epoch}`,
`phone.push`, `watch.hello`, `watch.toggle {item}`, `watch.send`,
`watch.timeout`, `watch.rawOps {ops}`, `deliver {to, index?, n?}`,
`deliverAll {to}`, `drop {to, index?}`, `dup {to, index?}`, `settle`. Any step
may set `clockMin`.

Expectations: `phone.done`, `phone.uncovered`, `phone.ticks`, `phone.lastSeq`,
`watch.view`, `watch.items`, `watch.outbox`, `watch.seqs`, `watch.ackedThrough`,
`watch.nextSeq`, `watch.epoch`, `watch.listRev`, `watch.listRevIsPhones`,
`watch.result`, `watch.notice`, `watch.toggled`, `watch.helloDue`,
`queue.phone`, `queue.watch`, `converged`.
