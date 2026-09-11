package dev.andrewarrow.cubacadabra.nativebridge

import android.view.Surface

internal object NativeEngine {
    init {
        System.loadLibrary("cubacadabra_client")
        System.loadLibrary("cubacadabra_app")
        System.loadLibrary("cubacadabra_jni")
    }

    external fun nativeCreate(manifest: ByteArray, script: ByteArray): Long
    external fun nativeAppCreate(): Long
    external fun nativeAppDestroy(app: Long)
    external fun nativeAppDispatch(app: Long, action: ByteArray): Boolean
    external fun nativeAppSnapshot(app: Long): ByteArray
    external fun nativeAppPollEffect(app: Long): ByteArray?
    external fun nativeDestroy(engine: Long)
    external fun nativeTransportConnected(engine: Long)
    external fun nativeTransportDisconnected(engine: Long)
    external fun nativeRequestTransport(engine: Long)
    external fun nativeReceiveTransportMessage(engine: Long, message: ByteArray): Boolean
    external fun nativeSetIgnoredPlayerIds(engine: Long, playerIds: ByteArray): Boolean
    external fun nativePollClientAction(engine: Long): ByteArray?
    external fun nativeSetInput(
        engine: Long,
        forward: Float,
        strafe: Float,
        sprint: Boolean,
        jump: Boolean,
        climb: Boolean,
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
    external fun nativePollAudioMessage(engine: Long): ByteArray?
    external fun nativeCreateRenderer(engine: Long, surface: Surface, width: Float, height: Float): Long
    external fun nativeResizeRenderer(renderer: Long, width: Float, height: Float)
    external fun nativeSetPackageImageAtlas(
        renderer: Long,
        width: Int,
        height: Int,
        pixels: ByteArray,
        regions: ByteArray,
    ): Boolean
    external fun nativeRegisterMorphPack(renderer: Long, pack: ByteArray): Boolean
    external fun nativeSetAvatarPreviewMode(renderer: Long, enabled: Boolean)
    external fun nativeDrawRenderer(renderer: Long, engine: Long)
    external fun nativeDestroyRenderer(renderer: Long)
    external fun nativeSnapshotLength(): Int
    external fun nativeSettingsRoomState(engine: Long): Int
    external fun nativeSetUsername(engine: Long, username: ByteArray): Boolean
    external fun nativeSetLocalAppearance(engine: Long, appearance: ByteArray): Boolean
    external fun nativeAppearanceRevision(engine: Long): Int
    external fun nativeStartWorld(engine: Long, world: Int): Boolean
    external fun nativePlayerRespawnEventId(engine: Long): Int
    external fun nativeSetBuildBlockCount(engine: Long, count: Int)
    external fun nativeSetBuildBlock(engine: Long, index: Int, x: Float, y: Float, z: Float, width: Float, height: Float, depth: Float, color: Int, rotation: Int)
}
