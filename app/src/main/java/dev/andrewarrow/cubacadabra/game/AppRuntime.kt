package dev.andrewarrow.cubacadabra.game

import dev.andrewarrow.cubacadabra.nativebridge.NativeEngine
import org.json.JSONObject

data class AppUsernameFeedback(val kind: String, val message: String)
data class AppBirthdayFeedback(val kind: String, val code: String, val message: String)
data class AppCatalogEntry(
    val cubeID: String,
    val version: String,
    val displayName: String,
    val packagePath: String,
    val assetBaseURL: String?,
)
data class AppCatalogFeedback(val kind: String, val code: String, val message: String)
data class AppSafetySnapshot(
    val blockedUserIDs: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val pendingAction: String? = null,
    val pendingUserID: String? = null,
    val feedback: AppCatalogFeedback? = null,
)
data class AppCatalogSnapshot(
    val entries: List<AppCatalogEntry> = emptyList(),
    val page: Int = 0,
    val hasNextPage: Boolean = false,
    val isLoading: Boolean = false,
    val feedback: AppCatalogFeedback? = null,
)
data class AppMorphAsset(val id: String, val kind: String, val displayName: String, val thumbnail: String?, val artifactURL: String?)
data class AppMorphPreset(val id: String, val displayName: String, val base: String, val parts: List<String>, val face: String?, val thumbnail: String?)
data class AppAppearanceSnapshot(
    val release: String? = null,
    val assets: List<AppMorphAsset> = emptyList(),
    val presets: List<AppMorphPreset> = emptyList(),
    val selectedBase: String? = null,
    val selectedParts: List<String> = emptyList(),
    val selectedFace: String? = null,
    val draftBase: String? = null,
    val draftParts: List<String> = emptyList(),
    val draftFace: String? = null,
    val draftPresetID: String? = null,
    val selectedLoadoutJSON: String? = null,
    val draftLoadoutJSON: String? = null,
    val draftCanSave: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val feedback: AppCatalogFeedback? = null,
)

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
    val dateOfBirth: String? = null,
    val birthdayIsSaving: Boolean = false,
    val birthdayFeedback: AppBirthdayFeedback? = null,
)

