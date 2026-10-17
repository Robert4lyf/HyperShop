#!/usr/bin/env python3
"""Exhaustive model checker for the HyperShop phone <-> watch protocol.

An independent model of the rules in docs/garmin-protocol.md (the Kotlin
PhoneSyncModel / WatchSyncModel implement the same rules and replay
testdata/sync). Explores every interleaving under small bounds:

  - the channel reorders freely (any queued message may be delivered next),
    and may drop one message and duplicate one;
  - the watch user toggles items; the phone adds contributions, ticks and
    unticks, pushes snapshots, and may be restored once (a new epoch);
  - the watch times out (once its batch or the reply is lost), resends
    (go-back-N), and says hello when due.

Safety invariants, on every reachable state:
  S1  seqs in the watch outbox are a contiguous, assigned prefix from ackedThrough+1
  S2  the phone never applies a seq the watch has not issued
  S3  every tick covers at least one contribution (ticks never create stock)
  S4  a watch tick covers only contributions with createdListRev <= its seenListRev
  S5  no contribution is covered by a tick that no longer exists (untick restores exactly)
  S6  an op the watch dropped as acked "ok" was applied by the phone (same epoch)
  S7  each (epoch, seq) is applied by the phone at most once (an epoch-rejected op is not applied)

Liveness, from every reachable state: with faults off, the watch saying hello
(reconnect), then delivering FIFO and retrying, the system goes quiet with the
watch's view equal to the phone's DONE flags (C1).

Exit status 0 when no violation is found. --small is the bound verify.sh uses.
"""

from __future__ import annotations

import argparse
import sys
from collections import deque
from dataclasses import dataclass, replace

OK, NOOP, PARTIAL, STILL_DONE, EPOCH = "ok", "noop", "partial", "still_done", "epoch"


@dataclass(frozen=True)
class Bounds:
    items: int = 2
    toggles: int = 3
    phone_actions: int = 2
    restores: int = 1
    drops: int = 1
    dups: int = 1
    queue: int = 2
    batch: int = 2


# ---- state -------------------------------------------------------------------------
# contribution: (cid, createdLR, coveredBy)   tick: (tid, item, fromWatch, seen, ep, seq)
# outbox op: (item, done, seen, seq)  seq 0 = unassigned
# messages: ("hello", ep, at) | ("ops", ep, ((seq,item,done,seen),...)) | ("snap", ep, lr, at, ax, full, done)


@dataclass(frozen=True)
class S:
    # phone
    p_ep: int
    p_lr: int
    contribs: tuple  # per item: tuple of contributions
    ticks: tuple
    last_seq: int  # -1: install unknown
    results: tuple  # ((seq, result), ...) last 4
    next_tick: int
    next_cid: int
    applied: frozenset  # ghost: (ep, seq, result)
    # watch
    w_ep: int
    next_seq: int
    acked: int
    outbox: tuple
    inflight: bool
    cache_lr: int
    cache: tuple | None
    settled: tuple  # ((item, done, lr), ...)
    hello_due: bool
    acked_ok: frozenset  # ghost: (ep, seq)
    # channel
    to_phone: tuple
    to_watch: tuple
    # budgets
    toggles: int
    phone_actions: int
    restores: int
    drops: int
    dups: int


def initial(b: Bounds) -> S:
    contribs = tuple(((i + 1, 1, 0),) for i in range(b.items))
    return S(1, 1, contribs, (), -1, (), 1, b.items + 1, frozenset(),
             0, 1, 0, (), False, 0, None, (), True, frozenset(),
             (), (), b.toggles, b.phone_actions, b.restores, b.drops, b.dups)


# ---- phone ---------------------------------------------------------------------------
def done_of(cs: tuple) -> bool:
    return bool(cs) and all(c[2] for c in cs)


def phone_done(s: S) -> tuple:
    return tuple(done_of(cs) for cs in s.contribs)


def snap(s: S, full: bool) -> tuple:
    ax = tuple(r for r in s.results if r[1] != OK)
    return ("snap", s.p_ep, s.p_lr, max(s.last_seq, 0), ax, full, phone_done(s) if full else None)


