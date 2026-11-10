# AirPlay receiver minSdk 22 port: Fire TV Stick 2nd gen test report

Date: 2026-11-10. Device: AFTT (Fire OS 5, Android 5.1.1, SDK 22, armeabi-v7a, MT8127, Mali-450, ~875 MB RAM) at 192.168.29.143:5555.
Build: debug APK from `minsdk-22`, sender: a MacBook (macOS mirroring and whole-desktop audio AirPlay).

## Result

Works on the stick after three fixes. Mirroring at 1080p and 720p, audio-only AirPlay, URL video (YouTube HLS),
Now Playing controls, and repeated connect/disconnect cycles all work without crashes.

## Commits (on `minsdk-22`)

| Commit | Problem on the stick | Fix |
|---|---|---|
| `54fd8ba` | Crash at server start: `UnsatisfiedLinkError: cannot locate symbol "EVP_CIPHER_CTX_reset" referenced by "libairplay_native.so"`. Below API 24 the linker matches DT_NEEDED by soname, so the bundled `libcrypto.so` (OpenSSL 3.4) resolved to the system's old `/system/lib/libcrypto.so` already loaded in the zygote. | Link OpenSSL statically (`OPENSSL_USE_STATIC_LIBS`) and hide its symbols (`--exclude-libs,libcrypto.a`). `libcrypto.so` is no longer packaged. Probably affects upstream on API 23 too. |
| `8fd9765` | SIGSEGV when a sender disconnects: `art/runtime/thread.cc:1057] Native thread exited without calling DetachCurrentThread` (abort in `Thread::ThreadExitCallback` from `pthread_exit`). RAOP threads attach in `_get_env()` and never detach; Lollipop ART aborts. | A `pthread_key` destructor detaches each thread that `_get_env()` attached. Verified: ART now logs only the warning, and the process survives disconnects. |
| `9848cd6` | Now Playing buttons dead (pause/play/next did nothing, sender volume slider disabled). The sender's `DACP-ID` header drops leading zeros (`EBB2D06607195AE`) but it advertises `iTunes_Ctrl_0EBB2D06607195AE`, so `NsdManager.resolveService` looked for a name that doesn't exist and never returned. On Lollipop a pending resolve then makes every later one fail with `FAILURE_ALREADY_ACTIVE` (3). | Zero-pad the ID to 16 hex digits. Not specific to SDK 22, but on old NSD a wrong name disables DACP for the rest of the process. 2 of the first 3 IDs seen were 15 digits. Verification: the padding rule is confirmed against the `dns-sd` listing (`EBB2D06607195AE` advertised as `iTunes_Ctrl_0EBB2D06607195AE`). On the device, the 4 sessions after the fix all had 16-digit IDs; all resolved, two in the same process (so a later resolve is no longer blocked), and pausing from the TV paused the Mac. A short ID resolving on the device was not observed. |

`./gradlew :app:lintDebug`: 0 errors after each commit.

## Checks

| Check | Result |
|---|---|
| Install (`adb install -r`, 33.5 MB) | OK, not previously installed |
| Native libs on armeabi-v7a | OK after `54fd8ba` |
| Server start / `realDisplaySize()` fallback | OK, `Server started on port 7000` |
| mDNS (NsdManager) | `_airplay._tcp` and `_raop._tcp` registered within ~0.7 s each start; Mac sees "Android AirPlay" every time |
| Decoder pick (`DecoderSelector`, `_rateSupported` fallback) | `avc=OMX.MTK.VIDEO.DECODER.AVC hevc=OMX.MTK.VIDEO.DECODER.HEVC`, no NoSuchMethodError; Mac picked H.264 |
| UI and D-pad focus | Renders; focus moves through nav bar, Stop, volume slider and transport buttons |
| Now Playing (`ui/PlayerControls.kt`) | Renders; D-pad reaches the transport buttons; play/pause works after `9848cd6` (icon flips, sender pauses and resumes) |
| Mini controller (`ui/PlayerControls.kt`) | Renders on the Logs tab with previous/play-pause/next. Not verified that the D-pad reaches its buttons: UP from the nav bar focused the scrollable log area that contains it (uiautomator shows one focused node covering the content area). Worth a look on a TV. |
| Volume gesture (`ServerVolumeState` fallback) | No crash. Receiver slider stays at 100%: HDMI on Fire OS is a fixed-volume device (`Sending max volume for fixed volume device`), so `STREAM_MUSIC` is pinned at max. Platform behaviour. |
| Crashes seen | Only the two fixed above; no NoSuchMethodError / NoClassDefFoundError anywhere |

