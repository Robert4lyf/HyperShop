using Toybox.Application;
using Toybox.Attention;
using Toybox.Graphics;
using Toybox.Lang;
using Toybox.System;
using Toybox.WatchUi;

//
// Every screen, buttons only (5 buttons, no touch).
//
// The list is one screen: every item in store walking order under thin aisle
// headings, ticked items sunk to a "Ticked" section at the end. A tick moves
// the focus to the next item to get, past any heading, so a shop is mostly
// Start, walk, Start. Settings is pushed from the last row; Back from the list
// leaves the app. Only a message ("Sync with phone") is pushed on top.
//
// The menu has no focus getter, so a snapshot that only changes ticks updates
// the rows in place; one that adds or drops items rebuilds the list.
//
class Screens {

    private const VIBRATE_KEY = "vb";
    private const DEBOUNCE_MS = 700;
    private const SUB_CHARS = 18;
    //! Rows: the focused one is taller, for a name wrapped onto two lines and its sub-line.
    private const ROW_H = 48;
    private const FOCUS_ROW_H = 92;

    private enum Screen { LIST, SETTINGS }

    var link as PhoneLink;
    var vibrate as Lang.Boolean = true;

    private var _screen as Screen = LIST;
    private var _menu as WatchUi.Menu2 or Null = null;
    //! The item ids and aisles the open list was built with, to tell a tick
    //! from a change of items when a snapshot arrives.
    private var _builtItems as Lang.Array<Lang.Number> = [] as Lang.Array<Lang.Number>;
    private var _builtAisles as Lang.Array<Lang.Number> = [] as Lang.Array<Lang.Number>;
    private var _lastPress as Lang.Number = -DEBOUNCE_MS;
    //! The item ticked last, for the "Undo" row.
    private var _lastTicked as Lang.Number = -1;

    function initialize(phoneLink as PhoneLink) {
        link = phoneLink;
        var stored = null;
        try {
            stored = Application.Storage.getValue(VIBRATE_KEY);
        } catch (e) {
        }
        vibrate = !(stored instanceof Lang.Boolean) || (stored as Lang.Boolean);
        link.setOnChange(method(:onSyncChanged));
    }

    function initialView() as [WatchUi.Views, WatchUi.InputDelegates] {
        return [listMenu(null), new ListDelegate(self)];
    }

    // --- the list

    //! Builds the list with [focusId] focused, or the first item to get when null or gone.
    private function listMenu(focusId as Lang.Number or Null) as WatchUi.Menu2 {
        var sync = link.sync;
        var view = sync.view();
        var menu = new WatchUi.CustomMenu(ROW_H, Graphics.COLOR_BLACK, {
            :title => new AisleTitle(text(Rez.Strings.AppName)),
            :focusItemHeight => FOCUS_ROW_H
        });
        menu.addItem(new TextRow(:status, statusLine(), noticeLine()));
        var rows = 1;
        var focus = -1;
        var firstOpen = -1;
        _builtItems = [] as Lang.Array<Lang.Number>;
        _builtAisles = [] as Lang.Array<Lang.Number>;
        var done = [] as Lang.Array<Lang.Array>;
        for (var a = 0; a < sync.aisles.size(); a++) {
            if (!showAisle(a, view)) {
                continue;
            }
            _builtAisles.add(a);
            var open = [] as Lang.Array<Lang.Array>;
            for (var i = 0; i < sync.items.size(); i++) {
                var item = sync.items[i];
                if ((item[3] as Lang.Number) != a) {
                    continue;
                }
                if (view.get(item[0] as Lang.Number) == true) {
                    done.add(item);
                } else {
                    open.add(item);
                }
            }
            var c = counts(a, view);
            if (open.size() == 0 && c[2] == 0) {
                continue;
            }
            menu.addItem(new HeaderRow("a" + a, sync.aisles[a][1] as Lang.String, aisleSub(a, view)));
            rows += 1;
            for (var i = 0; i < open.size(); i++) {
                var id = open[i][0] as Lang.Number;
                menu.addItem(new ItemRow(id, open[i][1] as Lang.String, itemSub(open[i]), false));
                _builtItems.add(id);
                if (firstOpen < 0) {
                    firstOpen = rows;
                }
                if (focusId != null && id == focusId) {
                    focus = rows;
                }
                rows += 1;
            }
        }
        if (sync.hasSnapshot && firstOpen < 0 && sync.items.size() > 0) {
            menu.addItem(new HeaderRow("alldone", text(Rez.Strings.AllDone), null));
            rows += 1;
        }
        if (done.size() > 0) {
            menu.addItem(new HeaderRow("ticked", text(Rez.Strings.Ticked), "" + done.size()));
            rows += 1;
            for (var i = 0; i < done.size(); i++) {
                var id = done[i][0] as Lang.Number;
                menu.addItem(new ItemRow(id, done[i][1] as Lang.String, itemSub(done[i]), true));
                _builtItems.add(id);
                if (focusId != null && id == focusId) {
                    focus = rows;
                }
                rows += 1;
            }
        }
        if (_lastTicked >= 0) {
            menu.addItem(new TextRow(:undo, Lang.format(text(Rez.Strings.UndoItem), [nameOf(_lastTicked)]), null));
            rows += 1;
        }
        menu.addItem(new TextRow(:settings, text(Rez.Strings.Settings), null));
        menu.setFocus(focus >= 0 ? focus : (firstOpen >= 0 ? firstOpen : 0));
        _menu = menu;
        _screen = LIST;
        return menu;
    }

