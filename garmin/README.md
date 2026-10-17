# HyperShop watch app (Forerunner 245)

Run 2 builds this. Run 1 ships only the BangGym skeleton: `manifest.xml`
(app id `41c79afd24ac47168df768ef92d1bac0`), `monkey.jungle`, `test.jungle`.

## Forerunner 245 facts (from BangGym)
- Device ids `fr245`, `fr245m`; `minApiLevel="3.2.0"` works.
- 5 buttons, no touchscreen; 240×240 round MIP.
- A watch app gets 128 KB. Release ≈ 15 KB; debug ≈ 7× bigger — sideload release.
- Messages may be capped in the low hundreds of bytes (unverified; measured by
  the payload probe at Hand-off B).
- Glance support: to check in Step 6.0.
