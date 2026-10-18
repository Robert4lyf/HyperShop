# Voice mode — design plan (not built yet)

Status: **plan only**, updated with your answers (§6). Goal: do a whole shop with headphones on and the phone in a
pocket, asking for the next items, saying "done" to tick, and asking simple
questions.

## 1. What it does

| You say (examples) | The app does | It says |
| --- | --- | --- |
| "next", "what's next", "next items" | Reads the next **3** unticked items in walking order. These become the "current three" | "Next: 2 onions, carrots, and a pack of basil." |
| "next one", "just the next" | Reads only the first | "Next: 2 onions." |
| "done", "got it", "tick" | Ticks the first of the current three that is still unticked, and names it | "Ticked onions. Still to get: carrots, basil." When all three are ticked, it reads the next three. |
| "got the carrots", "done carrots", "tick milk" | Ticks the named item (fuzzy match, preferring the current three) | "Ticked carrots." |
| "got the milk", "done milk", "tick milk" | Ticks the named item (fuzzy match among unticked) | "Ticked milk. Next: …" |
| "undo", "untick that" | Unticks the last item ticked by voice | "Unticked milk." |
| "skip", "later" | Moves on and leaves the item unticked. Skipped items come back at the end | "Skipping basil. Next: …" |
| "repeat", "say that again", "remind me" | Reads the current item again | "2 onions." |
| "how much", "what quantity", "how many" | Reads the full amount and the pack note | "700 millilitres of milk. Usually a 4 pint bottle." |
| "how many left", "how many items" | Counts | "12 left, 5 ticked." |
| "what's left in this aisle", "anything else here" | Reads the unticked items in the current aisle | "In Produce: carrots and basil." |
| "which aisle" | — | "Milk is in Dairy and eggs." |
| "swaps", "what can I use instead" | Reads the swaps from the ingredient library (read only: the list is not changed) | "Instead of agave syrup: maple syrup, honey or golden syrup." |
| "they're out", "out of stock", "none left" | Reads the swaps, then waits | "Agave syrup is out. You could get maple syrup, honey or golden syrup. Say 'done' if you got one, or 'skip'." Then "done" ticks the item; "skip" or "no" leaves it unticked and it isn't offered again this trip. No swaps → "No swaps for agave syrup; leaving it on the list." |
| "help", "what can I say" | — | A short list of commands |
| "stop", "pause voice" | Ends voice mode | "Voice mode off." |
| "finish shopping" | Asks to confirm, then runs the same Finish as the button | "Finish and clear 14 ticked items? Say yes or no." |

Not in v1:
- Changing the list by voice (no "swap for …"; swaps are read only).
- Adding items by voice.
- Partial ticks ("got 2 of 3").
- Reading recipes.
- Anything that calls Claude.

## 2. Key decisions (recommended)

1. **Everything runs on the phone, and Claude is never involved.** The command set is small and fixed, so a
   simple on-device parser is enough. Calling Claude would add cost, take seconds per command,
   and fail where signal is poor. It would also break the rule that Claude runs only while the review screen is open.
2. **Speech in: Android `SpeechRecognizer`.**
   - Prefer the **on-device** recognizer: `createOnDeviceSpeechRecognizer`, available from API 31; our minSdk is 34.
   - Use the `en-GB` language model. Supermarkets often have weak signal, so speech must not depend on the network.
   - Settings will check `checkRecognitionSupport` and offer `triggerModelDownload`.
   - Fall back to the normal (network) recognizer if the on-device one is unavailable, and say so in Settings.
