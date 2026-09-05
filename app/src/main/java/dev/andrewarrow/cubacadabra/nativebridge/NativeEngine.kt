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
    external fun nativeScriptError(engine: Long): ByteArray
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
    external fun nativeSetUiViewport(
        engine: Long,
        width: Float,
        height: Float,
        scale: Float,
        safeTop: Float,
        safeRight: Float,
        safeBottom: Float,
        safeLeft: Float,
    )
    external fun nativeSetAuthenticated(engine: Long, authenticated: Boolean)
    external fun nativeUiPointer(engine: Long, pointerId: Long, phase: Int, x: Float, y: Float): Boolean
    external fun nativePollUiEvent(engine: Long): Boolean
    external fun nativeUiEvent(engine: Long): ByteArray
    external fun nativeStep(engine: Long, delta: Float)
    external fun nativeReadFrame(engine: Long): FloatArray
    external fun nativeSetRemotePlayers(engine: Long, players: FloatArray)
    external fun nativeCreateRenderer(engine: Long, surface: Surface, width: Float, height: Float): Long
    external fun nativeResizeRenderer(renderer: Long, width: Float, height: Float)
    external fun nativeDrawRenderer(renderer: Long, engine: Long)
    external fun nativeDestroyRenderer(renderer: Long)
    external fun nativeSnapshotLength(): Int
    external fun nativeSettingsRoomState(engine: Long): Int
    external fun nativeSetUsername(engine: Long, username: ByteArray): Boolean
    external fun nativeStartWorld(engine: Long, world: Int): Boolean
    external fun nativeReconcilePlayer(engine: Long, x: Float, y: Float, z: Float, yaw: Float)
    external fun nativeSetBuildBlockCount(engine: Long, count: Int)
    external fun nativeSetBuildBlock(engine: Long, index: Int, x: Float, y: Float, z: Float, width: Float, height: Float, depth: Float, color: Int, rotation: Int)
}