data class AppSnapshot(
    val sessionId: Long,
    val accountId: String?,
    val profile: AppProfileSnapshot,
    val catalog: AppCatalogSnapshot,
    val safety: AppSafetySnapshot,
    val appearance: AppAppearanceSnapshot,
)
data class AppHttpEffect(val effectId: Long, val accountId: String?, val method: String, val path: String, val body: String)

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
        val catalogJSON = json.getJSONObject("catalog")
        val safetyJSON = json.getJSONObject("safety")
        val appearanceJSON = json.getJSONObject("appearance")
        val catalogEntriesJSON = catalogJSON.getJSONArray("entries")
        val catalogEntries = List(catalogEntriesJSON.length()) { index ->
            catalogEntriesJSON.getJSONObject(index).let { entry ->
                AppCatalogEntry(
                    cubeID = entry.getString("cube_id"),
                    version = entry.getString("version"),
                    displayName = entry.getString("display_name"),
                    packagePath = entry.getString("package_path"),
                    assetBaseURL = entry.nullableString("asset_base_url"),
                )
            }
        }
        val catalogFeedback = if (catalogJSON.isNull("feedback")) null else catalogJSON.getJSONObject("feedback").let {
            AppCatalogFeedback(it.getString("kind"), it.getString("code"), it.getString("message"))
        }
        val safetyFeedback = if (safetyJSON.isNull("feedback")) null else safetyJSON.getJSONObject("feedback").let {
            AppCatalogFeedback(it.getString("kind"), it.getString("code"), it.getString("message"))
        }
        val blockedUserIDsJSON = safetyJSON.getJSONArray("blocked_user_ids")
        val blockedUserIDs = List(blockedUserIDsJSON.length()) { index ->
            blockedUserIDsJSON.getString(index)
        }
        val feedback = if (profile.isNull("username_feedback")) null else profile.getJSONObject("username_feedback").let {
            val kind = it.get("kind") as String
            check(kind == "success" || kind == "error")
            AppUsernameFeedback(kind, it.get("message") as String)
        }
        val bodyFeedback = if (profile.isNull("body_feedback")) null else profile.getJSONObject("body_feedback").let {
            AppUsernameFeedback(it.get("kind") as String, it.get("message") as String)
        }
        val birthdayFeedback = if (profile.isNull("birthday_feedback")) null else profile.getJSONObject("birthday_feedback").let {
            AppBirthdayFeedback(it.get("kind") as String, it.get("code") as String, it.get("message") as String)
        }
        val morphAssetsJSON = appearanceJSON.getJSONArray("assets")
        val morphAssets = List(morphAssetsJSON.length()) { index -> morphAssetsJSON.getJSONObject(index).let { asset ->
            AppMorphAsset(asset.getString("id"), asset.getString("kind"), asset.getString("display_name"), asset.nullableString("thumbnail"), asset.nullableString("artifact_url"))
        } }
        val morphPresetsJSON = appearanceJSON.getJSONArray("presets")
        val morphPresets = List(morphPresetsJSON.length()) { index -> morphPresetsJSON.getJSONObject(index).let { preset ->
            val partsJSON = preset.getJSONArray("parts")
            AppMorphPreset(preset.getString("id"), preset.getString("display_name"), preset.getString("base"), List(partsJSON.length()) { partsJSON.getString(it) }, preset.nullableString("face"), preset.nullableString("thumbnail"))
        } }
        val appearanceFeedback = if (appearanceJSON.isNull("feedback")) null else appearanceJSON.getJSONObject("feedback").let { AppCatalogFeedback(it.getString("kind"), it.getString("code"), it.getString("message")) }
        return AppSnapshot(
            json.getLong("session_id"), json.nullableString("account_id"),
            AppProfileSnapshot(
                profile.nullableString("username"), profile.get("username_draft") as String,
                profile.get("username_can_save") as Boolean, profile.get("username_is_saving") as Boolean, feedback,
                profile.nullableString("body_id"), profile.get("body_draft") as String,
                profile.get("body_can_save") as Boolean, profile.get("body_is_saving") as Boolean, bodyFeedback,
                profile.nullableString("date_of_birth"), profile.get("birthday_is_saving") as Boolean, birthdayFeedback,
            ),
            AppCatalogSnapshot(
                entries = catalogEntries,
                page = catalogJSON.getInt("page"),
                hasNextPage = catalogJSON.getBoolean("has_next_page"),
                isLoading = catalogJSON.getBoolean("is_loading"),
                feedback = catalogFeedback,
            ),
            AppSafetySnapshot(
                blockedUserIDs = blockedUserIDs,
                isLoading = safetyJSON.getBoolean("is_loading"),
                pendingAction = safetyJSON.nullableString("pending_action"),
                pendingUserID = safetyJSON.nullableString("pending_user_id"),
                feedback = safetyFeedback,
            ),
            AppAppearanceSnapshot(
                appearanceJSON.nullableString("release"), morphAssets, morphPresets,
                appearanceJSON.nullableString("selected_base"), appearanceJSON.stringList("selected_parts"), appearanceJSON.nullableString("selected_face"),
                appearanceJSON.nullableString("draft_base"), appearanceJSON.stringList("draft_parts"), appearanceJSON.nullableString("draft_face"),
                appearanceJSON.nullableString("draft_preset_id"), appearanceJSON.nullableString("selected_loadout_json"), appearanceJSON.nullableString("draft_loadout_json"),
                appearanceJSON.getBoolean("draft_can_save"), appearanceJSON.getBoolean("is_loading"), appearanceJSON.getBoolean("is_saving"), appearanceFeedback,
            ),
        )
    }

    fun pollEffect(): AppHttpEffect? {
        check(handle != 0L)
        val bytes = NativeEngine.nativeAppPollEffect(handle) ?: return null
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        check(json.getString("type") == "http_request") { "Unsupported app effect" }
        return AppHttpEffect(json.getLong("effect_id"), json.nullableString("account_id"),
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

private fun JSONObject.stringList(key: String): List<String> {
    val values = getJSONArray(key)
    return List(values.length()) { values.getString(it) }
}
