using Toybox.Application;
using Toybox.Lang;
using Toybox.Test;

//
// What the traces cannot reach: input the phone never sends, and the order
// things are written to Storage in.
//

(:test)
function aMalformedPageChangesNothing(logger as Test.Logger) as Lang.Boolean {
    var sync = fresh();
    sync.receive(page(1, [[1, "Onion", "", 0, false, 1]]));
    Test.assertEqual(sync.listRev, 1);

    // An item pointing past the aisles, a wrong-typed field, a page outside
    // its count: each is dropped whole, and the cached list stays.
    sync.receive(page(2, [[1, "Onion", "", 5, false, 1]]));
    sync.receive(page(3, [[1, 7, "", 0, false, 1]]));
    var outside = page(4, [] as Lang.Array);
    outside.put(Protocol.KEY_PAGE, 3);
    sync.receive(outside);
    Test.assertEqual(sync.listRev, 1);
    Test.assertEqual(sync.items.size(), 1);
    return true;
}

(:test)
function nextSeqIsWrittenBeforeTheBatchIsSent(logger as Test.Logger) as Lang.Boolean {
    var sync = fresh();
    sync.receive(page(1, [[1, "Onion", "", 0, false, 1]]));
    Test.assert(sync.toggle(1, 100));
    var batch = sync.nextBatch();
    Test.assert(batch != null);

    // The watch dies before any reply: a restart must not reuse seq 1.
    var restarted = new WatchSync(true);
    Test.assertEqual(restarted.nextSeq, 2);
    Test.assertEqual(restarted.outbox.size(), 1);
    Test.assertEqual(restarted.outbox[0][4] as Lang.Number, 1);
    return true;
}

(:test)
function aFullOutboxRefusesTheTick(logger as Test.Logger) as Lang.Boolean {
    var sync = fresh();
    sync.outboxCap = 2;
    sync.receive(page(1, [[1, "A", "", 0, false, 1], [2, "B", "", 0, false, 1], [3, "C", "", 0, false, 1]]));
    Test.assert(sync.toggle(1, 100));
    Test.assert(sync.toggle(2, 100));
    Test.assert(!sync.toggle(3, 100));
    Test.assert(sync.viewOf(3) == false);
    // Undoing an unsent tick still works: it collapses rather than queues.
    Test.assert(sync.toggle(2, 101));
    Test.assertEqual(sync.outbox.size(), 1);
    return true;
}

function fresh() as WatchSync {
    Application.Storage.clearValues();
    var sync = new WatchSync(true);
    sync.reset(77);
    sync.receive({
        Protocol.KEY_VERSION => Protocol.VERSION,
        Protocol.KEY_TYPE => Protocol.IN_SNAPSHOT,
        Protocol.KEY_EPOCH => 1,
        Protocol.KEY_LIST_REV => 0,
        Protocol.KEY_ACKED_THROUGH => 0,
        Protocol.KEY_PAGE => 0,
        Protocol.KEY_PAGES => 0
    });
    return sync;
}

function page(rev as Lang.Number, items as Lang.Array) as Lang.Dictionary {
    return {
        Protocol.KEY_VERSION => Protocol.VERSION,
        Protocol.KEY_TYPE => Protocol.IN_SNAPSHOT,
        Protocol.KEY_EPOCH => 1,
        Protocol.KEY_LIST_REV => rev,
        Protocol.KEY_ACKED_THROUGH => 0,
        Protocol.KEY_PAGE => 0,
        Protocol.KEY_PAGES => 1,
        Protocol.KEY_AISLES => [0, ["PRODUCE", "Produce", 1, false]],
        Protocol.KEY_ITEMS => items
    };
}
