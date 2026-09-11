# Cubacadabra Android

This is the Kotlin/Jetpack Compose port of `../ios_app`. It loads the same
portable game package, drives the sibling Rust engine, and connects to the same
world WebSocket service.

The app intentionally has one small state holder: `GameViewModel` owns the
engine lifecycle, frame loop inputs, package loading, and world socket. Screen
state stays in Compose. There are no repositories or dependency-injection
layers because this client has one data source and one implementation.

## Rust integration

Gradle runs `scripts/build_rust_android.sh` before the native build. The script
uses the Android NDK LLVM clang toolchain to build the Rust `cdylib` for
`aarch64-linux-android` and `x86_64-linux-android`, then places each `.so` under
the matching generated `jniLibs` ABI directory. CMake imports those libraries
and links a small JNI shim (`app/src/main/cpp/jni_bridge.c`) against them.

The shim owns the Android surface conversion: it obtains an `ANativeWindow`
from the Compose-hosted `SurfaceView`, retains it for the renderer lifetime,
and exposes the narrow native API used by Kotlin. Rust maps that pointer to
`raw_window_handle::AndroidNdkWindowHandle` and uses `wgpu`'s Vulkan/GLES
backends. The existing iOS Core Animation/Metal path remains intact.

Android Studio must have an NDK installed. The build script discovers it from
`ANDROID_NDK_HOME`, `ANDROID_NDK_ROOT`, `ANDROID_SDK_ROOT`, `ANDROID_HOME`, or
the SDK path in `local.properties`.

Gradle builds all three sibling game sources with the shared `../tools` CLI and
includes each generated package directory in the APK. Debug builds
always start from that bundle. Release builds use a validated cached package
only when its manifest has a semantic `version` strictly newer than the
bundled manifest; an equal, older, or unversioned cache cannot mask a package
shipped in an app update. The app refreshes `manifest.json` and `game.luau`
from the configured host for a future launch, so remotely published package
updates must increment their manifest version.

For local services, the Debug defaults match iOS:

```text
Game package: http://10.0.2.2:5173/games/first-game/
Backend:      ws://10.0.2.2:8787
```

`10.0.2.2` is the Android Studio emulator's alias for the development
machine's loopback interface. Start the local services normally with
`npm run dev` in `../backend` and the web dev server in `../web`.

For a physical Android device, or an emulator configured to use the LAN,
replace `10.0.2.2` with the host's reachable LAN address and start the backend
and web servers with their LAN commands so they bind to `0.0.0.0`. The device
and host must be on a routable network, and the macOS firewall must allow ports
8787 and 5173.

Override either value with Gradle properties when the device is on the LAN:

```text
CUBACADABRA_GAME_BASE_URL=http://192.168.1.10:5173/games/first-game/
CUBACADABRA_BACKEND_URL=ws://192.168.1.10:8787
```

The Android surface approach follows the current [`wgpu` surface target
API](https://docs.rs/wgpu/latest/wgpu/enum.SurfaceTargetUnsafe.html), whose
raw-handle path requires the native window to remain valid through surface
destruction, and Android's ABI-specific native-library packaging guidance
([Android NDK ABIs](https://developer.android.com/ndk/guides/abis), [prebuilt
libraries](https://developer.android.com/ndk/guides/prebuilts)).

### Licensing

Copyright (C) 2026 Andrew Arrow

Licensed under the GNU General Public License v3.0 or later.
See [LICENSE](LICENSE).
