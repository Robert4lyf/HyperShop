using Toybox.Application;
using Toybox.Lang;

//
// The watch's side of the sync protocol (docs/garmin-protocol.md, "Watch
// rules"): a port of android/core/.../sync/rules/WatchSyncModel.kt. The
// Kotlin model is the reference; test/SyncTraces.mc is generated from it and
// replays every testdata/sync vector through this class.
//
// No Communications here and no clock: PhoneLink sends what this returns and
// passes the time in, so the tests drive it directly.
//
// Persistence order matters. nextSeq is written before a batch is handed out,
// and the outbox before a tick is shown. Every Storage access is wrapped: a
// full store must cost the cache, never the app.
//
// Shapes, all plain arrays so they store as they are:
//   item    [id, name, sub, aisleIdx, done, rev]
//   aisle   [code, name, left, hidden]
//   op      [itemId, done, seenListRev, ts, seq]   seq 0 = not sent yet
//   settled [itemId, done, listRevAtAck]
//
class WatchSync {

    // Storage keys, each well under the 32 KB a value may take: a snapshot is
    // at most 16 pages of a few hundred bytes.
    private const META_KEY = "m";
    private const OUTBOX_KEY = "o";
    private const SNAPSHOT_KEY = "s";

    // How many listRevs may be half-assembled at once. Older ones are
    // superseded anyway; this bounds the memory a lost page can hold.
    private const MAX_ASSEMBLING = 2;
    private const MAX_RESULTS = 64;

    // Set by the vectors' cap/outboxCap/batchSize; the defaults are the app's.
    var batchSize as Lang.Number = 8;
    var outboxCap as Lang.Number = 32;

    var installId as Lang.Number = 0;
    var epoch as Lang.Number = 0;

    //! Never reset, not even by an epoch change.
    var nextSeq as Lang.Number = 1;
    var ackedThrough as Lang.Number = 0;
    var inFlight as Lang.Boolean = false;
    var outbox as Lang.Array<Lang.Array> = [] as Lang.Array<Lang.Array>;

    //! The last complete snapshot.
    var listRev as Lang.Number = 0;
    var aisles as Lang.Array<Lang.Array> = [] as Lang.Array<Lang.Array>;
    var items as Lang.Array<Lang.Array> = [] as Lang.Array<Lang.Array>;
    //! Items the phone left off the snapshot ("+N on phone").
    var more as Lang.Number = 0;
    //! Whether a snapshot has ever been completed for this epoch.
    var hasSnapshot as Lang.Boolean = false;

    //! The watch should say hello: it adopted a new epoch, or an ack said the
    //! phone's list is newer than the cache. Cleared by hello().
    var helloDue as Lang.Boolean = false;

    //! The latest notice: "more" (a partial tick), "changed" (rejected),
    //! "discarded" (noticeCount ticks dropped by an epoch change). The serial
    //! goes up with each, so a view can tell a new one from the one it showed.
    var notice as Lang.String or Null = null;
    var noticeCount as Lang.Number = 0;
    var noticeSerial as Lang.Number = 0;

    //! Results by seq, the newest MAX_RESULTS.
    var results as Lang.Dictionary<Lang.Number, Lang.String> = {} as Lang.Dictionary<Lang.Number, Lang.String>;

    //! Items whose last tick came back partial: "1 more" on their row until
    //! they are ticked again or a snapshot shows them done.
    var partial as Lang.Dictionary<Lang.Number, Lang.Boolean> = {} as Lang.Dictionary<Lang.Number, Lang.Boolean>;

    private var _settled as Lang.Array<Lang.Array> = [] as Lang.Array<Lang.Array>;
    private var _assembling as Lang.Dictionary<Lang.Number, Lang.Dictionary> = {} as Lang.Dictionary<Lang.Number, Lang.Dictionary>;
    private var _persist as Lang.Boolean;

    //! [persist] false keeps everything in memory (the debug sample).
    function initialize(persist as Lang.Boolean) {
        _persist = persist;
        if (persist) {
            load();
        }
    }

