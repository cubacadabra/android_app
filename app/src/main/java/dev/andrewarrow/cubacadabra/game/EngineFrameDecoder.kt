package dev.andrewarrow.cubacadabra.game

import dev.andrewarrow.cubacadabra.nativebridge.NativeEngine

internal fun FloatArray.decodeFrame(): EngineFrame {
    val metadata = NativeEngine.nativeSnapshotLength()
    val padCount = getOrElse(metadata + 3) { 0f }.toInt()
    val pads = (0 until padCount).map { index ->
        val offset = metadata + 8 + index * 3
        EnginePad(
            getOrElse(offset) { 0f }.toInt(),
            getOrElse(offset + 1) { 0f },
            getOrElse(offset + 2) { 0f }.toInt(),
        )
    }
    return EngineFrame(
        player = EnginePlayer(Vec3(this[0], this[1], this[2]), this[3], this[6] > 0.5f, this[7] > 0.5f),
        agents = getOrElse(metadata) { 0f }.toInt(),
        remotePlayers = getOrElse(metadata + 2) { 0f }.toInt(),
        pads = pads,
        activeWorldIndex = getOrElse(metadata + 4) { 0f }.toInt(),
        cameraYaw = getOrElse(metadata + 5) { 0f },
    )
}

internal fun padStatus(pad: EnginePad?) = when {
    pad == null -> "READY"
    pad.seconds > 0f -> "${pad.occupants} · ${pad.seconds.toInt()}s"
    else -> "${pad.occupants} READY"
}