    function showList(focusId as Lang.Number or Null) as Void {
        WatchUi.switchToView(listMenu(focusId), new ListDelegate(self), WatchUi.SLIDE_IMMEDIATE);
    }

    //! [view] is sync.view(), computed once by the caller for every row.
    private function showAisle(a as Lang.Number, view as Lang.Dictionary<Lang.Number, Lang.Boolean>) as Lang.Boolean {
        var sync = link.sync;
        if (sync.aisles[a][3] as Lang.Boolean) {
            return false;
        }
        return counts(a, view)[1] > 0 || (sync.aisles[a][2] as Lang.Number) > 0;
    }

    //! [unticked on the watch, items on the watch, left only on the phone].
    private function counts(a as Lang.Number, view as Lang.Dictionary<Lang.Number, Lang.Boolean>) as Lang.Array<Lang.Number> {
        var sync = link.sync;
        var unticked = 0;
        var shown = 0;
        var snapshotUnticked = 0;
        for (var i = 0; i < sync.items.size(); i++) {
            var item = sync.items[i];
            if ((item[3] as Lang.Number) != a) {
                continue;
            }
            shown += 1;
            if (!(item[4] as Lang.Boolean)) {
                snapshotUnticked += 1;
            }
            if (view.get(item[0] as Lang.Number) != true) {
                unticked += 1;
            }
        }
        var onPhone = (sync.aisles[a][2] as Lang.Number) - snapshotUnticked;
        return [unticked, shown, onPhone > 0 ? onPhone : 0];
    }

    private function aisleSub(a as Lang.Number, view as Lang.Dictionary<Lang.Number, Lang.Boolean>) as Lang.String {
        var c = counts(a, view);
        if (c[0] == 0 && c[2] == 0) {
            return text(Rez.Strings.AisleDone);
        }
        if (c[2] > 0) {
            return Lang.format(text(Rez.Strings.LeftOnPhone), [c[0] + c[2], c[2]]);
        }
        return Lang.format(text(Rez.Strings.Left), [c[0]]);
    }
    //! The status row: what a glance at the list most needs to say.
    private function statusLine() as Lang.String {
        if (link.versionMismatch) {
            return text(Rez.Strings.UpdateBoth);
        }
        if (link.isOffline()) {
            return text(Rez.Strings.SampleList);
        }
        var sync = link.sync;
        var waiting = sync.unsynced();
        if (waiting > 0) {
            return Lang.format(text(Rez.Strings.Unsynced), [waiting]);
        }
        if (!sync.hasSnapshot) {
            return text(Rez.Strings.WaitingForPhone);
        }
        if (sync.items.size() == 0 && sync.more == 0) {
            return text(Rez.Strings.ListEmpty);
        }
        var minutes = link.minutesSinceSync();
        if (minutes < 0) {
            return text(Rez.Strings.FromLastSync);
        }
        if (minutes == 0) {
            return text(Rez.Strings.SyncedNow);
        }
        return Lang.format(text(Rez.Strings.SyncedAgo), [minutes]);
    }

