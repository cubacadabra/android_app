# Cubacadabra Android

This is the Kotlin/Jetpack Compose port of `../ios_app`. It loads the same
`first-game` package, drives the sibling Rust engine, and connects to the same
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

For local services, the Debug defaults match iOS:

```text
Game package: http://127.0.0.1:5173/games/first-game/
Backend:      ws://127.0.0.1:8787
```

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
