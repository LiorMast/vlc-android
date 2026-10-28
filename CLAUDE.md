# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

This is a personal fork of VLC for Android (`origin` = LiorMast/vlc-android, `upstream` = videolan/vlc-android). The checkout lives on a Windows drive (`/mnt/d`) and is worked on from WSL2. The test device is reached with Windows' `adb.exe`.

## Build

```bash
./build-apk.sh                                      # debug APK (org.videolan.vlc.debug), copied to out/
./build-apk.sh --install --run                      # also install on the device with adb.exe and launch it
./build-apk.sh --release                            # signedRelease, org.videolan.vlc, one APK per ABI, personal key
./build-apk.sh --remote lior@192.168.1.110 --install  # build on the LAN build host (also: VLC_BUILD_HOST)
./build-apk.sh --backup | --restore FILE            # debug-build data snapshots / VLC settings export
./build-apk.sh -- <gradle args>                     # extra Gradle arguments
```

- **App-only builds.** The app is built against VideoLAN's prebuilt LibVLC and medialibrary AARs from Maven. The versions are `libvlcVersion` and `medialibraryVersion` in the root `build.gradle`, with VLC 3 the default and `-PforceVlc4=true` for VLC 4.
  - Only the `dev` build type and `buildsystem/compile.sh` build native code, and that is out of scope.
- **First build.** `build-apk.sh` runs `buildsystem/compile.sh --init` once. That clones `libvlcjni/`, generates `gradlew` and writes `local.properties`; all three are gitignored, and `settings.gradle` always includes `:libvlcjni:libvlc`.
- **Prefer remote builds.** Local WSL builds can run out of memory, so prefer `--remote`.
  - The build host must be x86_64. Android's aapt2 and NDK have no ARM Linux builds.
  - Its only JDK is 25, which works.
- **Debug signing key.** Debug builds are signed with `~/.android/debug.keystore`, backed up to `../vlc-backups/signing/`. Installed debug builds only accept updates signed with that same key.

## Tests

- **Module tests don't compile.** `:application:vlc-android:testDebugUnitTest` fails in this checkout: `sourceSets.test` expects a `test-common/` folder that isn't in the repository, which upstream tests need (`TestUtil`, `argumentCaptor`).
- **Use a standalone harness instead.** Keep logic that needs tests free of Android imports. Run it with a small standalone Gradle project, `kotlin("jvm") 2.2.10` + JUnit 4.13.2, that adds the checkout's source files with `kotlin.srcDir(...)` and `include(...)`. The build host has one at `~/dualview-unit-tests`:
  ```bash
  ssh lior@192.168.1.110 'cd ~/vlc-android && ./gradlew -p ~/dualview-unit-tests test --rerun -Pcheckout=$HOME/vlc-android'
  # one class: add  --tests 'org.videolan.vlc.gui.video.dualview.SyncControllerTest'
  ```
  The `--remote` build rsyncs the checkout there; otherwise rsync the changed sources first.

## Architecture

**Modules.** The app is `:application:app`, a thin shell around `:application:vlc-android`, which holds nearly all UI and playback code.
- `:application:resources`: strings, drawables, `VLCOptions` and `VLCInstance`, so strings go in `application/resources/src/main/res/values/strings.xml`.
- `:application:tools`: `Settings` and helpers.
- `:application:mediadb`: Room.
- `:application:television`: the TV UI.
- `:application:remote-access-*`: the browser remote.

**VLC 3 vs 4.** `application/vlc-android/vlc3/src` and `vlc4/src` hold `VersionDependant.kt` shims. Any API that differs between versions needs both.

**Playback.**
- `PlaybackService` owns playback. `PlaylistManager` loads media: it builds `IMedia`, then calls `VLCOptions.setMediaOptions`, which applies the hardware-decoding preferences. `PlayerController` wraps the single LibVLC `MediaPlayer`.
- `VideoPlayerActivity` is the video UI. It is split into delegates (`VideoPlayerOverlayDelegate` for the HUD, `VideoTouchDelegate` for gestures, `VideoPlayerResizeDelegate` and so on), which are lazily created properties.
- Player events reach the activity through `PlaybackService.Callback.onMediaPlayerEvent`.
- Gestures come from `Activity.onTouchEvent`, so any child view that consumes a touch blocks them.
- The ⋯ options sheet is `gui/helpers/PlayerOptionsDelegate`. Its entries open `PlaybackBottomSheetDialogFragment` subclasses.

