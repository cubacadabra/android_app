package dev.andrewarrow.cubacadabra.nativebridge

import android.view.Surface

internal object NativeEngine {
    init {
        System.loadLibrary("cubacadabra_engine")
        System.loadLibrary("cubacadabra_jni")
    }

    external fun nativeCreate(): Long
    external fun nativeDestroy(engine: Long)
    external fun nativeLoad(engine: Long, bytes: ByteArray, packageManifest: Boolean): Boolean
    external fun nativeSetInput(
        engine: Long,
        forward: Float,
        strafe: Float,
        sprint: Boolean,
        jump: Boolean,
        lookX: Float,
        lookY: Float,
        zoomDelta: Float,
    )
    external fun nativeStep(engine: Long, delta: Float)
    external fun nativeReadFrame(engine: Long): FloatArray
    external fun nativeSetRemotePlayers(engine: Long, players: FloatArray)
    external fun nativeCreateRenderer(engine: Long, surface: Surface, width: Float, height: Float): Long
    external fun nativeResizeRenderer(renderer: Long, width: Float, height: Float)
    external fun nativeDrawRenderer(renderer: Long, engine: Long)
    external fun nativeDestroyRenderer(renderer: Long)
    external fun nativeSnapshotLength(): Int
    external fun nativeSettingsRoomState(engine: Long): Int
}
