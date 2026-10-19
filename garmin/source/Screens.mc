using Toybox.Application;
using Toybox.Attention;
using Toybox.Graphics;
using Toybox.Lang;
using Toybox.System;
using Toybox.WatchUi;

//
// Every screen, buttons only (5 buttons, no touch).
//
// One menu is on the view stack at a time: moving between the aisle list, an
// aisle and the "aisle done" prompt is switchToView, so Back from the aisle
// list leaves the app and the stack never grows. Only a message ("Sync with
// phone") is pushed on top, and Back pops it.
//
// The menus have no focus getter, so a snapshot arriving while one is open
// updates its rows in place rather than rebuilding it and losing the place.
//
class Screens {

    private const VIBRATE_KEY = "vb";
    private const GLANCE_KEY = "g";
    private const DEBOUNCE_MS = 700;
    private const SUB_CHARS = 18;
    //! An aisle's rows: the focused one is taller, for the larger name and its sub-line.
    private const ROW_H = 48;
    private const FOCUS_ROW_H = 72;

    private enum Screen { AISLES, AISLE, DONE, SETTINGS }

    var link as PhoneLink;
    var vibrate as Lang.Boolean = true;

    private var _screen as Screen = AISLES;
    private var _menu as WatchUi.Menu2 or Null = null;
    //! The aisle whose items are open, or whose "done" prompt is: its code,
    //! which survives a snapshot, and its index into sync.aisles, which is
    //! re-resolved from the code whenever a snapshot arrives.
    private var _aisleCode as Lang.String or Null = null;
    private var _aisle as Lang.Number = -1;
    //! The aisle indexes the open aisle list was built with.
    private var _aisleRows as Lang.Array<Lang.Number> = [] as Lang.Array<Lang.Number>;
    private var _lastPress as Lang.Number = -DEBOUNCE_MS;
    //! The item ticked last in the open aisle, for "Undo" on the done prompt.
    private var _lastTicked as Lang.Number = -1;
    //! The snapshot the glance summary was last written for.
    private var _glanceRev as Lang.Number = -1;
    private var _glanceEpoch as Lang.Number = -1;

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
        var menu = aisleMenu(0);
        return [menu, new AislesDelegate(self)];
    }

    // --- the aisle list

    private function aisleMenu(focus as Lang.Number) as WatchUi.Menu2 {
        var menu = new WatchUi.Menu2({:title => text(Rez.Strings.AppName)});
        menu.addItem(new WatchUi.MenuItem(statusLine(), noticeLine(), :status, {}));
        _aisleRows = [] as Lang.Array<Lang.Number>;
        var sync = link.sync;
        var view = sync.view();
        for (var a = 0; a < sync.aisles.size(); a++) {
            if (!showAisle(a, view)) {
                continue;
            }
            menu.addItem(new WatchUi.MenuItem(sync.aisles[a][1] as Lang.String, aisleSub(a, view), a, {}));
            _aisleRows.add(a);
        }
        menu.addItem(new WatchUi.MenuItem(text(Rez.Strings.Settings), null, :settings, {}));
        menu.setFocus(focus);
        _menu = menu;
        _screen = AISLES;
        writeGlance(view, false);
        return menu;
    }

    //! The index of the aisle with [code] in the current snapshot, or -1.
    private function aisleIndex(code as Lang.String or Null) as Lang.Number {
        if (code == null) {
            return -1;
        }
        var aisles = link.sync.aisles;
        for (var a = 0; a < aisles.size(); a++) {
            if ((aisles[a][0] as Lang.String).equals(code)) {
                return a;
            }
        }
        return -1;
    }

    function showAisles(focusAisle as Lang.Number) as Void {
        var focus = 0;
        var menu = aisleMenu(0);
        for (var i = 0; i < _aisleRows.size(); i++) {
            if (_aisleRows[i] == focusAisle) {
                focus = i + 1;
            }
        }
        menu.setFocus(focus);
        WatchUi.switchToView(menu, new AislesDelegate(self), WatchUi.SLIDE_RIGHT);
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

    function onAisleSelected(id as Lang.Object) as Void {
        if (id == :settings) {
            showSettings();
        } else if (id == :status) {
            // Asks the phone for the list now rather than at the next retry.
            link.refresh();
        } else if (id instanceof Lang.Number) {
            openAisle(id as Lang.Number, 0, WatchUi.SLIDE_LEFT);
        }
    }

    // --- one aisle

    //! Unticked first, ticked sunk to the bottom, each in store order.
    private function aisleItems(a as Lang.Number, view as Lang.Dictionary<Lang.Number, Lang.Boolean>) as Lang.Array<Lang.Array> {
        var sync = link.sync;
        var open = [] as Lang.Array<Lang.Array>;
        var done = [] as Lang.Array<Lang.Array>;
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
        return open.addAll(done);
    }

    function openAisle(a as Lang.Number, focus as Lang.Number, transition as WatchUi.SlideType) as Void {
        var sync = link.sync;
        var menu = new WatchUi.CustomMenu(ROW_H, Graphics.COLOR_BLACK, {
            :title => new AisleTitle(sync.aisles[a][1] as Lang.String),
            :focusItemHeight => FOCUS_ROW_H
        });
        var view = sync.view();
        var list = aisleItems(a, view);
        for (var i = 0; i < list.size(); i++) {
            var id = list[i][0] as Lang.Number;
            menu.addItem(new ItemRow(id, list[i][1] as Lang.String, itemSub(list[i]), view.get(id) == true));
        }
        if (list.size() > 0) {
            menu.setFocus(focus < list.size() ? focus : list.size() - 1);
        }
        var code = sync.aisles[a][0] as Lang.String;
        if (_aisleCode == null || !(_aisleCode as Lang.String).equals(code)) {
            _lastTicked = -1;
        }
        _aisle = a;
        _aisleCode = code;
        _menu = menu;
        _screen = AISLE;
        WatchUi.switchToView(menu, new AisleDelegate(self), transition);
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

    //! Select on a row. The row only changes once the tick has happened.
    function onItemSelected(item as ItemRow) as Void {
        var id = item.getId() as Lang.Number;
        var now = System.getTimer();
        if (now - _lastPress < DEBOUNCE_MS) {
            return;
        }
        _lastPress = now;
        if (_aisle < 0) {
            // The aisle left the list under the open screen.
            showAisles(-1);
            return;
        }
        var list = aisleItems(_aisle, link.sync.view());
        var index = 0;
        for (var i = 0; i < list.size(); i++) {
            if ((list[i][0] as Lang.Number) == id) {
                index = i;
            }
        }
        if (!link.toggle(id)) {
            WatchUi.pushView(
                new MessageView(text(link.sync.outboxFull() ? Rez.Strings.SyncWithPhone : Rez.Strings.NoticeChanged)),
                new MessageDelegate(),
                WatchUi.SLIDE_UP
            );
            return;
        }
        var view = link.sync.view();
        if (view.get(id) == true) {
            _lastTicked = id;
        }
        buzz();
        writeGlance(view, true);
        if (counts(_aisle, view)[0] == 0) {
            showDone(_aisle);
        } else {
            // The row sinks; the same position now holds the next one to get.
            openAisle(_aisle, index, WatchUi.SLIDE_IMMEDIATE);
        }
    }

    private function buzz() as Void {
        if (vibrate && (Attention has :vibrate)) {
            Attention.vibrate([new Attention.VibeProfile(50, 80)] as Lang.Array<Attention.VibeProfile>);
        }
    }

    // --- "aisle done"

    //! The next aisle in store order with something left, or -1.
    private function nextAisle(after as Lang.Number, view as Lang.Dictionary<Lang.Number, Lang.Boolean>) as Lang.Number {
        var sync = link.sync;
        for (var step = 1; step < sync.aisles.size(); step++) {
            var a = (after + step) % sync.aisles.size();
            if (showAisle(a, view) && counts(a, view)[0] > 0) {
                return a;
            }
        }
        return -1;
    }

    private function showDone(a as Lang.Number) as Void {
        var sync = link.sync;
        var menu = new WatchUi.Menu2({:title => Lang.format(text(Rez.Strings.AisleDoneTitle), [sync.aisles[a][1]])});
        var next = nextAisle(a, sync.view());
        if (next >= 0) {
            menu.addItem(new WatchUi.MenuItem(Lang.format(text(Rez.Strings.NextAisle), [sync.aisles[next][1]]), null, :next, {}));
        } else {
            menu.addItem(new WatchUi.MenuItem(text(Rez.Strings.AllDone), null, :aisles, {}));
        }
        if (_lastTicked >= 0) {
            menu.addItem(new WatchUi.MenuItem(Lang.format(text(Rez.Strings.UndoItem), [nameOf(_lastTicked)]), null, :undo, {}));
        }
        menu.addItem(new WatchUi.MenuItem(text(Rez.Strings.AllAisles), null, :aisles, {}));
        // Next is focused: the common case is one press.
        menu.setFocus(0);
        _aisle = a;
        _aisleCode = sync.aisles[a][0] as Lang.String;
        _menu = menu;
        _screen = DONE;
        WatchUi.switchToView(menu, new DoneDelegate(self), WatchUi.SLIDE_LEFT);
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

    function onDoneSelected(id as Lang.Object) as Void {
        if (_aisle < 0) {
            // The aisle left the list under the prompt.
            showAisles(-1);
            return;
        }
        if (id == :next) {
            var next = nextAisle(_aisle, link.sync.view());
            if (next >= 0) {
                openAisle(next, 0, WatchUi.SLIDE_LEFT);
                return;
            }
        } else if (id == :undo && _lastTicked >= 0) {
            var undone = _lastTicked;
            if (link.toggle(undone)) {
                _lastTicked = -1;
                writeGlance(link.sync.view(), true);
                openAisle(_aisle, 0, WatchUi.SLIDE_RIGHT);
                return;
            }
        }
        showAisles(_aisle);
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
        showAisles(-1);
    }

    // --- updates from the phone

    //! The view is computed once here and handed down: it walks every item
    //! and the outbox, and a refresh asks about every aisle.
    function onSyncChanged() as Void {
        var view = link.sync.view();
        writeGlance(view, false);
        var menu = _menu;
        if (menu == null) {
            return;
        }
        if (_screen == AISLES) {
            refreshAisles(menu, view);
        } else if (_screen == AISLE || _screen == DONE) {
            // A snapshot can reorder or drop aisles: the open one is found
            // again by code, and the aisle list takes over when it is gone.
            _aisle = aisleIndex(_aisleCode);
            if (_aisle < 0) {
                showAisles(-1);
                return;
            }
            if (_screen == AISLE) {
                refreshAisle(menu, view);
            }
        }
        WatchUi.requestUpdate();
    }

    private function refreshAisles(menu as WatchUi.Menu2, view as Lang.Dictionary<Lang.Number, Lang.Boolean>) as Void {
        var rows = [] as Lang.Array<Lang.Number>;
        for (var a = 0; a < link.sync.aisles.size(); a++) {
            if (showAisle(a, view)) {
                rows.add(a);
            }
        }
        if (!sameRows(rows)) {
            // A different set of aisles: there is no place worth keeping.
            WatchUi.switchToView(aisleMenu(0), new AislesDelegate(self), WatchUi.SLIDE_IMMEDIATE);
            return;
        }
        var status = menu.getItem(0);
        if (status != null) {
            status.setLabel(statusLine());
            status.setSubLabel(noticeLine());
        }
        for (var i = 0; i < rows.size(); i++) {
            var row = menu.getItem(i + 1);
            if (row != null) {
                row.setSubLabel(aisleSub(rows[i], view));
            }
        }
    }

    private function sameRows(rows as Lang.Array<Lang.Number>) as Lang.Boolean {
        if (rows.size() != _aisleRows.size()) {
            return false;
        }
        for (var i = 0; i < rows.size(); i++) {
            if (rows[i] != _aisleRows[i]) {
                return false;
            }
        }
        return true;
    }

    //! Ticks from the phone show in place; new rows wait for the next visit.
    private function refreshAisle(menu as WatchUi.Menu2, view as Lang.Dictionary<Lang.Number, Lang.Boolean>) as Void {
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
    }

    //! "14 left · Produce next", kept for a glance should the watch run one.
    //! A Storage write, so only when the counts can have moved: a snapshot
    //! was adopted (listRev or epoch changed) or, with [force], a tick here.
    private function writeGlance(view as Lang.Dictionary<Lang.Number, Lang.Boolean>, force as Lang.Boolean) as Void {
        var sync = link.sync;
        if (!force && sync.listRev == _glanceRev && sync.epoch == _glanceEpoch) {
            return;
        }
        var left = 0;
        var next = null as Lang.String or Null;
        for (var a = 0; a < sync.aisles.size(); a++) {
            if (!showAisle(a, view)) {
                continue;
            }
            var c = counts(a, view);
            left += c[0] + c[2];
            if (next == null && c[0] > 0) {
                next = sync.aisles[a][1] as Lang.String;
            }
        }
        try {
            Application.Storage.setValue(GLANCE_KEY, [left, next != null ? next : ""]);
        } catch (e) {
        }
        _glanceRev = sync.listRev;
        _glanceEpoch = sync.epoch;
    }

    function text(id as Lang.ResourceId) as Lang.String {
        return WatchUi.loadResource(id) as Lang.String;
    }
}

class AislesDelegate extends WatchUi.Menu2InputDelegate {

    private var _screens as Screens;

    function initialize(screens as Screens) {
        Menu2InputDelegate.initialize();
        _screens = screens;
    }

    function onSelect(item as WatchUi.MenuItem) as Void {
        var id = item.getId();
        if (id != null) {
            _screens.onAisleSelected(id);
        }
    }
}

class AisleDelegate extends WatchUi.Menu2InputDelegate {

    private var _screens as Screens;

    function initialize(screens as Screens) {
        Menu2InputDelegate.initialize();
        _screens = screens;
    }

    function onSelect(item as WatchUi.MenuItem) as Void {
        if (item instanceof ItemRow) {
            _screens.onItemSelected(item as ItemRow);
        }
    }

    function onBack() as Void {
        _screens.showAisles(-1);
    }
}

class DoneDelegate extends WatchUi.Menu2InputDelegate {

    private var _screens as Screens;

    function initialize(screens as Screens) {
        Menu2InputDelegate.initialize();
        _screens = screens;
    }

    function onSelect(item as WatchUi.MenuItem) as Void {
        var id = item.getId();
        if (id != null) {
            _screens.onDoneSelected(id);
        }
    }

    function onBack() as Void {
        _screens.showAisles(-1);
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
        _screens.showAisles(-1);
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