    private function noticeLine() as Lang.String or Null {
        var sync = link.sync;
        var notice = sync.notice;
        if (notice != null) {
            if (notice.equals("more")) {
                return text(Rez.Strings.NoticeMore);
            }
            if (notice.equals("changed")) {
                return text(Rez.Strings.NoticeChanged);
            }
            if (notice.equals("discarded")) {
                return Lang.format(text(Rez.Strings.NoticeDiscarded), [sync.noticeCount]);
            }
            if (notice.equals("storage")) {
                return text(Rez.Strings.NoticeStorage);
            }
        }
        if (link.versionMismatch) {
            return null;
        }
        if (!sync.hasSnapshot) {
            return text(Rez.Strings.OpenPhoneApp);
        }
        if (sync.items.size() == 0 && sync.more == 0) {
            return text(Rez.Strings.AddOnPhone);
        }
        return null;
    }

    private function itemSub(item as Lang.Array) as Lang.String or Null {
        if (link.sync.partial.hasKey(item[0] as Lang.Number)) {
            return text(Rez.Strings.OneMore);
        }
        var sub = item[2] as Lang.String;
        if (sub.length() == 0) {
            return null;
        }
        return sub.length() > SUB_CHARS ? sub.substring(0, SUB_CHARS) : sub;
    }

    function onListSelected(item as WatchUi.MenuItem) as Void {
        var id = item.getId();
        if (item instanceof ItemRow) {
            onItemSelected(item as ItemRow);
        } else if (id == :status) {
            // Asks the phone for the list now rather than at the next retry.
            link.refresh();
        } else if (id == :undo && _lastTicked >= 0) {
            var undone = _lastTicked;
            if (link.toggle(undone)) {
                _lastTicked = -1;
                buzz();
                showList(undone);
            }
        } else if (id == :settings) {
            showSettings();
        }
    }

    //! Select on an item. The row only changes once the tick has happened.
    private function onItemSelected(item as ItemRow) as Void {
        var id = item.getId() as Lang.Number;
        var now = System.getTimer();
        if (now - _lastPress < DEBOUNCE_MS) {
            return;
        }
        _lastPress = now;
        var wasDone = item.isDone();
        // The item to get after this one, before the tick moves it.
        var next = nextOpen(id);
        if (!link.toggle(id)) {
            WatchUi.pushView(
                new MessageView(text(link.sync.outboxFull() ? Rez.Strings.SyncWithPhone : Rez.Strings.NoticeChanged)),
                new MessageDelegate(),
                WatchUi.SLIDE_UP
            );
            return;
        }
        buzz();
        if (wasDone) {
            // Unticked: it goes back to its aisle, and the focus goes with it.
            showList(id);
            return;
        }
        _lastTicked = id;
        showList(next);
    }

    //! The open item listed after [id], else the one before it, else null.
    private function nextOpen(id as Lang.Number) as Lang.Number or Null {
        var view = link.sync.view();
        var open = [] as Lang.Array<Lang.Number>;
        for (var i = 0; i < _builtItems.size(); i++) {
            if (view.get(_builtItems[i]) != true) {
                open.add(_builtItems[i]);
            }
        }
        for (var i = 0; i < open.size(); i++) {
            if (open[i] == id) {
                if (i + 1 < open.size()) {
                    return open[i + 1];
                }
                return i > 0 ? open[i - 1] : null;
            }
        }
        return null;
    }

    private function buzz() as Void {
        if (vibrate && (Attention has :vibrate)) {
            Attention.vibrate([new Attention.VibeProfile(50, 80)] as Lang.Array<Attention.VibeProfile>);
        }
    }

    private function nameOf(id as Lang.Number) as Lang.String {
        var items = link.sync.items;
        for (var i = 0; i < items.size(); i++) {
            if ((items[i][0] as Lang.Number) == id) {
                return items[i][1] as Lang.String;
            }
        }
        return "";
    }

    // --- settings

