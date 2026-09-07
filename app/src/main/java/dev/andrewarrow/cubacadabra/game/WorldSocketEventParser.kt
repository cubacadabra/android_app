package dev.andrewarrow.cubacadabra.game

import org.json.JSONObject

internal fun JSONObject.toSessionEvent(playerId: String) = SessionEvent(
    playerId = playerId,
    username = optString("username").takeIf { it.isNotBlank() },
    hasUsername = optBoolean("hasUsername"),
    loggedIn = optBoolean("loggedIn", optBoolean("authenticated")),
    authenticated = optBoolean("authenticated"),
    appearance = optJSONObject("appearance"),
)

internal fun JSONObject.toExperienceEvent(type: String): ExperienceEvent {
    val blocks = buildList {
        val values = optJSONArray("blocks") ?: return@buildList
        for (index in 0 until values.length()) {
            val block = values.optJSONObject(index) ?: continue
            add(
                BuildBlock(
                    id = block.optString("id"),
                    x = block.optDouble("x").toFloat(),
                    y = block.optDouble("y").toFloat(),
                    z = block.optDouble("z").toFloat(),
                    rotation = block.optInt("rotation", 0),
                    shape = block.optString("shape", "cube"),
                    color = block.optString("color", "coral"),
                ),
            )
        }
    }
    val playerIds = buildList {
        val values = optJSONArray("playerIds") ?: return@buildList
        for (index in 0 until values.length()) values.optString(index).takeIf { it.isNotBlank() }?.let(::add)
    }
    val launch = optJSONObject("launch") ?: this
    return ExperienceEvent(
        type = type,
        kind = optString("kind").takeIf { it.isNotBlank() },
        phase = optString("phase").takeIf { it.isNotBlank() },
        prompt = optString("prompt").takeIf { it.isNotBlank() },
        sessionWorldId = optString("sessionWorldId").takeIf { it.isNotBlank() },
        playerIds = playerIds,
        startsAt = launch.optLong("startsAt").takeIf { launch.has("startsAt") && !launch.isNull("startsAt") },
        serverNow = optLong("serverNow").takeIf { has("serverNow") },
        blocks = blocks,
    )
}

internal fun JSONObject.toMovementEvent(id: String, localPlayerId: String) = MovementEvent(
    playerId = id,
    player = RemotePlayer(
        position = Vec3(optDouble("x").toFloat(), optDouble("y").toFloat(), optDouble("z").toFloat()),
        yaw = optDouble("yaw").toFloat(),
        moving = optBoolean("moving"),
        sprinting = optBoolean("sprinting"),
        generation = optInt("generation", 0),
        motionSequence = optLong("motionSequence", 0L),
    ),
    isSelf = id == localPlayerId,
    corrected = optBoolean("corrected"),
    generation = optInt("generation", 0),
    motionSequence = optLong("motionSequence", 0L),
)

internal fun JSONObject.toPresenceEvent(type: String, id: String) = PresenceEvent(
    type = type,
    playerId = id,
    username = optString("username").takeIf { it.isNotBlank() },
    userId = optString("user_id").takeIf { it.isNotBlank() },
    generation = optInt("generation", 0),
    appearance = optJSONObject("appearance"),
)
