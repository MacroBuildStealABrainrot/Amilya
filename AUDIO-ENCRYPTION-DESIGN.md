# Sandbox audio-encryption design (1.8.5-test8)

**Scope: sandbox build only.** The production recorder, the debug and release apps, their upload path and the release application ID are unchanged (`CRASH_SAFE_RECORDING` is off there). Nothing here has run on an Android phone; the file-format, recovery, in-memory rebuild and key-handling logic is tested on a PC (`PROTOTYPE-RESULTS.md`).

## What it protects, in plain words
In the sandbox build the sound is **never stored as a plain audio file**. During a visit it is written as small encrypted pieces. When you press Stop, the pieces stay as they are. The file the rest of the app knows as `recording-....m4a` is now a tiny **placeholder** (under 4 KB, no sound) whose only job is to give the recording its name and place in the lists. When the recording is played or uploaded, the app rebuilds an audio file **in memory** only, uses it, and wipes it.

## How it works
1. **Start.** A fresh random 256-bit key is made for this recording. It is locked with an Android Keystore key (AES-256-GCM, alias `wcp.audio.kek.sandbox.v1`) and the locked copy is saved **before any sound is written**. **If this fails, recording does not start** and the clinician sees a clear message. The spare copy of the key used for locking is wiped straight away, and a failed attempt wipes the key too. Audio is never recorded unencrypted. (Your decision 1.)
2. **While recording.** Each ~43 ms sound frame is encrypted and signed on its own (AES-256-GCM) with its running number and piece number bound in, and written straight to disk. Only the in-memory key is used, so recording continues with the screen locked.
3. **Stop or recovery after a kill.** The key is unlocked once, the good frames are decrypted into memory, checked (missing, damaged, repeated or cut-short audio is found and reported in plain words), and a placeholder plus a "ready" note are written. The unlocked bytes are wiped. A quick playability check is made in memory and is non-fatal.
4. **Playing.** The pieces are decrypted into a memory buffer, a minimal MP4 is built around the frames (`Mp4Aac`), handed to the player as an in-memory source, and wiped when the player closes.
5. **Uploading.** The app uploads through the existing `audio-parts` path. Each part is built in memory (120 s, never over the server's 130 s / 4 MB limits), sent straight from memory, and wiped. Parts are built identically every time, so a retry after a failure is recognised by the server. The existing upload-progress record points at the placeholder, so the existing "keep until processing is confirmed" rules protect the recording.
6. **Disposal.** The pieces and key go away only when the report is saved, when you confirm "Discard this recording...", or by the existing erase flows. Age alone never deletes them (see retention below).

## Retention in the sandbox (your decision 3)
A recording is **unresolved** until its report is saved or Discard is confirmed. Unresolved recordings and their recovery/upload records are **exempt from the 7-day clean-up**. The app reminds the clinician after 2 days and then at most once a day (no patient details in the notification). It refuses to start a recording if free space is under 200 MB or unresolved audio is 3 GB or more (warns from 1 GB), and stops and saves mid-visit if free space drops under 40 MB. Nothing is ever deleted automatically to make room. **Report drafts and other unfinished-work records also never expire by age (your approved decision 1).** Records of other kinds (`source-`, `local-bind-`, `diagnostic-`, `wear-` and the like) still expire as before.

## Key handling
* In memory while recording; locked copy in `session.properties`; never in a file in the clear (tested for raw bytes, Base64 and hex).
* Wiped: the spare lock copy, the unlocked bytes at recovery, the key at the end of reading, decrypted frames after copying, the memory buffer when it grows and when closed, playback bytes on close.
* Not wipeable: copies the platform makes internally (the upload body inside `Api.execute`, crypto classes). Best effort only.
* Never logged, never put in a notification (a source scan fails the tests if a log or message line mentions key material).
* Deleting the session folder deletes the locked key.

## Costs and risks
* **Size:** about 15% more on disk than plain audio (16.6 MB against 14.4 MB for one hour).
* **Memory:** about 15 MB of sound for a one-hour visit plus working copies while playing or uploading. Not measured on a phone.
* **Biggest risk: if the Keystore key is lost or invalidated, the pieces cannot be unlocked.** Examples: Keystore faults, restoring to a different phone, clearing app data. Encryption can turn a recoverable recording into an unrecoverable one. That is why the key is not tied to the screen lock, and why this is sandbox only until phone tests (rows E13, E15) pass.
* **Not proven:** Android's player accepting the hand-written MP4; HTTPS in the sandbox (loopback only).

## Visits longer than one hour (approved decision 2; sandbox only; PC-tested, never run on a phone)
The server accepts at most 3,602 s of upload parts per job, so one recording stops at about one hour. Instead of stopping, the sandbox recorder now **continues into a second recording**:
* **Heads-up** at 54:00 (5 minutes ahead) on the recording screen, in calm words.
* **Hand-over at 59:00 of sound.** The microphone and encoder keep running. A new recording starts with its own new key (locked by the Keystore before any sound is written) and its own folder. If locking the new key fails, the hand-over does **not** happen: the current recording keeps going and tries again a little later, up to the existing safety stop at 59:50 (3,590,000 ms).
* **No missing sound.** The last frame of the finished part is also written as the first frame of the next part. So the join repeats one frame (about 43 ms, shown as a faded 1024-sample block) and nothing is lost. Checked with real AAC: part 1 plus part 2 decoded, minus those 1024 samples, equals the uncut recording exactly (speech).
* **Connected to the same visit.** Each part carries the visit id, its part number, and the names of the part before and after, in its settings file and a note. Up to 4 parts (about 3 h 56 min).
* Each part is a normal recording: its own placeholder `.m4a`, its own pieces, its own upload parts under the server limits. **Not yet built or verified:** whether the report step merges the parts automatically (see decisions).

## Verification status
| Part | Status |
|---|---|
| Record format, tamper/gap detection, key before sound, no readable sound on disk, wrong key reads nothing | PC-tested |
| Placeholder, in-memory rebuild, MP4 structure, parts, determinism | PC-tested with ffprobe/ffmpeg and the backend's own checks (tones and synthetic speech) |
| 300 real forced kills (75 of them across a hand-over); real full disk | PC-tested |
| Hand-over: no lost frame, one repeated frame, linked parts, retry on failure, part limit | PC-tested (13 logic tests, 9 real-AAC checks) |
| Key copies wiped; no key in files; no key in log lines | PC-tested |
| Keystore (including for part 2), the service wiring of the hand-over, MediaPlayer/metadata acceptance, notifications, screens, phone tests | Written, never compiled against Android or run |
