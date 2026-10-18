# HyperShop watch app (Forerunner 245)

Buttons only. The phone owns the list; the watch shows a paged copy, aisle by
aisle, and sends each tick until the phone acknowledges it. Protocol:
`docs/garmin-protocol.md`. App id `41c79afd24ac47168df768ef92d1bac0`
(`manifest.xml`, `WatchProtocol.WATCH_APP_ID`).

## Building

| Command | What |
|---|---|
| `tools/build_watch.sh` | strict, device-independent compile (fetches SDK 9.2.0, generates a throwaway key); run by `verify.sh` |
| `tools/build_watch.sh --test` | the same for `source/` + `test/` |
| `tools/package_watch.sh` | the real build: `.iq` for the store and `hypershop-fr245.prg` to sideload. Needs your developer key and the device definitions |
| `python3 tools/device_facts.py` | memory limits and glance support from the installed definitions |

CI: `.github/workflows/connectiq.yml` runs the first two on every PR touching
`garmin/`. Its **Build for the Forerunner 245** job (only with the three Garmin
secrets set) downloads the device definitions, prints the device facts and the
build size to the job summary, compiles the tests for the device, and uploads
`hypershop-fr245.prg` as an artifact. `release.yml` attaches the `.iq` and
`.prg` to the rolling release.

## Forerunner 245 facts
- Device ids `fr245`, `fr245m`; `minApiLevel="3.2.0"` works (BangGym).
- 5 buttons, no touchscreen; 240×240 round MIP.
- A watch app gets 128 KB. Release ≈ 15 KB in BangGym; debug ≈ 7× bigger, so
  sideload release builds.
- API: `Menu2`, `CheckboxMenu`, `Menu2.setFocus` are 3.0.0; `getGlanceView` is
  3.1.0; `WatchUi.showToast` is 3.4.0, **not** available at 3.2, so notices are
  drawn in the app's own views.
- Glances: read from the device job summary (`tools/device_facts.py`). Until
  that says the 245 lists a `glance` app type, the app has no glance; it keeps
  the summary ("14 left · Produce next") in Storage key `glance` ready for one.
- Messages may be capped in the low hundreds of bytes (unverified; the
  payload probe in the debug build measures it at Hand-off B).
