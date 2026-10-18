using Toybox.Application;
using Toybox.Graphics;
using Toybox.Lang;
using Toybox.WatchUi;

//
// HyperShop for Connect IQ.
//
// The phone owns the list; the watch shows a paged copy of it, aisle by
// aisle, and sends each tick until the phone acknowledges it
// (docs/garmin-protocol.md). Everything works with the five buttons alone.
//
// The app class also runs the glance, in the glance's 32 KB, so nothing is
// built in initialize(): the link and the screens start in getInitialView,
// which a glance never calls.
//
class HyperShopApp extends Application.AppBase {

    private var _link as Lang.Object or Null = null;

    function initialize() {
        AppBase.initialize();
    }

    // Foreground only: these touch the link and screens, which a glance never
    // loads, and the glance check cannot tell that getInitialView never runs
    // in one.
    (:typecheck(disableGlanceCheck))
    function onStop(state as Lang.Dictionary or Null) as Void {
        if (_link != null) {
            (_link as PhoneLink).stop();
        }
    }

    (:typecheck(disableGlanceCheck))
    function getInitialView() as [WatchUi.Views] or [WatchUi.Views, WatchUi.InputDelegates] {
        var link = new PhoneLink(new WatchSync(true));
        _link = link;
        link.start();
        return new Screens(link).initialView();
    }

    (:glance)
    function getGlanceView() as [WatchUi.GlanceView] or [WatchUi.GlanceView, WatchUi.GlanceViewDelegate] or Null {
        return [new HyperShopGlance()];
    }
}

//
// "14 left · Produce next", from the summary the app writes to Storage `g`
// whenever the list changes (Screens.writeGlance). Nothing else is loaded.
//
(:glance)
class HyperShopGlance extends WatchUi.GlanceView {

    function initialize() {
        GlanceView.initialize();
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        var line = "HyperShop";
        try {
            var g = Application.Storage.getValue("g");
            if (g instanceof Lang.Array && (g as Lang.Array).size() == 2) {
                var left = (g as Lang.Array)[0];
                var next = (g as Lang.Array)[1];
                if (left instanceof Lang.Number && next instanceof Lang.String) {
                    if ((left as Lang.Number) == 0) {
                        line = "List done";
                    } else if ((next as Lang.String).length() > 0) {
                        line = left + " left · " + next + " next";
                    } else {
                        line = left + " left";
                    }
                }
            }
        } catch (e) {
        }
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(0, dc.getHeight() / 2, Graphics.FONT_TINY, line, Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
    }
}