3. **Speech out: Android `TextToSpeech`.**
   - Use `en-GB`, with audio attributes `USAGE_ASSISTANT`.
   - Request short-lived audio focus (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`), so other audio dips while the app talks and then returns.
4. **How listening starts. Decided: always listening** (§6). Finding an item can take minutes, which a short listening window can't cover.
   - Use **segmented sessions** (`RecognizerIntent.EXTRA_SEGMENTED_SESSION`, API 33+, with `onSegmentResults`). One long recognition session hands back each phrase as you say it, instead of a stop/restart (and a beep) after every silence. It is restarted only when Android ends the session (`onEndOfSegmentedSession`) or on an error, with a short back-off.
   - The **Shokz single click** (play/pause) is the backup: it toggles "listening paused". The same applies to the Bose tap. This needs testing in the device test, because in call-audio mode (below) the button may act as "end call" instead.
   - The text below was the original recommendation, kept for reference:
   - After the app speaks, it listens for about 6 seconds. That covers the normal "next → done → next" exchange.
   - If nothing is heard, it stops listening. A **headset button press** (or the notification's "Listen" button) starts listening again at any time.
   - An optional **"Always listening"** setting keeps restarting the recognizer. This is not the default: it uses more battery and the official documentation says the API isn't meant for continuous use.
5. **Runs with the screen off.**
   - Voice mode runs as a **foreground service** of type `microphone`.
   - It must start while the app is open (Android 14 grants "while-in-use" microphone access only then). So you tap the mic button on the List tab before pocketing the phone. It keeps working with the screen locked.
   - A persistent notification offers **Listen**, **Next** and **Stop**.
6. **Ticks use the existing path.** Voice ticks call `ListRepository.tick`/`untick`/`swapItem`. Revision bumps, watch sync and undo then work exactly as for a tap. The service never writes to the database directly.

### Should Claude Haiku interpret what you say? Decided: not in v1

Possible uses: send the speech-to-text transcript (never audio) to Haiku when the
local grammar doesn't understand it, or for free questions ("what was the coriander for?").

Against, for this feature:
- **Latency:** each call is a network round trip of a second or more, made while you stand in an aisle waiting. Local parsing is instant.
- **Signal:** supermarkets often have poor reception, so this help would fail exactly where it's needed.
- **Trust:** a wrong tick is worse than "say that again". A fixed grammar is predictable and testable; a model's guess isn't.
- **Recognition errors:** Haiku can't fix them. It sees the same text the recognizer produced.
- **Coverage:** the requested commands are a small, closed set.
- **Rules:** it would break "Claude is only called while the review screen is in the foreground" and add spend on every unclear phrase.

Revisit after real shops. The session will count how often it answers "Sorry, say that again"
(a local counter, no audio or text kept). If that is common, add an opt-in "Ask Claude" fallback:
- for unclear phrases only;
- text only, with the list's item names;
- it may answer or suggest, but never ticks without your "yes";
- it goes through the spend guard;
- it needs a CLAUDE.md rule change.

## 3. Architecture

`:core/voice` is pure JVM and fully unit-tested:
- `VoiceGrammar`: turns the recognizer's best guesses (with confidence scores) into a `Command`:
  - `Next(n)`, `Done(name?)`, `Undo`, `Skip`, `Repeat`, `Quantity`, `Left`, `AisleLeft`, `WhichAisle`,
  - `Swaps`, `SwapFor(name)`, `Help`, `Stop`, `Finish`, `Yes`, `No`, `Unknown`.
  - It tries each best guess in turn, and accepts near-miss words the recognizer often hears instead: "dun", "don", "dumb" for "done"; "text" for "next"; "thick" for "tick".
- `VoiceSession`: a state machine.
  - Input: the latest list snapshot and a `Command`.
  - Output: the text to speak plus the actions to run.
  - State: the current item id, the items just read out, the skipped ids, the last voice tick (for undo), and any pending "Finish?" confirmation.
- `SpeechText`: turns list text into words to speak.
  - "700ml" → "700 millilitres", "1½tbsp" → "one and a half tablespoons", "1 × 400 g tin" → "a 400 gram tin".
  - Also handles "2 cloves", "1 pack", and fractions.
- `ItemMatcher`: matches a spoken name against the unticked items. It uses the same catalogue keys, synonyms and plurals as the parser, then word overlap, then edit distance with a threshold. When two items match equally, it asks: "Did you mean red onions or spring onions?"

`:app/voice` holds the Android parts. Each sits behind an interface, so tests can use fakes:
- `VoiceService`: the foreground service (type `microphone`, not exported). It owns the session, the speech input and output, the media session and the notification.
- `SpeechInput`: wraps `SpeechRecognizer` (main thread only, one at a time). It:
  - asks for several best guesses and their confidence scores;
  - where available, biases the recognizer towards the current item names and the commands (`EXTRA_BIASING_STRINGS`, API 33+);
  - treats `NO_MATCH` and `SPEECH_TIMEOUT` as silence;
  - backs off when it gets `RECOGNIZER_BUSY`.
- `SpeechOutput`: wraps TTS, with a queue. Listening starts only after speech has finished (`onDone`), so the app never hears itself.
- `HeadsetButton`: a `MediaSession` whose play/pause means "listen".
- Settings:
  - voice on or off;
  - read 1 or 3 items for "next";
  - listening mode (conversation window or always);
  - "say the aisle when it changes".

Entry points:
- A mic button in the List header starts voice mode. It asks for microphone permission the first time.
- The notification has Stop, Listen and Next.
- A small "Voice on" chip shows on the List while voice mode is running.

## 4. Pitfalls and how the plan handles them

1. **Headset buttons go to the last app that played audio, not to the active media session.** Developers report this, along with "TTS doesn't count as playing".
   - Fix: when voice mode starts (and after each spoken reply), play a ~100 ms silent clip through `MediaPlayer` so we become the target.
   - If you're playing music, Spotify or similar will keep the buttons. The notification's Listen button and the conversation window still work.
   - This is the biggest uncertainty; it is checked in the device test, step 0.
2. **Bluetooth headset microphone. Decided: use the headset mic** (§6). With the phone in a pocket, its own mic will be too muffled. The Shokz have noise-reducing mics aimed at your mouth, which also cuts false triggers from other shoppers. Original analysis:
   - By default the recognizer uses the **phone's** microphone, even with Bluetooth earbuds. With the phone in a pocket that's muffled, but usually workable for single words.
   - Using the earbuds' own mic needs the call audio route (HFP/SCO). That lowers audio quality, pauses or degrades music, and on Android 12+ probably needs the `BLUETOOTH_CONNECT` permission. That breaks the CLAUDE.md "No Bluetooth permissions" rule (written for the Garmin link).
   - **Decided for v1:**
     - Route the mic to the headset with `AudioManager.setCommunicationDevice` while voice mode is on, using `MODE_IN_COMMUNICATION`, and release it on stop.
     - Request `BLUETOOTH_CONNECT` if the device test shows Android needs it. That needs a CLAUDE.md and `check_manifest.py` change: "No Bluetooth permissions" becomes "`BLUETOOTH_CONNECT` only, for the voice mode mic".
     - Call-audio quality is fine, since you listen to nothing else.
     - Fallback when no headset is connected: the phone mic.
   - Wired headsets with a mic just work.
3. **Background microphone limits (Android 14).**
   - The service must be started from a visible screen, with `RECORD_AUDIO` granted and `FOREGROUND_SERVICE_MICROPHONE` declared. If the system kills it, it can't restart itself in the background. On restart we post a notification: "Voice mode stopped, tap to resume".
   - Some phones (notably Samsung, Xiaomi) stop background work aggressively. Settings will explain how to exempt the app from battery optimisation; we won't request the exemption automatically.
4. **Listening beeps.** The recognizer plays start and stop tones, and there is no official way to mute them. We keep them: they tell you "speak now". We won't use stream-muting workarounds; those break other audio.
5. **Hearing ourselves or other people.** We never listen while speaking. Other shoppers saying "done" could tick an item:
   - every tick is spoken back ("Ticked milk"), so you notice;
   - "undo" is always available;
   - in Always-listening mode, a plain "done" counts only within about 10 s of the app reading an item; otherwise it needs the item name.
6. **Recognition errors in a noisy shop.**
   - Use all the best guesses, plus confidence thresholds.
   - Below the threshold, the app says "Sorry, say that again", and makes no change to the list.
   - Commands are tiny words; aliases and the near-miss words cover the usual errors.
7. **The on-device language model may be missing.**
   - Settings shows "Offline speech: ready / download (size)" before you go shopping.
   - If it's missing and you're offline, the app says "Voice needs the speech pack: download it in Settings", rather than failing silently.
8. **The list changes during voice mode** (a tap on the phone, the watch, an edit). The session reads the shared list flow:
   - if the current item disappears or gets ticked elsewhere, the next "next" moves on;
   - nothing is held from earlier reads except item ids, and ticks go through the repository (rev bumps, watch sync).
9. **Partly ticked items.** These are read as the list shows them ("1 more onion"). "done" ticks the remainder: same behaviour as tapping the tick.
10. **Odd amounts read aloud.** Text like "½", "×", "tbsp", "2.27 l" and "1 × 30 g pack" would sound wrong if spoken as written. `SpeechText` gets its own unit tests, built from the existing `Display` test cases.
11. **Battery and privacy.**
    - No audio is stored and, with the on-device recognizer, nothing leaves the phone.
    - Android shows its microphone indicator while listening.
    - Voice mode turns itself off after 30 minutes with no commands, and when "Finish shopping" runs.
12. **Audio focus.** We take short-lived focus while speaking and give it straight back. If a phone call starts, voice mode pauses and says "Voice paused" when the call ends.
13. **Permissions and the manifest check.**
    - New: `RECORD_AUDIO` (runtime), `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, and `POST_NOTIFICATIONS` (runtime, for the notification). `MODIFY_AUDIO_SETTINGS` only if needed.
    - Still none: `CAMERA` and Bluetooth.
    - `tools/check_manifest.py` gains rules:
      - the voice service is not exported;
      - `RECORD_AUDIO` is the only microphone permission;
      - still no `CAMERA` and no `BLUETOOTH*`.
14. **Ordering.** "Next" follows the List tab exactly: walking order, then the categorised groups if that view is on, unticked items first. Skipped items come last, so you aren't sent back across the shop.
15. **Overlap with the watch app (Run 2).** Both are in-store ticking interfaces on the same repository path, so there's no conflict, but it's the same need met twice. Worth deciding whether Run 2 is still wanted (§6 Q8).
16. **Testing reality.** Real speech can't be unit-tested.
    - Parser, session and speech text: JVM tests, including a scripted "whole shop" walk-through.
    - Service wiring: Robolectric with fake speech input and output.
    - A manual device checklist (§5, step 4) is required before calling it done.

## 5. Build order

0. **Device test on your phone, about an hour.** Built as Settings › Advanced › Voice test (`VoiceTestService`, `VoiceTestTools`); run it and share the log. It must prove four things before any real work:
   - (a) the on-device en-GB recognizer works and how fast;
   - (b) recognition works from a microphone foreground service with the screen locked;
   - (c) the headset button reaches us with your headphones, including the silent-clip trick;
   - (d) how well the phone mic hears you with the phone in a pocket.

   Added after your answers (Pixel 7 Pro, Shokz OpenRun Pro 2, Bose QC Earbuds II):
   - (e) the recognizer hears through the Shokz and Bose mics with the headset route set via `setCommunicationDevice`, and whether `BLUETOOTH_CONNECT` is needed;
   - (f) a segmented session runs for 10+ minutes with the screen locked: how often it ends, whether it beeps, battery use;
   - (g) what a Shokz click and a Bose tap send while call audio is on: a media button we receive, or "hang up";
   - (h) whether other shoppers' voices trigger commands, at a store or with a TV on.

   If (b) or (f) fails, voice mode needs the screen on, or listening windows instead. That would change the plan.
1. `:core/voice`: `SpeechText`, `ItemMatcher`, `VoiceGrammar`, `VoiceSession`, with tests (the bulk of the logic).
2. `:app/voice`: the service, speech input and output, the notification, the mic button and permissions, settings, and the manifest-check update.
3. Conversation window and headset button. Then the optional Always-listening mode.
4. Device checklist:
   - wired headset and Bluetooth earbuds;
   - screen locked;
   - music playing;
   - airplane mode;
   - a noisy room;
   - a phone call mid-shop;
   - the app killed in the background;
   - 45 minutes of battery drain.

## 6. Your answers (18 Oct 2026)

- **Phone and headphones:** Pixel 7 Pro, with Shokz OpenRun Pro 2 or Bose QC (QuietComfort Earbuds II). Bluetooth works well; I've read that as agreement to `BLUETOOTH_CONNECT` for the headset mic. Shokz: a single click on the multifunction button is play/pause (Shokz guide).
- **Nothing else is played while shopping:** the media session and the audio route are ours alone.
- **Always listening** (push-to-talk as a backup where the button allows).
- **"Next" reads 3** by default.
- **Swaps are read only.**
- **Out of stock:** offer the swaps; if none is taken, leave the item unticked.
- **Watch app (Run 2):** still wanted, as an alternative. Voice mode and the watch both tick through `ListRepository`, so they work side by side; nothing in this plan blocks Run 2.

### Original questions

1. **Which phone and headphones?** For example, Pixel or Samsung; AirPods, Pixel Buds, Galaxy Buds or wired. Button gestures, and whether the earbuds' mic can be used, depend on this.
2. **Do you listen to music or podcasts while shopping?** If so, the headset button usually stays with the music app. You'd rely on the conversation window or Always-listening instead.
3. **How should listening start?** Recommended: listen for a few seconds after each reply, and press the headset button otherwise. Alternatively, always listening: fully hands-free, with more battery and more false triggers.
4. **Earbuds' microphone.** If the phone mic in a pocket isn't good enough, do you accept the Bluetooth (`BLUETOOTH_CONNECT`) permission and "call quality" audio while voice mode is on?
5. **"Next" reads one item or three by default?** Recommended: one, and "next three" when you ask.
6. **Should "swap for …" change the list by voice, or only read the swaps?**
7. **"They're out of stock" should skip the item (left unticked) or delete it?** Recommended: skip.
8. **Is the watch app (Run 2) still wanted** once voice mode exists?

## 7. Sources

- Segmented recognition sessions (`onSegmentResults`, `onEndOfSegmentedSession`, API 33): <https://developer.android.com/reference/android/speech/RecognitionListener>
- Shokz OpenRun Pro 2 controls: <https://shokz.com/blogs/guides/openrun-pro-2-using-guides>

- Foreground service types and the Android 14 while-in-use rules: <https://developer.android.com/develop/background-work/services/fgs/service-types>, <https://developer.android.com/about/versions/12/foreground-services>
- `SpeechRecognizer` (on-device, recognition support, model download; "not intended for continuous recognition"): <https://developer.android.com/reference/android/speech/SpeechRecognizer>
- Headset buttons go to the app that last played audio: <https://issuetracker.google.com/issues/65175978>, <https://community.audiorelay.net/t/blocked-media-key/975>
- Bluetooth mic routing and `BLUETOOTH_CONNECT`: <https://forasoft.com/blog/article/implement-audio-output-switching-on-android-575>, <https://docs.eyeson.com/docs/android/usage/audio-manager>
- Recognizer restart loops, beeps and `RECOGNIZER_BUSY`: <https://community.appinventor.mit.edu/t/error-messages-in-non-legacy-speech-recognition/73163>