**Dual view** (`gui/video/dualview/`, this fork's feature) plays the two halves of a video holding two POVs at once.
- **Players:** the main player plays the whole file as usual. A second LibVLC `MediaPlayer` (`DualViewPlayer`, TextureView) shows the matching moment in a floating in-app window, `DualViewWindow`.
- **Timeline:** `DualViewTimeline` holds POV 1 = `[start, split)`, POV 2 = `[split, end)` and a list of `Segment`s, each giving the offset from one POV 1 moment on (cuts in the recordings change it). Mapping POV 2 back can find no POV 1 moment (`GAP`) or several (the latest segment wins).
- **Sync:**
  - `SyncController` holds the correction logic, and `DualViewSync` samples both players every 10 ms on its own thread.
  - Both of those work from VLC 3's coarse time (refreshed every 250 ms, and ahead of the picture right after a start or seek).
  - Crossing a cut, or an edit that moves the matching moment far, seeks the window at once.
- **Paused view:** while paused, seeks aim at mid-frame (`SyncController.midFrame`), because VLC shows the frame about 9 ms before a seek target.
- **Audio sync** (`audio/`):
  - Envelopes: a 500 Hz high-passed loudness envelope at 10 ms steps, kept per video in `AudioEnvelopeCache` (shared by sync, scan and the waveform strips). Starting dual view reads the whole file into it in the background (`AudioSync.prefetch`, 60 s blocks from the current moment outwards); the cache lives until another media opens or the activity is destroyed (`DualViewDelegate.release`), not just until dual view is turned off.
  - Matching: GCC-PHAT, accepted at a peak ≥ 6σ and ≥ 2× the runner-up. "Sync here" matches 15 s around the moment (±30 s, then all of POV 2); `CutFinder` places a cut where the old offset gives way to the new one, and its scan does that along the whole video.
  - Readers: `AndroidAudioReader` (MediaCodec, 4 parallel chunks) for local, `content://` and HTTP sources; `VlcAudioReader` (a transcode to WAV from the file start, cached in `cacheDir/dual_view`) for network shares.
  - Accuracy: results are typically one frame off the pictures, because each camera's mic hears sounds at slightly different times. The offset buttons step one frame.
- **Controls:** a HUD button (`quickStart`: resume the saved timeline, else split in the middle, match the audio and find the cuts; once on, it shows/hides the bar), `DualViewBar` (its ⋯ menu holds the other ways to split and resume) above the seek bar (keeps the HUD up while open), `DualViewWaveformView` (drag a strip to change the current segment's offset live) and `DualViewPinsView` (drag start/split/end pins and cut ticks).
- **Other apps' files** (`DualViewSource`):
  - Nova Video Player hands network files over as `http://localhost:<port>/…` through a proxy that drops an older connection when a new one arrives. Its patched `StreamOverHttp` only does so within a `?lane=` tag, which every extra dual view stream adds. The port changes per launch, so keys drop it.
  - A shared `content://` file plays as `fd://N`: `DualViewPlayer` opens its own descriptor, since two readers of one fd share its position.
- **Persistence:** `DualViewStore`, its own SharedPreferences file `dual_view`; `SavedTimelineCodec` writes `v2;…` and still reads the single-offset format.
- **Integration points:**
  - `VideoPlayerActivity`: `dualViewDelegate`, event forwarding, the seek hook `mainSeekTarget`, and `setPlayerVolume`/`playerVolume` for volume routing.
  - `VideoPlayerOverlayDelegate`: `initDualView` (button, pins, bar) and the infinite HUD timeout while the bar is open.
  - `player.xml`: the `dual_view_stub` view stub.
  - `player_hud.xml`: `dual_view_toggle`, `dual_view_pins` and `dual_view_bar_stub`.

## Device testing over adb.exe

- **The device:** a Galaxy Tab in landscape, 2000×1200, unlocked with a swipe (no PIN).
- **Guard every input:** before each tap or key press, check that `adb.exe shell dumpsys window | grep mCurrentFocus` shows `org.videolan.vlc.debug`. Unguarded taps once landed in another app.
- **Don't press Back to close sheets.** If the sheet isn't actually open, Back exits the player.
- **Controls auto-hide** within the ~2 s a `uiautomator dump` takes, so tap known coordinates right after confirming they're visible.
- **Logs:** `adb.exe logcat -s VLC/DualView VLC/DualViewAudioSync`. Test videos are in `/sdcard/Movies/DualViewTest/`.
- **Popups** (the dual view menu, cut removal) take focus as `PopupWindow:…`: guard those taps on `mFocusedApp` instead. The HUD hides after a few seconds and closes the menu, so show the HUD, open the menu and pick an item in one quick sequence.
