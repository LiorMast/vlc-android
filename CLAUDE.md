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
- **Timeline and sync:**
  - `DualViewTimeline` does the split/offset maths.
  - `SyncController` holds the correction logic, and `DualViewSync` samples both players every 10 ms on its own thread.
  - Both of those work from VLC 3's coarse time (refreshed every 250 ms, and ahead of the picture right after a start or seek).
- **Paused view:** while paused, seeks aim at mid-frame (`SyncController.midFrame`), because VLC shows the frame about 9 ms before a seek target.
- **Audio sync** (`audio/`):
  - Envelopes: a 500 Hz high-passed loudness envelope at 10 ms steps.
  - Matching: GCC-PHAT across windows, accepted at a peak ≥ 6σ and ≥ 2× the runner-up.
  - Readers: `AndroidAudioReader` (MediaCodec, parallel chunks) for local and HTTP sources; `VlcAudioReader` (a transcode to WAV from the file start, cached in `cacheDir/dual_view`) for network shares.
  - Accuracy: results are typically one frame off the pictures, because each camera's mic hears sounds at slightly different times. The panel's offset buttons step one frame.
- **Persistence:** `DualViewStore`, its own SharedPreferences file `dual_view`.
- **Integration points:**
  - `VideoPlayerActivity`: `dualViewDelegate`, event forwarding, the seek hook `mainSeekTarget`, and `setPlayerVolume`/`playerVolume` for volume routing.
  - `PlayerOptionsDelegate`: the `ID_DUAL_VIEW` entry.
  - `player.xml`: the `dual_view_stub` view stub.
  - `player_hud.xml`: the split marker.

## Device testing over adb.exe

- **The device:** a Galaxy Tab in landscape, 2000×1200, unlocked with a swipe (no PIN).
- **Guard every input:** before each tap or key press, check that `adb.exe shell dumpsys window | grep mCurrentFocus` shows `org.videolan.vlc.debug`. Unguarded taps once landed in another app.
- **Don't press Back to close sheets.** If the sheet isn't actually open, Back exits the player.
- **Controls auto-hide** within the ~2 s a `uiautomator dump` takes, so tap known coordinates right after confirming they're visible.
- **Logs:** `adb.exe logcat -s VLC/DualView VLC/DualViewAudioSync`. Test videos are in `/sdcard/Movies/DualViewTest/`.
