# ADR 003 — Backups are a zip that carries the recipe photos

**Status:** accepted (Step S.3)

## Context
A backup was one JSON document. Since recipe photos (R.2) it named each recipe's
photo file and the page it came from, but not the image. A page's photo can be
fetched again; one chosen from the phone's gallery cannot, so a restore onto
another phone lost it while saying the restore had worked.

## Decision
"Back up to a file" writes `hypershop-backup-<date>.zip`, read by
`BackupArchive`:

- `manifest.json` first: format `hypershop-archive`, version 1, and each photo's
  file name, SHA-256 and byte count;
- `backup.json`: the `BackupFile` exactly as a plain backup has always been
  written, so it takes the same checks (`BackupFileValidator`) and version rules;
- `photos/<name>`, once per file however many recipes show it (photos are named
  by their content, so a shared picture is one entry).

A zip, not base64 in the JSON: the photos never pass through the JSON parser's
memory, each is streamed to a file, and nothing new is added to the dependency
list (`java.util.zip`).

Reading treats the file as hostile. Entry names are never paths: only the three
shapes above are accepted, each once, the manifest first. Inflated bytes are
capped per entry (8 MB a photo, the plain backup's 64 MB for the JSON, 4 MB for
the manifest) and in total (2 GB), whatever the headers claim; at most 10,000
photos. A photo must match its manifest's size and SHA-256, its name must be a
prefix of that hash, and it must be a JPEG no larger than the app itself writes.
Anything else refuses the whole file before anything changes.

Restoring: the photos are staged in the cache at inspection, moved into the
photo folder when the restore is confirmed and *before* the restore journal is
written (a file nothing names is harmless and is swept at the next start), and
a recipe whose photo will not be a file loses the name, keeping a page's address
so it is fetched again. The database never names a missing file. The old photos
stay until the next start's sweep, so Undo (a restore of what was there) finds
them. Backup, restore and recovery hold `RecipePhotos`' lock, so no photo is
changed or swept half-way.

**Step T (staging, integrity, retention).** Each inspection stages its photos in
a folder of its own (`StagedPhotos.dir`, a UUID under `restore-photos`);
discarding or restoring one removes only that folder, a new inspection discards
the pending one it replaces, and start-up clears what a dead process left. A
staged photo is checked again (`BackupArchive.isIntact`) as it is adopted, and a
photo already on the phone counts as there only if it is whole, never merely
present. The restore runs off the main thread, since that check hashes every
photo. A removed recipe's note (stars, notes, photo) is kept while anything
names its key — a stored copy of the recipe or a planned meal — then marked
(`RecipeNote.orphanedAt`, travelling in backups) and deleted 30 days later at
start-up, before the sweep, which then takes its photo if nothing else shows it.

A plain JSON backup still restores: its photos count as there only when the
same file is already on the phone; the rest are said to be missing in the
restore prompt.

## Consequences
- An older HyperShop cannot read a new backup (it says it is not a backup). The
  app is one person's, updated in step, so this is accepted.
- A backup grows with the photos: about 150–400 KB each at 1280 pixels.
- No database migration: the format change is the file's, not Room's.
- A rating, notes or photo on a recipe removed for good is gone 30 days after
  nothing names it (Step T); a backup taken in that month still carries it.
