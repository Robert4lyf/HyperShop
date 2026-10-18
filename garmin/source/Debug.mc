using Toybox.Communications;
using Toybox.Graphics;
using Toybox.Lang;
using Toybox.Timer;
using Toybox.WatchUi;

//
// Debug-build tools on the settings menu: a 60-item sample list in 12 aisles,
// to check memory and speed without a phone, and the payload probe, which
// measures the largest message that gets through each way (Hand-off B).
//
// The (:debug) module is compiled into builds without --release, which is
// what tools/build_watch.sh makes; tools/package_watch.sh builds --release
// and gets the empty (:release) module instead.
//
(:debug)
module Debug {

    function addItems(menu as WatchUi.Menu2) as Void {
        menu.addItem(new WatchUi.MenuItem("Load sample (60)", null, :sample, {}));
        menu.addItem(new WatchUi.MenuItem("Back to phone list", null, :phone, {}));
        menu.addItem(new WatchUi.MenuItem("Payload probe", null, :probe, {}));
    }

    function onSelected(screens as Screens, id as Lang.Object or Null) as Void {
        if (id == :sample) {
            screens.link.useSample(sample());
        } else if (id == :phone) {
            screens.link.useSample(null);
        } else if (id == :probe) {
            var view = new ProbeView(screens.link);
            WatchUi.pushView(view, new ProbeDelegate(view), WatchUi.SLIDE_UP);
            view.begin();
            return;
        }
        screens.showAisles(-1);
    }

    //! 12 aisles of 5 items, with sub-labels as long as the phone sends.
    function sample() as WatchSync {
        var names = [
            "Fruit & veg", "Bakery", "Meat", "Fish", "Dairy & eggs", "Chilled",
            "Frozen", "Tins & jars", "Pasta & rice", "Baking", "Drinks", "Household"
        ] as Lang.Array<Lang.String>;
        var aisles = [] as Lang.Array<Lang.Array>;
        var items = [] as Lang.Array<Lang.Array>;
        for (var a = 0; a < names.size(); a++) {
            aisles.add([names[a], names[a], 5, false]);
            for (var i = 0; i < 5; i++) {
                var id = a * 5 + i + 1;
                items.add([id, "Sample item " + id, "2 × 400 g tins", a, i == 4, 1]);
            }
        }
        var sync = new WatchSync(false);
        sync.outboxCap = 1000;
        sync.useSnapshot(aisles, items);
        return sync;
    }
}

(:release)
module Debug {

    function addItems(menu as WatchUi.Menu2) as Void {
    }

    function onSelected(screens as Screens, id as Lang.Object or Null) as Void {
    }
}

//
// The payload probe. Phone → watch first: the watch asks for replies of
// growing size and keeps the largest that arrives. Then watch → phone: it
// sends growing padding and the phone reports what it received. Each way
// stops after two sizes in a row get no answer. Sizes are the phone's
// PayloadSize estimate, the same measure PayloadCap uses.
//
(:debug)
class ProbeView extends WatchUi.View {

    private const SIZES = [128, 256, 384, 512, 768, 1024, 1536, 2048, 3072, 4096] as Lang.Array<Lang.Number>;
    private const WAIT_MS = 8000;

    private var _link as PhoneLink;
    private var _timer as Timer.Timer = new Timer.Timer();
    // 0 = phone → watch, 1 = watch → phone, 2 = finished.
    private var _phase as Lang.Number = 0;
    private var _index as Lang.Number = 0;
    private var _misses as Lang.Number = 0;
    private var _bestDown as Lang.Number = 0;
    private var _bestUp as Lang.Number = 0;
    private var _waiting as Lang.Boolean = false;

    function initialize(link as PhoneLink) {
        View.initialize();
        _link = link;
    }

    function begin() as Void {
        _link.probeHandler = method(:onReply);
        next();
    }

    function end() as Void {
        _timer.stop();
        _link.probeHandler = null;
    }

    private function next() as Void {
        if (_misses >= 2 || _index >= SIZES.size()) {
            _phase += 1;
            _index = 0;
            _misses = 0;
        }
        if (_phase >= 2) {
            end();
            WatchUi.requestUpdate();
            return;
        }
        var size = SIZES[_index];
        var payload = {
            Protocol.KEY_VERSION => Protocol.VERSION,
            Protocol.KEY_TYPE => Protocol.OUT_PROBE,
            Protocol.KEY_EPOCH => _link.sync.epoch,
            Protocol.KEY_INSTALL_ID => _link.sync.installId,
            Protocol.KEY_PROBE_SIZE => _phase == 0 ? size : 0
        };
        if (_phase == 1) {
            payload.put(Protocol.KEY_PAD, pad(size - 48));
        }
        _waiting = true;
        _link.send(payload);
        _timer.start(method(:onTimeout), WAIT_MS, false);
        WatchUi.requestUpdate();
    }

    private function pad(n as Lang.Number) as Lang.String {
        var s = "";
        while (s.length() + 16 <= n) {
            s += "xxxxxxxxxxxxxxxx";
        }
        while (s.length() < n) {
            s += "x";
        }
        return s;
    }

    function onReply(data as Lang.Dictionary) as Void {
        if (!_waiting) {
            return;
        }
        _waiting = false;
        _timer.stop();
        var up = data.get(Protocol.KEY_PROBE_SIZE);
        var down = data.get(Protocol.KEY_SIZE_DOWN);
        if (_phase == 0 && down instanceof Lang.Number && (down as Lang.Number) > _bestDown) {
            _bestDown = down as Lang.Number;
        }
        if (_phase == 1 && up instanceof Lang.Number && (up as Lang.Number) > _bestUp) {
            _bestUp = up as Lang.Number;
        }
        _misses = 0;
        _index += 1;
        next();
    }

    function onTimeout() as Void {
        if (!_waiting) {
            return;
        }
        _waiting = false;
        _misses += 1;
        _index += 1;
        next();
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        var cx = dc.getWidth() / 2;
        var status = _phase >= 2 ? "Done" : (_phase == 0 ? "Testing phone>watch" : "Testing watch>phone");
        if (_phase < 2) {
            status += " " + SIZES[_index];
        }
        dc.drawText(cx, 50, Graphics.FONT_XTINY, status, Graphics.TEXT_JUSTIFY_CENTER);
        dc.drawText(cx, 100, Graphics.FONT_SMALL, "To watch: " + _bestDown + " B", Graphics.TEXT_JUSTIFY_CENTER);
        dc.drawText(cx, 140, Graphics.FONT_SMALL, "To phone: " + _bestUp + " B", Graphics.TEXT_JUSTIFY_CENTER);
    }
}

(:debug)
class ProbeDelegate extends WatchUi.BehaviorDelegate {

    private var _view as ProbeView;

    function initialize(view as ProbeView) {
        BehaviorDelegate.initialize();
        _view = view;
    }

    function onBack() as Lang.Boolean {
        _view.end();
        WatchUi.popView(WatchUi.SLIDE_DOWN);
        return true;
    }
}
