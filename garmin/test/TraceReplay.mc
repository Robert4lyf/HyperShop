using Toybox.Application;
using Toybox.Lang;
using Toybox.Test;

//
// Replays a trace from SyncTraces.mc through WatchSync and compares every
// state with the Kotlin model's. After each state it also reloads a second
// WatchSync from Storage, which must agree on everything Storage keeps: a
// watch restarted at any point in a vector resumes where it was.
//
module TraceReplay {

    function run(logger as Test.Logger, name as Lang.String, steps as Lang.Array) as Lang.Boolean {
        Application.Storage.clearValues();
        var sync = new WatchSync(true);
        sync.reset(77);
        for (var i = 0; i < steps.size(); i++) {
            var step = steps[i] as Lang.Array;
            var kind = step[0] as Lang.String;
            var problem = null as Lang.String or Null;
            if (kind.equals("config")) {
                sync.batchSize = step[1] as Lang.Number;
                sync.outboxCap = step[2] as Lang.Number;
            } else if (kind.equals("toggle")) {
                var got = sync.toggle(step[1] as Lang.Number, step[2] as Lang.Number);
                problem = differ("toggle", got, at(step, 3));
            } else if (kind.equals("send")) {
                var batch = sync.nextBatch();
                var ops = batch != null ? (batch as Lang.Dictionary).get(Protocol.KEY_OPS) as Lang.Object or Null : null;
                problem = differ("ops", ops, at(step, 1));
            } else if (kind.equals("hello")) {
                var hello = sync.hello(step[1] as Lang.Number);
                var got = [
                    hello.get(Protocol.KEY_LIST_REV), hello.get(Protocol.KEY_ACKED_THROUGH), hello.get(Protocol.KEY_EPOCH)
                ] as Lang.Array;
                problem = differ("hello lr/at/ep", got, step.slice(2, 5));
            } else if (kind.equals("timeout")) {
                sync.timeout();
            } else if (kind.equals("recv")) {
                sync.receive(step[1] as Lang.Dictionary);
            } else if (kind.equals("state")) {
                problem = compare(sync, step, true);
                if (problem == null) {
                    var reloaded = new WatchSync(true);
                    reloaded.batchSize = sync.batchSize;
                    reloaded.outboxCap = sync.outboxCap;
                    problem = compare(reloaded, step, false);
                    if (problem != null) {
                        problem = "after reload: " + problem;
                    }
                }
            } else {
                problem = "unknown step";
            }
            if (problem != null) {
                logger.error(name + " step " + i + " " + kind + ": " + problem);
                return false;
            }
        }
        return true;
    }

    //! Null when [sync] matches the state; [live] also checks what lives only in memory.
    function compare(sync as WatchSync, state as Lang.Array, live as Lang.Boolean) as Lang.String or Null {
        var seqs = [] as Lang.Array<Lang.Number>;
        for (var i = 0; i < sync.outbox.size(); i++) {
            seqs.add(sync.outbox[i][4] as Lang.Number);
        }
        var problem = differ("view", sync.view(), at(state, 1));
        if (problem == null) {
            problem = differ("outbox seqs", seqs, at(state, 2));
        }
        if (problem == null) {
            problem = differ("ackedThrough", sync.ackedThrough, at(state, 3));
        }
        if (problem == null) {
            problem = differ("nextSeq", sync.nextSeq, at(state, 4));
        }
        if (problem == null) {
            problem = differ("epoch", sync.epoch, at(state, 5));
        }
        if (problem == null) {
            problem = differ("listRev", sync.listRev, at(state, 6));
        }
        if (problem != null || !live) {
            return problem;
        }
        problem = differ("helloDue", sync.helloDue, at(state, 7));
        if (problem == null) {
            problem = differ("notice", sync.notice, at(state, 8));
        }
        if (problem == null && at(state, 8) != null) {
            problem = differ("notice count", sync.noticeCount, at(state, 9));
        }
        if (problem == null) {
            problem = differ("results", sync.results, at(state, 10));
        }
        return problem;
    }

    function at(list as Lang.Array, i as Lang.Number) as Lang.Object or Null {
        return list[i] as Lang.Object or Null;
    }

    function differ(what as Lang.String, got as Lang.Object or Null, want as Lang.Object or Null) as Lang.String or Null {
        if (same(got, want)) {
            return null;
        }
        return what + " " + str(got) + ", expected " + str(want);
    }

    function str(x as Lang.Object or Null) as Lang.String {
        return x == null ? "null" : x.toString();
    }

    //! Deep equality over what a payload can hold.
    function same(a as Lang.Object or Null, b as Lang.Object or Null) as Lang.Boolean {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        if (a instanceof Lang.String) {
            return b instanceof Lang.String && (a as Lang.String).equals(b);
        }
        if (a instanceof Lang.Array) {
            if (!(b instanceof Lang.Array) || (a as Lang.Array).size() != (b as Lang.Array).size()) {
                return false;
            }
            for (var i = 0; i < (a as Lang.Array).size(); i++) {
                if (!same((a as Lang.Array)[i] as Lang.Object or Null, (b as Lang.Array)[i] as Lang.Object or Null)) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Lang.Dictionary) {
            if (!(b instanceof Lang.Dictionary) || (a as Lang.Dictionary).size() != (b as Lang.Dictionary).size()) {
                return false;
            }
            var keys = (a as Lang.Dictionary).keys();
            for (var i = 0; i < keys.size(); i++) {
                if (!(b as Lang.Dictionary).hasKey(keys[i])) {
                    return false;
                }
                if (!same((a as Lang.Dictionary).get(keys[i]) as Lang.Object or Null, (b as Lang.Dictionary).get(keys[i]) as Lang.Object or Null)) {
                    return false;
                }
            }
            return true;
        }
        return a == b;
    }
}
