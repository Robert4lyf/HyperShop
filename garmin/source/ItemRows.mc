using Toybox.Graphics;
using Toybox.Lang;
using Toybox.WatchUi;

//
// An aisle's rows, drawn rather than left to CheckboxMenu: on the Forerunner
// 245 the system menu draws the box only on the focused row, so a ticked item
// one row away looked exactly like one still to get. Here every row shows its
// state: an empty ring to get, a green tick with the name greyed and struck
// through once ticked.
//
class ItemRow extends WatchUi.CustomMenuItem {

    private const GREEN = 0x3DBE5C;
    private const DIM = 0x8A8A8A;

    private var _label as Lang.String;
    private var _sub as Lang.String or Null;
    private var _done as Lang.Boolean;

    function initialize(id as Lang.Number, label as Lang.String, sub as Lang.String or Null, done as Lang.Boolean) {
        CustomMenuItem.initialize(id, {});
        _label = label;
        _sub = sub;
        _done = done;
    }

    function isDone() as Lang.Boolean {
        return _done;
    }

    function setState(done as Lang.Boolean, sub as Lang.String or Null) as Void {
        _done = done;
        _sub = sub;
    }

    function draw(dc as Graphics.Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var focused = isFocused();
        var cy = h / 2;
        var r = focused ? 12 : 9;
        var cx = 38;
        if (_done) {
            dc.setColor(GREEN, GREEN);
            dc.fillCircle(cx, cy, r);
            dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
            dc.setPenWidth(3);
            dc.drawLine(cx - r / 2, cy, cx - r / 6, cy + r / 2);
            dc.drawLine(cx - r / 6, cy + r / 2, cx + r / 2, cy - r / 3);
        } else {
            dc.setColor(focused ? Graphics.COLOR_WHITE : DIM, Graphics.COLOR_TRANSPARENT);
            dc.setPenWidth(2);
            dc.drawCircle(cx, cy, r);
        }
        dc.setPenWidth(1);

        var x = cx + r + 10;
        var room = w - x - 24;
        var font = focused ? Graphics.FONT_MEDIUM : Graphics.FONT_SMALL;
        var text = fit(dc, _label, font, room);
        var sub = focused ? _sub : null;
        var textH = dc.getFontHeight(font);
        var subH = sub != null ? dc.getFontHeight(Graphics.FONT_XTINY) : 0;
        var top = cy - (textH + subH) / 2;
        dc.setColor(_done ? DIM : Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(x, top, font, text, Graphics.TEXT_JUSTIFY_LEFT);
        if (_done) {
            var y = top + textH / 2;
            dc.setPenWidth(2);
            dc.drawLine(x, y, x + dc.getTextWidthInPixels(text, font), y);
            dc.setPenWidth(1);
        }
        if (sub != null) {
            dc.setColor(DIM, Graphics.COLOR_TRANSPARENT);
            dc.drawText(x, top + textH, Graphics.FONT_XTINY, fit(dc, sub as Lang.String, Graphics.FONT_XTINY, room), Graphics.TEXT_JUSTIFY_LEFT);
        }
    }

    //! [s] cut to [room] pixels, with ".." when it had to be.
    private function fit(dc as Graphics.Dc, s as Lang.String, font as Graphics.FontType, room as Lang.Number) as Lang.String {
        if (dc.getTextWidthInPixels(s, font) <= room) {
            return s;
        }
        var n = s.length();
        while (n > 1 && dc.getTextWidthInPixels(s.substring(0, n) + "..", font) > room) {
            n -= 1;
        }
        return s.substring(0, n) + "..";
    }
}

//! The aisle's name above its rows.
class AisleTitle extends WatchUi.Drawable {

    private var _name as Lang.String;

    function initialize(name as Lang.String) {
        Drawable.initialize({});
        _name = name;
    }

    function draw(dc as Graphics.Dc) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        dc.drawText(
            dc.getWidth() / 2,
            dc.getHeight() / 2,
            Graphics.FONT_SMALL,
            _name,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER
        );
    }
}