    //! A fresh install with this id, for tests and first launch.
    function reset(id as Lang.Number) as Void {
        installId = id;
        epoch = 0;
        nextSeq = 1;
        ackedThrough = 0;
        inFlight = false;
        outbox = [] as Lang.Array<Lang.Array>;
        listRev = 0;
        aisles = [] as Lang.Array<Lang.Array>;
        items = [] as Lang.Array<Lang.Array>;
        more = 0;
        hasSnapshot = false;
        helloDue = false;
        notice = null;
        noticeCount = 0;
        results = {} as Lang.Dictionary<Lang.Number, Lang.String>;
        partial = {} as Lang.Dictionary<Lang.Number, Lang.Boolean>;
        _settled = [] as Lang.Array<Lang.Array>;
        _assembling = {} as Lang.Dictionary<Lang.Number, Lang.Dictionary>;
        saveMeta();
        saveOutbox();
        saveSnapshot();
    }

    //! A list that came from nowhere: the debug sample. Never persisted.
    function useSnapshot(newAisles as Lang.Array<Lang.Array>, newItems as Lang.Array<Lang.Array>) as Void {
        aisles = newAisles;
        items = newItems;
        listRev = 1;
        hasSnapshot = true;
    }

    // --- outbound

    function hello(nowSeconds as Lang.Number) as Lang.Dictionary {
        helloDue = false;
        return {
            Protocol.KEY_VERSION => Protocol.VERSION,
            Protocol.KEY_TYPE => Protocol.OUT_HELLO,
            Protocol.KEY_EPOCH => epoch,
            Protocol.KEY_INSTALL_ID => installId,
            Protocol.KEY_LIST_REV => listRev,
            Protocol.KEY_WATCH_NOW => nowSeconds,
            Protocol.KEY_ACKED_THROUGH => ackedThrough
        };
    }

    function bye() as Lang.Dictionary {
        return {
            Protocol.KEY_VERSION => Protocol.VERSION,
            Protocol.KEY_TYPE => Protocol.OUT_BYE,
            Protocol.KEY_EPOCH => epoch,
            Protocol.KEY_INSTALL_ID => installId
        };
    }

    function outboxFull() as Lang.Boolean {
        return outbox.size() >= outboxCap;
    }

    //! The user selected an item: tick it if unticked, untick if ticked.
    //! False when the item is unknown or the outbox is full ("Sync with
    //! phone"); the caller then leaves the row as it was.
    function toggle(itemId as Lang.Number, nowSeconds as Lang.Number) as Lang.Boolean {
        var shown = viewOf(itemId);
        if (shown == null) {
            return false;
        }
        var desired = !(shown as Lang.Boolean);
        // Collapse with an op for the same item that has not been sent yet.
        var unsent = -1;
        for (var i = outbox.size() - 1; i >= 0; i--) {
            if ((outbox[i][0] as Lang.Number) == itemId && (outbox[i][4] as Lang.Number) == 0) {
                unsent = i;
                break;
            }
        }
        if (unsent >= 0) {
            outbox = removeAt(outbox, unsent);
            if (baseline(itemId) == desired) {
                saveOutbox();
                return true;
            }
        } else if (outboxFull()) {
            return false;
        }
        outbox.add([itemId, desired, listRev, nowSeconds, 0]);
        partial.remove(itemId);
        saveOutbox();
        return true;
    }

    //! The next batch to send, or null (nothing pending, or one in flight).
    //! Seqs are assigned and written down before the batch is returned.
    function nextBatch() as Lang.Dictionary or Null {
        if (inFlight || outbox.size() == 0) {
            return null;
        }
        var n = outbox.size() < batchSize ? outbox.size() : batchSize;
        var ops = [] as Lang.Array<Lang.Array>;
        for (var i = 0; i < n; i++) {
            var op = outbox[i];
            // Assigned ops are always a prefix of the outbox, so seqs stay
            // contiguous.
            if ((op[4] as Lang.Number) == 0) {
                op[4] = nextSeq;
                nextSeq += 1;
            }
            ops.add([op[4], op[0], op[1], op[2], op[3]]);
        }
        saveOutbox();
        inFlight = true;
        return {
            Protocol.KEY_VERSION => Protocol.VERSION,
            Protocol.KEY_TYPE => Protocol.OUT_OPS,
            Protocol.KEY_EPOCH => epoch,
            Protocol.KEY_INSTALL_ID => installId,
            Protocol.KEY_OPS => ops
        };
    }

