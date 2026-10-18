using Toybox.Communications;
using Toybox.Lang;
using Toybox.Math;
using Toybox.System;
using Toybox.Time;
using Toybox.Timer;

//
// The Bluetooth link to the phone: registration, transmit, retry.
//
// The rules live in WatchSync; this only moves its payloads. One message is
// in flight at a time — a hello or an op batch — and the phone always answers
// either. No answer within REPLY_SECONDS, or a transmit that fails outright,
// backs off 5 s → 15 s → 60 s before the next try, for as long as the app is
// open. Any answer resets the back-off.
//
class PhoneLink {

    private const REPLY_SECONDS = 10;
    private const BACKOFF = [5, 15, 60] as Lang.Array<Lang.Number>;

    var sync as WatchSync;

    //! The phone sent a protocol version this watch does not speak.
    var versionMismatch as Lang.Boolean = false;
    //! When a reply last arrived (Time seconds), 0 for never this session.
    var lastHeard as Lang.Number = 0;

    private var _onChange as Lang.Method or Null = null;
    private var _timer as Timer.Timer or Null = null;
    private var _sentAt as Lang.Number = 0;
    private var _awaiting as Lang.Boolean = false;
    private var _backoff as Lang.Number = 0;
    private var _notBefore as Lang.Number = 0;
    private var _offline as Lang.Boolean = false;

    function initialize(watchSync as WatchSync) {
        sync = watchSync;
        if (sync.installId == 0) {
            // Random rather than counted: two watches on one phone, or one
            // reinstalled, must not share an outbox history on the phone.
            Math.srand(System.getTimer() + Time.now().value());
            var id = Math.rand() & 0x7fffffff;
            sync.reset(id > 0 ? id : 1);
        }
    }

    //! [callback] runs after anything the screen may want to redraw.
    function setOnChange(callback as Lang.Method or Null) as Void {
        _onChange = callback;
    }

    function start() as Void {
        Communications.registerForPhoneAppMessages(method(:onPhoneMessage));
        _timer = new Timer.Timer();
        (_timer as Timer.Timer).start(method(:onTimer), 1000, true);
        sendHello();
    }

    //! Flushes what it can and says goodbye. Best effort: onStop has very
    //! little time and transmit is asynchronous. The outbox is already in
    //! Storage, so nothing is lost when this does not get through.
    function stop() as Void {
        if (_timer != null) {
            (_timer as Timer.Timer).stop();
            _timer = null;
        }
        if (!_offline) {
            flush();
            transmit(sync.bye());
        }
        Communications.registerForPhoneAppMessages(null);
    }

    //! The debug sample: no hellos or batches while it is on screen.
    function setOffline(offline as Lang.Boolean) as Void {
        _offline = offline;
        if (!offline) {
            _awaiting = false;
            _notBefore = 0;
            sendHello();
        }
    }

    function isOffline() as Lang.Boolean {
        return _offline;
    }

    //! Asks the phone for the list now, whatever the back-off says.
    function refresh() as Void {
        _awaiting = false;
        _backoff = 0;
        _notBefore = 0;
        sendHello();
    }

    //! A tick or untick from the screen. False when it was refused.
    function toggle(itemId as Lang.Number) as Lang.Boolean {
        if (!sync.toggle(itemId, Time.now().value())) {
            return false;
        }
        flush();
        return true;
    }

    function onPhoneMessage(message as Communications.PhoneAppMessage) as Void {
        var payload = message.data;
        if (!(payload instanceof Lang.Dictionary) || _offline) {
            return;
        }
        var data = payload as Lang.Dictionary;
        var version = data.get(Protocol.KEY_VERSION);
        if (!(version instanceof Lang.Number) || (version as Lang.Number) != Protocol.VERSION) {
            // Said on screen rather than dropped silently: a blank list after
            // an update looks exactly like a broken link.
            versionMismatch = true;
            changed();
            return;
        }
        versionMismatch = false;
        if (!sync.receive(data)) {
            return;
        }
        lastHeard = Time.now().value();
        _awaiting = false;
        _backoff = 0;
        _notBefore = 0;
        if (sync.helloDue) {
            sendHello();
        }
        flush();
        changed();
    }

    function onTimer() as Void {
        if (_offline) {
            return;
        }
        var now = Time.now().value();
        if (_awaiting && now - _sentAt >= REPLY_SECONDS) {
            noReply(now);
        }
        if (!_awaiting && now >= _notBefore) {
            if (sync.helloDue || !sync.hasSnapshot) {
                sendHello();
            } else {
                flush();
            }
        }
    }

    //! The phone did not answer: give up on this message and wait a while.
    private function noReply(now as Lang.Number) as Void {
        _awaiting = false;
        sync.timeout();
        // The next try may be a batch the phone already applied (its reply
        // was lost); the phone re-acks a duplicate rather than applying it.
        if (!sync.hasSnapshot) {
            sync.helloDue = true;
        }
        _notBefore = now + BACKOFF[_backoff];
        if (_backoff < BACKOFF.size() - 1) {
            _backoff += 1;
        }
        changed();
    }

    function onTransmitFailed() as Void {
        if (_awaiting) {
            noReply(Time.now().value());
        }
    }

    private function sendHello() as Void {
        if (_offline) {
            return;
        }
        transmit(sync.hello(Time.now().value()));
        _awaiting = true;
        _sentAt = Time.now().value();
    }

    //! Sends the next batch unless something is in flight or backing off.
    private function flush() as Void {
        if (_offline || _awaiting || Time.now().value() < _notBefore) {
            return;
        }
        var batch = sync.nextBatch();
        if (batch == null) {
            return;
        }
        transmit(batch);
        _awaiting = true;
        _sentAt = Time.now().value();
    }

    private function transmit(payload as Lang.Dictionary) as Void {
        try {
            Communications.transmit(
                payload as Lang.Dictionary<Communications.TransmitKeyType, Communications.TransmitType>,
                null,
                new LinkListener(self)
            );
        } catch (e) {
            onTransmitFailed();
        }
    }

    private function changed() as Void {
        if (_onChange != null) {
            (_onChange as Lang.Method).invoke();
        }
    }

    //! "Last synced" in whole minutes, or -1 when nothing arrived this session.
    function minutesSinceSync() as Lang.Number {
        if (lastHeard == 0) {
            return -1;
        }
        return (Time.now().value() - lastHeard) / 60;
    }
}

class LinkListener extends Communications.ConnectionListener {

    private var _link as PhoneLink;

    function initialize(link as PhoneLink) {
        ConnectionListener.initialize();
        _link = link;
    }

    function onComplete() as Void {
    }

    function onError() as Void {
        _link.onTransmitFailed();
    }
}