## Mirroring (macOS sender)

| Setting | Stream | FPS | Drops | Jitter | App PSS | CPU (app) |
|---|---|---|---|---|---|---|
| auto (1920x1080) | H.264 1920x1080, OMX.MTK HW | 54-63, ~60 steady | 0 | ~15-18 ms | 75 MB (28 MB GL) | ~15% |
| 1280x720 | H.264 1280x720, OMX.MTK HW | ~60 with motion, 30-40 on static screens | 0 | ~25-30 ms | 76 MB (18 MB GL) | ~21% |

Device MemAvailable during 1080p mirroring: ~137 MB. No lowmemorykiller kills of the app (37 LMK lines in the log, none for this package). With no sender connected the app uses ~40 MB PSS.
Startup: `Displayed +3.2 s`, and Choreographer skipped ~100 frames once at launch. The UI is usable after that.

## Audio-only (macOS whole-desktop audio)

Note: the Music app itself refuses the receiver ("The AirPlay device “Android AirPlay” is not compatible with this version of Music"); see fork issue #3. These sessions were system audio.

- ALAC, decoded by FFmpeg (`audio/alac decoder started (ffmpeg, forced)`); decode 0.25 ms mean per packet.
- Output: Oboe 1.9.3 falls back to OpenSL ES (no libaaudio), 44100 Hz stereo, buffer 2048 frames, burst 1024, `AUDIO_OUTPUT_FLAG_FAST denied`.
- Overlay over a ~1 min session: buffer ~140 ms, `xrun=0 drop=0`, 1 trim, 1-2 underruns at start. No audible-glitch reports.
- AAC-ELD (mirror audio) was not exercised; the Mac's mirroring sessions carried no audio stream.
- Track metadata showed "Unknown" (sender sent `Track:  - `).

## URL video (YouTube "AirPlay" from the Mac)

`video_play` with an HLS manifest; ExoPlayer (Media3 1.8.0) played ~27 s on `OMX.MTK.VIDEO.DECODER.AVC` with SoftAAC2 44.1 kHz audio.
Only noise: `PesReader: Unexpected start code prefix` and `ACodec setting nBufferCountActual failed`. Java heap grew from 13 MB to 31 MB during playback; no OOM.

## Open issues (not fixed)

1. **Volume read polling floods logcat.** `MainActivity` calls `updateFromService()` every 200 ms, which reads `getStreamVolume` 3 times. Fire OS logs each read for HDMI, about 15 lines a second (0 while the app is stopped). This comes from upstream; it costs log noise and binder calls, not correctness.
2. **Debug overlay keeps stale VIDEO stats** after mirroring ends and audio-only starts (it showed the last 1080p numbers until the app restarted). Cosmetic, upstream.
3. **DACP resolve can still wedge on Lollipop** if a resolve ever hangs for another reason (sender gone before the resolve finishes). `NsdManager.stopServiceResolution` is API 34+, so the only reset is restarting the app. Not seen after `9848cd6`.
4. **Not tested:** iPhone sender, HEVC (sender never chose it), AAC-ELD mirror audio (no mirroring session carried audio), mini controller D-pad reachability, PIN pairing, long sessions (>2 min), thermal behaviour (skin temp read 46 °C after 12 s).

## Device state left behind

- App installed (debug build with all 3 fixes), server running, `auto_start` on.
- App settings changed via ADB during testing were all reset: `debug_enabled=false`, `benchmark_log=false`, `resolution=auto`.
- The stick rebooted once during the session (not caused by this session). ADB over network left on.
- Logs and screenshots: `work/logcat.txt`, `work/s*.png`.