    //! No reply in time. PhoneLink backs off before asking for the next batch,
    //! which resends from ackedThrough + 1.
    function timeout() as Void {
        inFlight = false;
    }

    // --- inbound

    //! One snapshot page (or ack-only page). Anything malformed is ignored
    //! whole. Returns whether something visible may have changed.
    function receive(payload as Lang.Dictionary) as Lang.Boolean {
        var type = payload.get(Protocol.KEY_TYPE);
        if (!(type instanceof Lang.String) || !(type as Lang.String).equals(Protocol.IN_SNAPSHOT)) {
            return false;
        }
        var ep = payload.get(Protocol.KEY_EPOCH);
        var lr = payload.get(Protocol.KEY_LIST_REV);
        var at = payload.get(Protocol.KEY_ACKED_THROUGH);
        var pg = payload.get(Protocol.KEY_PAGE);
        var pgs = payload.get(Protocol.KEY_PAGES);
        if (!(ep instanceof Lang.Number) || !(lr instanceof Lang.Number) || !(at instanceof Lang.Number) ||
            !(pg instanceof Lang.Number) || !(pgs instanceof Lang.Number)) {
            return false;
        }
        var pageRev = lr as Lang.Number;
        var page = pg as Lang.Number;
        var pages = pgs as Lang.Number;
        var acked = at as Lang.Number;
        if (pageRev < 0 || acked < 0 || page < 0 || pages < 0 || (pages > 0 && page >= pages)) {
            return false;
        }
        var ax = exceptions(payload.get(Protocol.KEY_ACK_EXCEPTIONS));
        if (ax == null) {
            return false;
        }

        if ((ep as Lang.Number) != epoch) {
            adoptEpoch(ep as Lang.Number);
        }

        if (acked > ackedThrough || inFlight) {
            settle(acked, ax as Lang.Dictionary<Lang.Number, Lang.String>, pageRev);
        }

        // Ack-only pages (pgs = 0) carry nothing else.
        if (pages == 0) {
            if (pageRev > listRev) {
                helloDue = true;
            }
            return true;
        }
        if (pageRev < listRev) {
            return true;
        }
        var held = _assembling.get(pageRev);
        if (held == null) {
            if (_assembling.size() >= MAX_ASSEMBLING) {
                dropOldestAssembling();
            }
            held = {} as Lang.Dictionary<Lang.Number, Lang.Dictionary>;
            _assembling.put(pageRev, held as Lang.Dictionary);
        }
        (held as Lang.Dictionary<Lang.Number, Lang.Dictionary>).put(page, payload);
        for (var i = 0; i < pages; i++) {
            if (!(held as Lang.Dictionary).hasKey(i)) {
                return true;
            }
        }
        adoptSnapshot(pageRev, pages, held as Lang.Dictionary<Lang.Number, Lang.Dictionary>);
        return true;
    }

    //! `ax` as seq → result, or null when it is malformed. Absent is empty.
    private function exceptions(raw as Lang.Object or Null) as Lang.Dictionary<Lang.Number, Lang.String> or Null {
        var out = {} as Lang.Dictionary<Lang.Number, Lang.String>;
        if (raw == null) {
            return out;
        }
        if (!(raw instanceof Lang.Array)) {
            return null;
        }
        var list = raw as Lang.Array;
        for (var i = 0; i < list.size(); i++) {
            var pair = list[i];
            if (!(pair instanceof Lang.Array) || (pair as Lang.Array).size() != 2) {
                return null;
            }
            var seq = (pair as Lang.Array)[0];
            var result = (pair as Lang.Array)[1];
            if (!(seq instanceof Lang.Number) || !(result instanceof Lang.String)) {
                return null;
            }
            out.put(seq as Lang.Number, result as Lang.String);
        }
        return out;
    }