def cover(s: S, item: int, pred, tick) -> S:
    cs = tuple((c[0], c[1], tick[0]) if (not c[2] and pred(c)) else c for c in s.contribs[item])
    contribs = s.contribs[:item] + (cs,) + s.contribs[item + 1:]
    return replace(s, contribs=contribs, ticks=s.ticks + (tick,), next_tick=s.next_tick + 1, p_lr=s.p_lr + 1)


def undo(s: S, tick) -> S:
    item = tick[1]
    cs = tuple((c[0], c[1], 0) if c[2] == tick[0] else c for c in s.contribs[item])
    contribs = s.contribs[:item] + (cs,) + s.contribs[item + 1:]
    return replace(s, contribs=contribs, ticks=tuple(t for t in s.ticks if t != tick), p_lr=s.p_lr + 1)


def apply_op(s: S, ep: int, op) -> tuple[S, str]:
    seq, item, done, seen = op
    if ep != s.p_ep:
        return s, EPOCH
    if done:
        open_ = [c for c in s.contribs[item] if not c[2]]
        to_cover = [c for c in open_ if c[1] <= seen]
        later = len(open_) - len(to_cover)
        if not to_cover:
            return s, (PARTIAL if later else NOOP)
        s = cover(s, item, lambda c: c[1] <= seen, (s.next_tick, item, True, seen, ep, seq))
        return s, (PARTIAL if later else OK)
    mine = [t for t in s.ticks if t[1] == item and t[2]]
    if not mine:
        return s, NOOP
    s = undo(s, mine[-1])
    return s, (STILL_DONE if done_of(s.contribs[item]) else OK)


def phone_receive(s: S, m) -> S:
    if m[0] == "hello":
        _, ep, at = m
        last = at if s.last_seq < 0 else max(s.last_seq, at)
        s = replace(s, last_seq=last)
        return replace(s, to_watch=s.to_watch + (snap(s, True),))
    _, ep, ops = m
    last = s.last_seq if s.last_seq >= 0 else ops[0][0] - 1
    s = replace(s, last_seq=last)
    changed = False
    for op in ops:
        seq = op[0]
        if seq <= s.last_seq:
            continue
        if seq != s.last_seq + 1:
            break
        s, r = apply_op(s, ep, op)
        if r != EPOCH and (ep, seq) in {(a[0], a[1]) for a in s.applied if a[2] != EPOCH}:
            raise Violation("S7", f"seq {seq} in epoch {ep} applied twice")
        changed |= r in (OK, PARTIAL, STILL_DONE)
        s = replace(s, last_seq=seq, results=(s.results + ((seq, r),))[-4:], applied=s.applied | {(ep, seq, r)})
    return replace(s, to_watch=s.to_watch + (snap(s, changed),))


# ---- watch ---------------------------------------------------------------------------
def view(s: S) -> tuple | None:
    if s.cache is None:
        return None
    v = list(s.cache)
    for item, done, _ in s.settled:
        v[item] = done
    for item, done, _, _ in s.outbox:
        v[item] = done
    return tuple(v)


def toggle(s: S, item: int) -> S | None:
    v = view(s)
    if v is None:
        return None
    desired = not v[item]
    unsent = [i for i, o in enumerate(s.outbox) if o[0] == item and o[3] == 0]
    if unsent:
        ob = s.outbox[: unsent[-1]] + s.outbox[unsent[-1] + 1:]
        base = s.cache[item]
        for it, d, _ in s.settled:
            if it == item:
                base = d
        for o in ob:
            if o[0] == item:
                base = o[1]
        s = replace(s, outbox=ob)
        if base == desired:
            return s
    return replace(s, outbox=s.outbox + ((item, desired, s.cache_lr, 0),))


def send(s: S, b: Bounds) -> S | None:
    if s.inflight or not s.outbox:
        return None
    ob = list(s.outbox)
    nxt = s.next_seq
    for i in range(min(b.batch, len(ob))):
        if ob[i][3] == 0:
            ob[i] = (ob[i][0], ob[i][1], ob[i][2], nxt)
            nxt += 1
    batch = tuple((o[3], o[0], o[1], o[2]) for o in ob[: b.batch])
    return replace(s, outbox=tuple(ob), next_seq=nxt, inflight=True, to_phone=s.to_phone + (("ops", s.w_ep, batch),))


