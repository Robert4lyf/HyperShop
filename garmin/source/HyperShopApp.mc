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
class HyperShopApp extends Application.AppBase {

    // Built here rather than in onStart so neither is ever null.
    private var _link as PhoneLink;

    function initialize() {
        AppBase.initialize();
        _link = new PhoneLink(new WatchSync(true));
    }

    function onStart(state as Lang.Dictionary or Null) as Void {
        _link.start();
    }

    function onStop(state as Lang.Dictionary or Null) as Void {
        _link.stop();
    }

    function getInitialView() as [WatchUi.Views] or [WatchUi.Views, WatchUi.InputDelegates] {
        return [new WaitingView()];
    }
}

//! Shown until the first snapshot arrives.
class WaitingView extends WatchUi.View {

    function initialize() {
        View.initialize();
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        dc.drawText(
            dc.getWidth() / 2,
            dc.getHeight() / 2,
            Graphics.FONT_SMALL,
            WatchUi.loadResource(Rez.Strings.WaitingForPhone) as Lang.String,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER
        );
    }
}