    //! Ops through [acked] leave the outbox with their results (default ok).
    private function settle(acked as Lang.Number, ax as Lang.Dictionary<Lang.Number, Lang.String>, pageRev as Lang.Number) as Void {
        var kept = [] as Lang.Array<Lang.Array>;
        for (var i = 0; i < outbox.size(); i++) {
            var op = outbox[i];
            var seq = op[4] as Lang.Number;
            if (seq == 0 || seq > acked) {
                kept.add(op);
                continue;
            }
            var result = ax.get(seq);
            var r = result != null ? result as Lang.String : Protocol.RESULT_OK;
            putResult(seq, r);
            var itemId = op[0] as Lang.Number;
            if (r.equals(Protocol.RESULT_OK)) {
                _settled.add([itemId, op[1], pageRev]);
            } else if (r.equals(Protocol.RESULT_PARTIAL)) {
                partial.put(itemId, true);
                say("more", 1);
            } else if (r.equals(Protocol.RESULT_REJECTED)) {
                say("changed", 1);
            }
        }
        outbox = kept;
        if (acked > ackedThrough) {
            ackedThrough = acked;
        }
        inFlight = false;
        saveOutbox();
        saveMeta();
    }

    private function putResult(seq as Lang.Number, result as Lang.String) as Void {
        results.put(seq, result);
        if (results.size() > MAX_RESULTS) {
            var oldest = seq;
            var keys = results.keys();
            for (var i = 0; i < keys.size(); i++) {
                if ((keys[i] as Lang.Number) < oldest) {
                    oldest = keys[i] as Lang.Number;
                }
            }
            results.remove(oldest);
        }
    }

    private function say(code as Lang.String, count as Lang.Number) as Void {
        notice = code;
        noticeCount = count;
        noticeSerial += 1;
    }

    private function dropOldestAssembling() as Void {
        var keys = _assembling.keys();
        var oldest = keys[0] as Lang.Number;
        for (var i = 1; i < keys.size(); i++) {
            if ((keys[i] as Lang.Number) < oldest) {
                oldest = keys[i] as Lang.Number;
            }
        }
        _assembling.remove(oldest);
    }

    //! Pages 0 … pages−1 of one listRev are all here: they replace the cache.
    //! A page that does not parse voids the snapshot rather than half of it.
    private function adoptSnapshot(rev as Lang.Number, pages as Lang.Number, held as Lang.Dictionary<Lang.Number, Lang.Dictionary>) as Void {
        var newAisles = [] as Lang.Array<Lang.Array>;
        var newItems = [] as Lang.Array<Lang.Array>;
        var newMore = 0;
        for (var p = 0; p < pages; p++) {
            var page = held.get(p) as Lang.Dictionary;
            var ai = page.get(Protocol.KEY_AISLES);
            if (ai != null) {
                if (!(ai instanceof Lang.Array) || (ai as Lang.Array).size() == 0 || !((ai as Lang.Array)[0] instanceof Lang.Number)) {
                    _assembling.remove(rev);
                    return;
                }
                var list = ai as Lang.Array;
                var base = list[0] as Lang.Number;
                for (var i = 1; i < list.size(); i++) {
                    var a = aisle(list[i] as Lang.Object or Null);
                    if (a == null || base + i - 1 != newAisles.size()) {
                        _assembling.remove(rev);
                        return;
                    }
                    newAisles.add(a as Lang.Array);
                }
            }
            var it = page.get(Protocol.KEY_ITEMS);
            if (!(it instanceof Lang.Array)) {
                _assembling.remove(rev);
                return;
            }
            var raw = it as Lang.Array;
            for (var i = 0; i < raw.size(); i++) {
                var item = item(raw[i] as Lang.Object or Null);
                if (item == null) {
                    _assembling.remove(rev);
                    return;
                }
                newItems.add(item as Lang.Array);
            }
            var mo = page.get(Protocol.KEY_MORE);
            if (mo instanceof Lang.Number && (mo as Lang.Number) > newMore) {
                newMore = mo as Lang.Number;
            }
        }
        for (var i = 0; i < newItems.size(); i++) {
            if ((newItems[i][3] as Lang.Number) >= newAisles.size()) {
                _assembling.remove(rev);
                return;
            }
        }
        aisles = newAisles;
        items = newItems;
        more = newMore;
        listRev = rev;
        hasSnapshot = true;
        var keys = _assembling.keys();
        for (var i = 0; i < keys.size(); i++) {
            if ((keys[i] as Lang.Number) <= rev) {
                _assembling.remove(keys[i]);
            }
        }
        var kept = [] as Lang.Array<Lang.Array>;
        for (var i = 0; i < _settled.size(); i++) {
            if ((_settled[i][2] as Lang.Number) > listRev) {
                kept.add(_settled[i]);
            }
        }
        _settled = kept;
        // A partial row that the phone now shows done needs no "1 more".
        for (var i = 0; i < items.size(); i++) {
            if (items[i][4] as Lang.Boolean) {
                partial.remove(items[i][0] as Lang.Number);
            }
        }
        saveSnapshot();
        saveMeta();
        saveOutbox();
    }

