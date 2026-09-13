package dev.andrewarrow.cubacadabra.app

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.andrewarrow.cubacadabra.game.AppAuthenticationService
import dev.andrewarrow.cubacadabra.game.AppAuthException
import dev.andrewarrow.cubacadabra.game.AppAuthResult
import dev.andrewarrow.cubacadabra.game.AppRuntime
import dev.andrewarrow.cubacadabra.game.ClientConfiguration
import dev.andrewarrow.cubacadabra.game.NativeGoogleSignInService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.URL
import kotlin.coroutines.coroutineContext

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val appRuntime = AppRuntime()
    private var appSnapshot = appRuntime.snapshot()
    private val appRequests = mutableMapOf<Long, Job>()
    private var appClosed = false
    private val authentication = AppAuthenticationService(application)
    private val googleSignIn = NativeGoogleSignInService(application)
    private var activityReference: WeakReference<Activity>? = null
    private var authenticationJob: Job? = null
    private var authenticationGeneration = 0L
    private var profileRevision = 0L
    private var started = false
    private var accessToken: String? = null
    private val _state = MutableStateFlow(
        AppUiState(profileUsername = appSnapshot.profile, safety = appSnapshot.safety, appearance = appSnapshot.appearance),
    )
    val state = _state.asStateFlow()
    private val _gameSession = MutableStateFlow(AccountGameSession())
    val gameSession = _gameSession.asStateFlow()

    fun attachActivity(activity: Activity) { activityReference = WeakReference(activity) }
    fun detachActivity(activity: Activity) {
        if (activityReference?.get() === activity) activityReference = null
    }

    fun start() { if (!started) refreshAuthentication() }

    fun refreshAuthentication() {
        if (authenticationJob?.isActive == true || _state.value.loginInProgress) return
        started = true
        val generation = authenticationGeneration
        val revision = profileRevision
        authenticationJob = viewModelScope.launch {
            val result = try { authentication.restore() }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { null }
            if (generation != authenticationGeneration) return@launch
            if (result != null) {
                val user = _state.value.authUser
                val accepted = if (user != null && result.user.id == user.id &&
                    (revision != profileRevision || appSnapshot.profile.usernameIsSaving || appSnapshot.profile.bodyIsSaving
                        || appSnapshot.profile.birthdayIsSaving)) {
                    result.copy(user = user)
                } else result
                applyAuthentication(accepted, replaceSession = false)
            } else clearSession()
            update { copy(isRestoring = false) }
        }
    }

    fun gameSessionRejected(sessionID: Long) {
        if (_gameSession.value.sessionID == sessionID && _state.value.isAuthenticated) refreshAuthentication()
    }

    fun requestSignIn() {
        if (!_state.value.loginInProgress) update { copy(loginDialogOpen = true, loginErrorMessage = null) }
    }

    fun dismissLoginDialog() {
        if (!_state.value.loginInProgress) update { copy(loginDialogOpen = false, loginErrorMessage = null) }
    }

    fun startGoogleSignIn() {
        val activity = activityReference?.get() ?: return
        beginSignIn {
            val credential = googleSignIn.signIn(activity)
            coroutineContext.ensureActive()
            authentication.authenticateGoogle(credential)
        }
    }

    fun signInWithEmail(email: String, password: String) {
        val normalizedEmail = email.trim()
        if (normalizedEmail.isEmpty() || password.isEmpty()) {
            update { copy(loginErrorMessage = "Enter your email and password.") }
            return
        }
        beginSignIn { authentication.authenticateEmail(normalizedEmail, password) }
    }

    private fun beginSignIn(operation: suspend () -> AppAuthResult) {
        if (_state.value.loginInProgress) return
        authenticationGeneration += 1
        authenticationJob?.cancel()
        val generation = authenticationGeneration
        update { copy(loginDialogOpen = true, loginInProgress = true, loginErrorMessage = null) }
        authenticationJob = viewModelScope.launch {
            try {
                val result = operation()
                coroutineContext.ensureActive()
                if (generation != authenticationGeneration) return@launch
                applyAuthentication(result)
                update { copy(loginDialogOpen = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: AppAuthException.Cancelled) {
                // Native sign-in UI was dismissed.
            } catch (error: Exception) {
                if (generation == authenticationGeneration) {
                    update { copy(loginDialogOpen = true, loginErrorMessage =
                        if (error is AppAuthException.Server && error.statusCode == 401)
                            "That email or password is not correct."
                        else "We could not finish signing you in. Please try again.") }
                }
            } finally {
                if (generation == authenticationGeneration) update { copy(loginInProgress = false, isRestoring = false) }
            }
        }
    }

    fun signOut() {
        authenticationGeneration += 1
        authenticationJob?.cancel()
        authentication.clearTokens()
        clearSession()
        update { copy(loginDialogOpen = false, loginInProgress = false, loginErrorMessage = null, isRestoring = false) }
        activityReference?.get()?.let { activity -> viewModelScope.launch { googleSignIn.signOut(activity) } }
    }

    private fun applyAuthentication(result: AppAuthResult, replaceSession: Boolean = true) {
            val current = _state.value.authUser
            val needsReplacement = replaceSession || current?.id != result.user.id
            || current?.username != result.user.username || current?.bodyID != result.user.bodyID
            || current?.dateOfBirth != result.user.dateOfBirth
        accessToken = result.accessToken
        update { copy(authUser = result.user) }
        if (needsReplacement) replaceAppSession()
        publishGameSession()
    }

    private fun clearSession() {
        accessToken = null
        update { copy(authUser = null) }
        replaceAppSession()
        publishGameSession()
    }

    private fun publishGameSession() {
        val user = _state.value.authUser
        _gameSession.value = AccountGameSession(
            sessionID = appSnapshot.sessionId,
            accountID = user?.id,
            accessToken = accessToken,
            username = user?.username,
            bodyID = user?.bodyID,
            appearanceJSON = appSnapshot.appearance.selectedLoadoutJSON,
            morphArtifactURLs = appSnapshot.appearance.assets.mapNotNull { asset ->
                val path = asset.artifactURL ?: return@mapNotNull null
                val url = runCatching { URL(ClientConfiguration.backendApiUrl.trimEnd('/') + "/", path) }.getOrNull()
                    ?: return@mapNotNull null
                asset.id to url.toString()
            }.toMap(),
            blockedUserIDs = appSnapshot.safety.blockedUserIDs.toSet(),
        )
    }

    fun draftMorphLoadoutJSON(): String? = appSnapshot.appearance.draftLoadoutJSON

    fun beginProfileUsernameEdit() = dispatchApp(JSONObject().put("type", "begin_username_edit"))
    fun changeProfileUsername(value: String) = dispatchApp(JSONObject().put("type", "username_changed").put("value", value))
    fun saveProfileUsername() = dispatchApp(JSONObject().put("type", "save_username"))
    fun loadAppearanceCatalog() = dispatchApp(JSONObject().put("type", "load_appearance_catalog"))
    fun beginMorphEdit() = dispatchApp(JSONObject().put("type", "begin_appearance_edit"))
    fun chooseMorphPreset(presetID: String) = dispatchApp(JSONObject().put("type", "select_morph_preset").put("preset_id", presetID))
    fun setMorphPart(assetID: String) = dispatchApp(JSONObject().put("type", "set_morph_part").put("asset_id", assetID))
    fun clearMorphPart(assetID: String) = dispatchApp(JSONObject().put("type", "clear_morph_part").put("asset_id", assetID))
    fun saveMorph() = dispatchApp(JSONObject().put("type", "save_appearance"))
    fun saveBirthday(dateOfBirth: String) = dispatchApp(JSONObject().put("type", "save_birthday").put("date_of_birth", dateOfBirth))
    fun loadCatalog(page: Int = 1, pageSize: Int = 20) = dispatchApp(
        JSONObject().put("type", "load_catalog").put("page", page).put("page_size", pageSize),
    )
    fun loadBlockedUsers() = dispatchApp(JSONObject().put("type", "load_blocked_users"))
    fun blockUser(userID: String) = dispatchApp(JSONObject().put("type", "block_user").put("user_id", userID))
    fun unblockUser(userID: String) = dispatchApp(JSONObject().put("type", "unblock_user").put("user_id", userID))

    private fun replaceAppSession() {
        appRequests.values.toList().forEach { it.cancel() }
        appRequests.clear()
        val user = _state.value.authUser
        dispatchApp(JSONObject().put("type", "replace_session")
            .put("account_id", user?.id ?: JSONObject.NULL)
            .put("username", user?.username ?: JSONObject.NULL)
            .put("body_id", user?.bodyID ?: JSONObject.NULL)
            .put("date_of_birth", user?.dateOfBirth ?: JSONObject.NULL))
        dispatchApp(JSONObject().put("type", "load_appearance_catalog"))
    }

    private fun dispatchApp(action: JSONObject) {
        if (appClosed) return
        val previousAppearanceJSON = appSnapshot.appearance.selectedBase?.let { base ->
            JSONObject().put("version", 2).put("base", base).put("parts", appSnapshot.appearance.selectedParts).put("face", appSnapshot.appearance.selectedFace ?: JSONObject.NULL).toString()
        }
        val previousMorphArtifactURLs = morphArtifactURLs()
        profileRevision += 1
        appRuntime.dispatch(action)
        appSnapshot = appRuntime.snapshot()
        update { copy(profileUsername = appSnapshot.profile, catalog = appSnapshot.catalog, safety = appSnapshot.safety, appearance = appSnapshot.appearance) }
        if (_gameSession.value.blockedUserIDs != appSnapshot.safety.blockedUserIDs.toSet()) {
            publishGameSession()
        }
        val nextAppearanceJSON = appSnapshot.appearance.selectedBase?.let { base ->
            JSONObject().put("version", 2).put("base", base).put("parts", appSnapshot.appearance.selectedParts).put("face", appSnapshot.appearance.selectedFace ?: JSONObject.NULL).toString()
        }
        if (previousAppearanceJSON != nextAppearanceJSON) publishGameSession()
        if (previousMorphArtifactURLs != morphArtifactURLs()) publishGameSession()
        val user = _state.value.authUser
        if (user != null && user.id == appSnapshot.accountId && user.username != appSnapshot.profile.username) {
            val name = appSnapshot.profile.username
            update { copy(authUser = user.copy(username = name)) }
            publishGameSession()
        }
        if (user != null && user.id == appSnapshot.accountId && user.bodyID != appSnapshot.profile.bodyID) {
            update { copy(authUser = user.copy(bodyID = appSnapshot.profile.bodyID)) }
            publishGameSession()
        }
        if (user != null && user.id == appSnapshot.accountId && user.dateOfBirth != appSnapshot.profile.dateOfBirth) {
            update { copy(authUser = user.copy(dateOfBirth = appSnapshot.profile.dateOfBirth)) }
        }
        while (true) {
            val effect = appRuntime.pollEffect() ?: break
            val token = authentication.appAccessToken()
            if ((effect.accountId != null && effect.accountId != _state.value.authUser?.id)
                || (effect.accountId != null && token == null)) {
                dispatchApp(JSONObject().put("type", "http_completed").put("effect_id", effect.effectId)
                    .put("status", 401).put("body", ""))
                continue
            }
            appRequests[effect.effectId] = viewModelScope.launch {
                try {
                    val response = authentication.performAppRequest(effect, token)
                    dispatchApp(JSONObject().put("type", "http_completed").put("effect_id", effect.effectId)
                        .put("status", response.statusCode).put("body", response.body))
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    dispatchApp(JSONObject().put("type", "http_failed").put("effect_id", effect.effectId))
                } finally {
                    appRequests.remove(effect.effectId)
                }
            }
        }
    }

    private fun morphArtifactURLs(): Map<String, String> = appSnapshot.appearance.assets.mapNotNull { asset ->
        val path = asset.artifactURL ?: return@mapNotNull null
        val url = runCatching { URL(ClientConfiguration.backendApiUrl.trimEnd('/') + "/", path) }.getOrNull()
            ?: return@mapNotNull null
        asset.id to url.toString()
    }.toMap()



    private fun update(transform: AppUiState.() -> AppUiState) { _state.value = transform(_state.value) }

    override fun onCleared() {
        appClosed = true
        authenticationJob?.cancel()
        appRequests.values.toList().forEach { it.cancel() }
        appRequests.clear()
        appRuntime.close()
        super.onCleared()
    }
}