    private function showSettings() as Void {
        var menu = new WatchUi.Menu2({:title => text(Rez.Strings.Settings)});
        menu.addItem(new WatchUi.ToggleMenuItem(text(Rez.Strings.Vibration), null, :vibrate, vibrate, {}));
        menu.addItem(new WatchUi.MenuItem(text(Rez.Strings.Refresh), null, :refresh, {}));
        Debug.addItems(menu);
        _menu = menu;
        _screen = SETTINGS;
        WatchUi.switchToView(menu, new SettingsDelegate(self), WatchUi.SLIDE_LEFT);
    }

    function onSettingSelected(item as WatchUi.MenuItem) as Void {
        var id = item.getId();
        if (id == :vibrate) {
            vibrate = (item as WatchUi.ToggleMenuItem).isEnabled();
            try {
                Application.Storage.setValue(VIBRATE_KEY, vibrate);
            } catch (e) {
            }
            return;
        }
        if (id == :refresh) {
            link.refresh();
        } else {
            Debug.onSelected(self, id);
            return;
        }
        showList(null);
    }

    // --- updates from the phone

    function onSyncChanged() as Void {
        var menu = _menu;
        if (menu == null || _screen != LIST) {
            return;
        }
        var view = link.sync.view();
        if (!sameItems(view)) {
            // Items came or went: there is no place worth keeping.
            showList(null);
            return;
        }
        // Only ticks changed (here or on the phone): shown in place, sorted at the next tick.
        var status = menu.getItem(0);
        if (status instanceof TextRow) {
            (status as TextRow).setText(statusLine(), noticeLine());
        }
        var items = link.sync.items;
        for (var i = 0; i < items.size(); i++) {
            var id = items[i][0] as Lang.Number;
            var index = menu.findItemById(id);
            if (index < 0) {
                continue;
            }
            var row = menu.getItem(index);
            if (row instanceof ItemRow) {
                (row as ItemRow).setState(view.get(id) == true, itemSub(items[i]));
            }
        }
        WatchUi.requestUpdate();
    }

    //! The same items and aisles as the open list was built with, in any order.
    private function sameItems(view as Lang.Dictionary<Lang.Number, Lang.Boolean>) as Lang.Boolean {
        var sync = link.sync;
        var aisles = 0;
        for (var a = 0; a < sync.aisles.size(); a++) {
            if (showAisle(a, view)) {
                if (_builtAisles.indexOf(a) < 0) {
                    return false;
                }
                aisles += 1;
            }
        }
        if (aisles != _builtAisles.size() || sync.items.size() != _builtItems.size()) {
            return false;
        }
        for (var i = 0; i < sync.items.size(); i++) {
            if (_builtItems.indexOf(sync.items[i][0] as Lang.Number) < 0) {
                return false;
            }
        }
        return true;
    }

    function text(id as Lang.ResourceId) as Lang.String {
        return WatchUi.loadResource(id) as Lang.String;
    }
}

class ListDelegate extends WatchUi.Menu2InputDelegate {

    private var _screens as Screens;

    function initialize(screens as Screens) {
        Menu2InputDelegate.initialize();
        _screens = screens;
    }

    function onSelect(item as WatchUi.MenuItem) as Void {
        _screens.onListSelected(item);
    }
}

class SettingsDelegate extends WatchUi.Menu2InputDelegate {

    private var _screens as Screens;

    function initialize(screens as Screens) {
        Menu2InputDelegate.initialize();
        _screens = screens;
    }

    function onSelect(item as WatchUi.MenuItem) as Void {
        _screens.onSettingSelected(item);
    }

    function onBack() as Void {
        _screens.showList(null);
    }
}

//! A line of text over the menu; Back or Select dismisses it.
class MessageView extends WatchUi.View {

    private var _text as Lang.String;

    function initialize(message as Lang.String) {
        View.initialize();
        _text = message;
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        dc.drawText(
            dc.getWidth() / 2,
            dc.getHeight() / 2,
            Graphics.FONT_SMALL,
            _text,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER
        );
    }
}

class MessageDelegate extends WatchUi.BehaviorDelegate {

    function initialize() {
        BehaviorDelegate.initialize();
    }

    function onSelect() as Lang.Boolean {
        WatchUi.popView(WatchUi.SLIDE_DOWN);
        return true;
    }
}