    private function aisle(raw as Lang.Object or Null) as Lang.Array or Null {
        if (!(raw instanceof Lang.Array)) {
            return null;
        }
        var a = raw as Lang.Array;
        if (a.size() != 4 || !(a[0] instanceof Lang.String) || !(a[1] instanceof Lang.String) ||
            !(a[2] instanceof Lang.Number) || !(a[3] instanceof Lang.Boolean)) {
            return null;
        }
        return a;
    }

    private function item(raw as Lang.Object or Null) as Lang.Array or Null {
        if (!(raw instanceof Lang.Array)) {
            return null;
        }
        var a = raw as Lang.Array;
        if (a.size() != 6 || !(a[0] instanceof Lang.Number) || !(a[1] instanceof Lang.String) ||
            !(a[2] instanceof Lang.String) || !(a[3] instanceof Lang.Number) ||
            !(a[4] instanceof Lang.Boolean) || !(a[5] instanceof Lang.Number)) {
            return null;
        }
        if ((a[3] as Lang.Number) < 0) {
            return null;
        }
        return a;
    }

    //! The phone's list was replaced (or restored): the outbox is void, the
    //! cache is cleared, nextSeq is kept. The hello this schedules tells the
    //! phone every seq issued so far is settled.
    private function adoptEpoch(newEpoch as Lang.Number) as Void {
        var dropped = outbox.size();
        if (dropped > 0) {
            say("discarded", dropped);
        }
        outbox = [] as Lang.Array<Lang.Array>;
        epoch = newEpoch;
        items = [] as Lang.Array<Lang.Array>;
        aisles = [] as Lang.Array<Lang.Array>;
        more = 0;
        listRev = 0;
        hasSnapshot = false;
        _assembling = {} as Lang.Dictionary<Lang.Number, Lang.Dictionary>;
        _settled = [] as Lang.Array<Lang.Array>;
        partial = {} as Lang.Dictionary<Lang.Number, Lang.Boolean>;
        inFlight = false;
        helloDue = true;
        ackedThrough = nextSeq - 1;
        saveOutbox();
        saveSnapshot();
        saveMeta();
    }

    // --- the view

    //! What the screen shows for every item: the snapshot, then the acked-ok
    //! ops no snapshot shows yet, then the outbox.
    function view() as Lang.Dictionary<Lang.Number, Lang.Boolean> {
        var v = {} as Lang.Dictionary<Lang.Number, Lang.Boolean>;
        for (var i = 0; i < items.size(); i++) {
            v.put(items[i][0] as Lang.Number, items[i][4] as Lang.Boolean);
        }
        overlay(v, _settled);
        overlay(v, outbox);
        return v;
    }

    private function overlay(v as Lang.Dictionary<Lang.Number, Lang.Boolean>, ops as Lang.Array<Lang.Array>) as Void {
        for (var i = 0; i < ops.size(); i++) {
            var id = ops[i][0] as Lang.Number;
            if (v.hasKey(id)) {
                v.put(id, ops[i][1] as Lang.Boolean);
            }
        }
    }

    function viewOf(itemId as Lang.Number) as Lang.Boolean or Null {
        return view().get(itemId);
    }

    //! The item as the view shows it without the unsent ops.
    private function baseline(itemId as Lang.Number) as Lang.Boolean or Null {
        var d = null as Lang.Boolean or Null;
        for (var i = 0; i < items.size(); i++) {
            if ((items[i][0] as Lang.Number) == itemId) {
                d = items[i][4] as Lang.Boolean;
                break;
            }
        }
        if (d == null) {
            return null;
        }
        for (var i = 0; i < _settled.size(); i++) {
            if ((_settled[i][0] as Lang.Number) == itemId) {
                d = _settled[i][1] as Lang.Boolean;
            }
        }
        for (var i = 0; i < outbox.size(); i++) {
            if ((outbox[i][0] as Lang.Number) == itemId && (outbox[i][4] as Lang.Number) != 0) {
                d = outbox[i][1] as Lang.Boolean;
            }
        }
        return d;
    }

