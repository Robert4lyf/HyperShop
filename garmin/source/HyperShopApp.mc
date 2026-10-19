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

    private var _link as PhoneLink or Null = null;

    function initialize() {
        AppBase.initialize();
    }

    function onStop(state as Lang.Dictionary or Null) as Void {
        if (_link != null) {
            (_link as PhoneLink).stop();
        }
    }

    function getInitialView() as [WatchUi.Views] or [WatchUi.Views, WatchUi.InputDelegates] {
        var link = new PhoneLink(new WatchSync(true));
        _link = link;
        link.start();
        return new Screens(link).initialView();
    }
}