def watch_receive(s: S, m) -> S:
    _, ep, lr, at, ax, full, done = m
    if ep != s.w_ep:
        s = replace(s, w_ep=ep, outbox=(), cache=None, cache_lr=0, settled=(), inflight=False,
                    acked=s.next_seq - 1, hello_due=True)
    if at > s.acked or s.inflight:
        settled = list(s.settled)
        acked_ok = set(s.acked_ok)
        for o in s.outbox:
            if o[3] and o[3] <= at:
                r = dict(ax).get(o[3], OK)
                if r == OK:
                    settled.append((o[0], o[1], lr))
                    acked_ok.add((s.w_ep, o[3]))
        s = replace(s, outbox=tuple(o for o in s.outbox if not (o[3] and o[3] <= at)), acked=max(s.acked, at),
                    inflight=False, settled=tuple(settled), acked_ok=frozenset(acked_ok))
    if not full:
        return replace(s, hello_due=True) if lr > s.cache_lr else s
    if lr < s.cache_lr:
        return s
    return replace(s, cache=done, cache_lr=lr, settled=tuple(x for x in s.settled if x[2] > lr))


def hello(s: S) -> S:
    return replace(s, hello_due=False, to_phone=s.to_phone + (("hello", s.w_ep, s.acked),))


# ---- exploration ---------------------------------------------------------------------
class Violation(Exception):
    def __init__(self, code: str, msg: str):
        super().__init__(f"{code}: {msg}")
        self.code = code


def successors(s: S, b: Bounds):
    if len(s.to_phone) < b.queue:
        if s.toggles > 0 and s.cache is not None:
            for i in range(b.items):
                t = toggle(s, i)
                if t is not None:
                    yield f"toggle {i}", replace(t, toggles=s.toggles - 1)
        n = send(s, b)
        if n is not None:
            yield "send", n
        if s.hello_due:
            yield "hello", hello(s)
    # Timeouts are long next to delivery: one fires only once the batch or its
    # reply is gone (dropped), not while either is still on the way. Stale and
    # duplicate deliveries are still explored, through reordering and `dup`.
    if s.inflight and not any(m[0] == "ops" for m in s.to_phone) and not s.to_watch:
        yield "timeout", replace(s, inflight=False)
    for i, m in enumerate(s.to_phone):
        rest = s.to_phone[:i] + s.to_phone[i + 1:]
        if len(s.to_watch) < b.queue:
            yield f"phone<-{m[0]}", phone_receive(replace(s, to_phone=rest), m)
        if s.drops:
            yield f"drop->phone {m[0]}", replace(s, to_phone=rest, drops=s.drops - 1)
        if s.dups and len(s.to_phone) < b.queue:
            yield f"dup->phone {m[0]}", replace(s, to_phone=s.to_phone + (m,), dups=s.dups - 1)
    for i, m in enumerate(s.to_watch):
        rest = s.to_watch[:i] + s.to_watch[i + 1:]
        yield f"watch<-snap lr={m[2]}", watch_receive(replace(s, to_watch=rest), m)
        if s.drops:
            yield "drop->watch", replace(s, to_watch=rest, drops=s.drops - 1)
        if s.dups and len(s.to_watch) < b.queue:
            yield "dup->watch", replace(s, to_watch=s.to_watch + (m,), dups=s.dups - 1)
    if s.phone_actions:
        pa = s.phone_actions - 1
        for i in range(b.items):
            cs = s.contribs[i]
            nc = cs + ((s.next_cid, s.p_lr + 1, 0),)
            yield f"phone add contribution {i}", replace(
                s, contribs=s.contribs[:i] + (nc,) + s.contribs[i + 1:], next_cid=s.next_cid + 1, p_lr=s.p_lr + 1, phone_actions=pa)
            if any(not c[2] for c in cs):
                yield f"phone tick {i}", replace(cover(s, i, lambda c: True, (s.next_tick, i, False, -1, s.p_ep, 0)), phone_actions=pa)
            mine = [t for t in s.ticks if t[1] == i]
            if mine:
                yield f"phone untick {i}", replace(undo(s, mine[-1]), phone_actions=pa)
        if len(s.to_watch) < b.queue:
            yield "phone push", replace(s, to_watch=s.to_watch + (snap(s, True),), phone_actions=pa)
    if s.restores:
        yield "phone restore", replace(s, p_ep=s.p_ep + 1, p_lr=s.p_lr + 1, last_seq=-1, results=(), restores=s.restores - 1)