    //! Ops waiting for the phone, sent or not.
    function unsynced() as Lang.Number {
        return outbox.size();
    }

    // --- storage

    private function load() as Void {
        var meta = read(META_KEY);
        if (meta instanceof Lang.Array && (meta as Lang.Array).size() == 5) {
            var m = meta as Lang.Array;
            installId = m[0] instanceof Lang.Number ? m[0] as Lang.Number : 0;
            epoch = m[1] instanceof Lang.Number ? m[1] as Lang.Number : 0;
            listRev = m[2] instanceof Lang.Number ? m[2] as Lang.Number : 0;
            ackedThrough = m[3] instanceof Lang.Number ? m[3] as Lang.Number : 0;
            more = m[4] instanceof Lang.Number ? m[4] as Lang.Number : 0;
        }
        var box = read(OUTBOX_KEY);
        if (box instanceof Lang.Array && (box as Lang.Array).size() == 3) {
            var b = box as Lang.Array;
            if (b[0] instanceof Lang.Number) {
                nextSeq = b[0] as Lang.Number;
            }
            outbox = arrays(b[1] as Lang.Object or Null, 5);
            _settled = arrays(b[2] as Lang.Object or Null, 3);
        }
        var snap = read(SNAPSHOT_KEY);
        if (snap instanceof Lang.Array && (snap as Lang.Array).size() == 2) {
            var s = snap as Lang.Array;
            aisles = arrays(s[0] as Lang.Object or Null, 4);
            items = arrays(s[1] as Lang.Object or Null, 6);
            hasSnapshot = items.size() > 0 || aisles.size() > 0;
        }
        // A cache that does not hold together is worth less than none: the
        // next hello brings the whole list back.
        for (var i = 0; i < items.size(); i++) {
            if (!(items[i][3] instanceof Lang.Number) || (items[i][3] as Lang.Number) >= aisles.size()) {
                items = [] as Lang.Array<Lang.Array>;
                aisles = [] as Lang.Array<Lang.Array>;
                listRev = 0;
                hasSnapshot = false;
                break;
            }
        }
    }

    private function arrays(raw as Lang.Object or Null, size as Lang.Number) as Lang.Array<Lang.Array> {
        var out = [] as Lang.Array<Lang.Array>;
        if (!(raw instanceof Lang.Array)) {
            return out;
        }
        var list = raw as Lang.Array;
        for (var i = 0; i < list.size(); i++) {
            if (list[i] instanceof Lang.Array && (list[i] as Lang.Array).size() == size) {
                out.add(list[i] as Lang.Array);
            }
        }
        return out;
    }

    private function saveMeta() as Void {
        write(META_KEY, [installId, epoch, listRev, ackedThrough, more]);
    }

    private function saveOutbox() as Void {
        write(OUTBOX_KEY, [nextSeq, outbox, _settled]);
    }

    private function saveSnapshot() as Void {
        write(SNAPSHOT_KEY, [aisles, items]);
    }

    private function read(key as Lang.String) as Lang.Object or Null {
        if (!_persist) {
            return null;
        }
        try {
            return Application.Storage.getValue(key);
        } catch (e) {
            return null;
        }
    }

    private function write(key as Lang.String, value as Lang.Array) as Void {
        if (!_persist) {
            return;
        }
        try {
            Application.Storage.setValue(key, value as Lang.Array<Application.Storage.ValueType>);
        } catch (e) {
            // Full or failing storage: the session goes on from memory.
        }
    }
}

//! A copy of [list] without index [index].
function removeAt(list as Lang.Array<Lang.Array>, index as Lang.Number) as Lang.Array<Lang.Array> {
    var out = [] as Lang.Array<Lang.Array>;
    for (var i = 0; i < list.size(); i++) {
        if (i != index) {
            out.add(list[i]);
        }
    }
    return out;
}
