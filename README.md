# OmniMirror

Mirrors an Android phone's screen onto an iPad over the local Wi-Fi network. No cloud, no
account, no internet connection.

- **Android sender** (`android/app`): captures the screen with MediaProjection, encodes
  hardware H.264, serves receivers.
- **iPad receiver**, two forms:
  - **Safari**: open the address the phone shows. Nothing to install.
  - **Native app** (`ios/`): Bonjour discovery, VideoToolbox decoding. Needs signing to
    install; see [docs/IPAD_WITHOUT_A_MAC.md](docs/IPAD_WITHOUT_A_MAC.md).

## Status

The streaming path is tested end to end with a simulated sender and a real browser decoder.
The Android app and the iPad app compile in CI but **have not yet run on real hardware**, and
Safari itself is untested. Expect a round of fixes after the first real run.
Details and measurements: [docs/TESTING.md](docs/TESTING.md).

## Quick start

1. **Get the APK.** Open the repository's *Actions* tab → latest *Android* run → download the
   `OmniMirror-android` artifact → install the APK on the phone. (Each CI build is signed with
   a fresh debug key, so uninstall the old version before installing a new one.)
2. Put the phone and iPad on the same Wi-Fi (or join the iPad to the phone's hotspot).
3. On the phone: **Start mirroring** and accept the screen-capture dialog.
4. On the iPad: open the address shown on the phone in Safari and enter the 6-digit code.
5. Tap the picture to show or hide the status overlay. Stop from the phone app or its notification.

## Building

Android, from any Linux/macOS/Windows machine or CI (JDK 17+ and the Android SDK):

```
cd android
./gradlew :core:test :app:assembleDebug     # APK in app/build/outputs/apk/debug/
```

Building on the phone itself in Termux is not supported: the Android Gradle plugin downloads
x86 build tools. Pushing to GitHub and letting the *Android* workflow build is the phone-only route.

iPad app (needs macOS; the *iOS* workflow does this on a hosted Mac):

```
cd ios && xcodegen generate && open OmniMirror.xcodeproj
```

## Documentation

- [Architecture](docs/ARCHITECTURE.md): API research, transport comparison, limitations, roadmap
- [Protocol](docs/PROTOCOL.md)
- [iPad without a Mac](docs/IPAD_WITHOUT_A_MAC.md)
- [Testing and measurements](docs/TESTING.md)
