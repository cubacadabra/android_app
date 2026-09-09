package dev.andrewarrow.cubacadabra.game

import dev.andrewarrow.cubacadabra.nativebridge.NativeEngine
import org.json.JSONObject

data class AppUsernameFeedback(val kind: String, val message: String)

data class AppProfileSnapshot(
    val username: String? = null,
    val usernameDraft: String = "",
    val usernameCanSave: Boolean = false,
    val usernameIsSaving: Boolean = false,
    val usernameFeedback: AppUsernameFeedback? = null,
    val bodyID: String? = null,
    val bodyDraft: String = "",
    val bodyCanSave: Boolean = false,
    val bodyIsSaving: Boolean = false,
    val bodyFeedback: AppUsernameFeedback? = null,
)

data class AppSnapshot(val sessionId: Long, val accountId: String?, val profile: AppProfileSnapshot)
data class AppHttpEffect(val effectId: Long, val accountId: String, val method: String, val path: String, val body: String)

/** Main-thread owned by AppViewModel. JSON shape errors are binding/build errors. */
class AppRuntime : AutoCloseable {
    private var handle = NativeEngine.nativeAppCreate().also { check(it != 0L) }

    fun dispatch(action: JSONObject) {
        check(handle != 0L)
        check(NativeEngine.nativeAppDispatch(handle, action.toString().toByteArray(Charsets.UTF_8))) { "Invalid app action" }
    }

    fun snapshot(): AppSnapshot {
        check(handle != 0L)
        val json = JSONObject(String(NativeEngine.nativeAppSnapshot(handle), Charsets.UTF_8))
        check(json.getInt("protocol_version") == 1) { "Unsupported app protocol" }
        val profile = json.getJSONObject("profile")
        val feedback = if (profile.isNull("username_feedback")) null else profile.getJSONObject("username_feedback").let {
            val kind = it.get("kind") as String
            check(kind == "success" || kind == "error")
            AppUsernameFeedback(kind, it.get("message") as String)
        }
        val bodyFeedback = if (profile.isNull("body_feedback")) null else profile.getJSONObject("body_feedback").let {
            AppUsernameFeedback(it.get("kind") as String, it.get("message") as String)
        }
        return AppSnapshot(
            json.getLong("session_id"), json.nullableString("account_id"),
            AppProfileSnapshot(
                profile.nullableString("username"), profile.get("username_draft") as String,
                profile.get("username_can_save") as Boolean, profile.get("username_is_saving") as Boolean, feedback,
                profile.nullableString("body_id"), profile.get("body_draft") as String,
                profile.get("body_can_save") as Boolean, profile.get("body_is_saving") as Boolean, bodyFeedback,
            ),
        )
    }

    fun pollEffect(): AppHttpEffect? {
        check(handle != 0L)
        val bytes = NativeEngine.nativeAppPollEffect(handle) ?: return null
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        check(json.getString("type") == "http_request") { "Unsupported app effect" }
        return AppHttpEffect(json.getLong("effect_id"), json.get("account_id") as String,
            json.get("method") as String, json.get("path") as String, json.get("body") as String)
    }

    override fun close() {
        if (handle != 0L) NativeEngine.nativeAppDestroy(handle)
        handle = 0L
    }
}

private fun JSONObject.nullableString(key: String): String? {
    val value = get(key)
    return if (value == JSONObject.NULL) null else value as String
}