def check(s: S) -> None:
    assigned = [o[3] for o in s.outbox if o[3]]
    if assigned != list(range(s.acked + 1, s.acked + 1 + len(assigned))) or any(o[3] for o in s.outbox[len(assigned):]):
        raise Violation("S1", f"outbox seqs {[o[3] for o in s.outbox]} after acked {s.acked}")
    if s.last_seq >= s.next_seq:
        raise Violation("S2", f"phone lastSeq {s.last_seq} >= watch nextSeq {s.next_seq}")
    tick_ids = {t[0] for t in s.ticks}
    for t in s.ticks:
        covered = [c for c in s.contribs[t[1]] if c[2] == t[0]]
        if not covered:
            raise Violation("S3", f"tick {t} covers nothing")
        if t[2] and any(c[1] > t[3] for c in covered):
            raise Violation("S4", f"watch tick {t} covers a contribution newer than it saw")
    for cs in s.contribs:
        for c in cs:
            if c[2] and c[2] not in tick_ids:
                raise Violation("S5", f"contribution {c} covered by a missing tick")
    applied_ok = {(a[0], a[1]) for a in s.applied if a[2] == OK}
    if not s.acked_ok <= applied_ok:
        raise Violation("S6", f"watch dropped {sorted(s.acked_ok - applied_ok)} as ok; phone applied {sorted(applied_ok)}")


def drain(s: S, b: Bounds) -> S:
    """Faults off; the watch reconnects (hello); deliver FIFO and retry until quiet."""
    s = replace(s, drops=0, dups=0, toggles=0, phone_actions=0, restores=0)
    s = hello(s)
    for _ in range(60):
        check(s)
        if s.to_phone:
            s = phone_receive(replace(s, to_phone=s.to_phone[1:]), s.to_phone[0])
        elif s.to_watch:
            s = watch_receive(replace(s, to_watch=s.to_watch[1:]), s.to_watch[0])
        elif s.hello_due:
            s = hello(s)
        elif s.outbox:
            s = send(replace(s, inflight=False), b) or s
        else:
            return s
    raise Violation("C1", "did not go quiet")


def explore(b: Bounds, progress: bool = False) -> int:
    start = initial(b)
    seen = {start: None}
    q = deque([start])
    popped = 0
    while q:
        s = q.popleft()
        popped += 1
        if progress and popped % 5000 == 0:
            print(f"  … {popped} explored, {len(seen)} seen, queue {len(q)}", file=sys.stderr, flush=True)
        try:
            check(s)
            d = drain(s, b)
            if view(d) != phone_done(d) or d.outbox or d.cache_lr != d.p_lr:
                raise Violation("C1", f"after drain: watch {view(d)} lr={d.cache_lr}, phone {phone_done(d)} lr={d.p_lr}")
        except Violation as v:
            print(f"VIOLATION {v}", file=sys.stderr)
            trace = []
            cur = s
            while cur is not None:
                parent = seen[cur]
                if parent is None:
                    break
                trace.append(parent[1])
                cur = parent[0]
            for step in reversed(trace):
                print(f"  {step}", file=sys.stderr)
            return 1
        for label, n in successors(s, b):
            if n not in seen:
                seen[n] = (s, label)
                q.append(n)
    print(f"sync model: {len(seen)} states, 0 violations ({b})")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--small", action="store_true", help="the verify.sh bounds (about 30 s)")
    ap.add_argument("--progress", action="store_true")
    a = ap.parse_args()
    # One fault family per run keeps each space small; together they cover
    # loss, duplication and restore. The default (no --small) adds a run with
    # a restore *and* phone-side changes (about 420k states, 2 minutes).
    runs = [
        Bounds(toggles=2, phone_actions=1, restores=0, drops=1, dups=0),
        Bounds(toggles=2, phone_actions=0, restores=0, drops=0, dups=1),
        Bounds(toggles=2, phone_actions=0, restores=1, drops=1, dups=0),
    ]
    if not a.small:
        runs.append(Bounds(toggles=2, phone_actions=1, restores=1, drops=0, dups=0))
    for b in runs:
        if explore(b, a.progress):
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
