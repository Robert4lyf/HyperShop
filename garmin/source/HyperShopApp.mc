using Toybox.Application;
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
        return new Screens(_link).initialView();
    }
}
