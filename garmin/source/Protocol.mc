//
// Wire protocol shared with the Android app.
// Mirror of android/core/.../sync/WatchProtocol.kt — change both or neither;
// tools/check_protocol_parity.py fails the build when they drift.
// docs/garmin-protocol.md has the rules.
//
module Protocol {

    // A payload whose version differs is dropped. A new key or message type
    // does not bump it; a change in the shape or meaning of one does.
    const VERSION = 1;

    // The manifest's application id, mirrored for the parity check.
    const WATCH_APP_ID = "41c79afd24ac47168df768ef92d1bac0";

    // Keys are 1–3 characters: messages to older watches may be capped in
    // the low hundreds of bytes and are dropped silently when larger.
    const KEY_VERSION = "v";
    const KEY_TYPE = "t";
    const KEY_EPOCH = "ep";
    const KEY_INSTALL_ID = "ii";
    const KEY_LIST_REV = "lr";
    const KEY_WATCH_NOW = "wn";
    const KEY_ACKED_THROUGH = "at";
    const KEY_ACK_EXCEPTIONS = "ax";
    const KEY_PAGE = "pg";
    const KEY_PAGES = "pgs";
    const KEY_ITEMS = "it";
    const KEY_AISLES = "ai";
    const KEY_MORE = "mo";
    const KEY_OPS = "op";

    // Watch → phone.
    const OUT_HELLO = "hello";
    const OUT_OPS = "ops";
    const OUT_BYE = "bye";

    // Phone → watch.
    const IN_SNAPSHOT = "snap";

    // Op results, in `ax` for every acked seq whose result is not ok.
    const RESULT_OK = "ok";
    const RESULT_NOOP = "noop";
    const RESULT_PARTIAL = "partial";
    const RESULT_STILL_DONE = "still_done";
    const RESULT_REJECTED = "rejected";
    const RESULT_EPOCH = "epoch";
}
