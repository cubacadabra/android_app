package dev.andrewarrow.cubacadabra.app

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.andrewarrow.cubacadabra.game.AppAuthenticationService
import dev.andrewarrow.cubacadabra.game.AppAuthException
import dev.andrewarrow.cubacadabra.game.AppAuthResult
import dev.andrewarrow.cubacadabra.game.AppProfileException
import dev.andrewarrow.cubacadabra.game.AppRuntime
import dev.andrewarrow.cubacadabra.game.NativeGoogleSignInService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.lang.ref.WeakReference
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
    private val _state = MutableStateFlow(AppUiState(profileUsername = appSnapshot.profile))
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
                    (revision != profileRevision || appSnapshot.profile.usernameIsSaving)) {
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
        val needsReplacement = replaceSession || current?.id != result.user.id || current?.username != result.user.username
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
        _gameSession.value = AccountGameSession(appSnapshot.sessionId, user?.id, accessToken, user?.username, user?.bodyID)
    }

    fun beginProfileUsernameEdit() = dispatchApp(JSONObject().put("type", "begin_username_edit"))
    fun changeProfileUsername(value: String) = dispatchApp(JSONObject().put("type", "username_changed").put("value", value))
    fun saveProfileUsername() = dispatchApp(JSONObject().put("type", "save_username"))

    private fun replaceAppSession() {
        appRequests.values.toList().forEach { it.cancel() }
        appRequests.clear()
        val user = _state.value.authUser
        dispatchApp(JSONObject().put("type", "replace_session")
            .put("account_id", user?.id ?: JSONObject.NULL)
            .put("username", user?.username ?: JSONObject.NULL))
        update { copy(morphSaving = false, morphMessage = null, morphMessageIsError = false) }
    }

    private fun dispatchApp(action: JSONObject) {
        if (appClosed) return
        profileRevision += 1
        appRuntime.dispatch(action)
        appSnapshot = appRuntime.snapshot()
        update { copy(profileUsername = appSnapshot.profile) }
        val user = _state.value.authUser
        if (user != null && user.id == appSnapshot.accountId && user.username != appSnapshot.profile.username) {
            val name = appSnapshot.profile.username
            update { copy(authUser = user.copy(username = name)) }
            publishGameSession()
        }
        while (true) {
            val effect = appRuntime.pollEffect() ?: break
            val token = authentication.appAccessToken()
            if (effect.accountId != _state.value.authUser?.id || token == null) {
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

    fun saveMorph(bodyID: String) {
        val sessionID = appSnapshot.sessionId
        update { copy(morphSaving = true, morphMessage = null, morphMessageIsError = false) }
        viewModelScope.launch {
            if (appSnapshot.sessionId != sessionID) return@launch
            runCatching { authentication.saveAvatar(bodyID) }
                .onSuccess { result ->
                    if (appSnapshot.sessionId != sessionID || result.user.id != _state.value.authUser?.id) return@onSuccess
                    update { copy(authUser = authUser?.copy(bodyID = result.user.bodyID)) }
                    profileRevision += 1
                    publishGameSession()
                    update {
                        copy(
                            morphSaving = false,
                            morphMessage = "Morph saved.",
                            morphMessageIsError = false,
                        )
                    }
                }
                .onFailure { error ->
                    if (appSnapshot.sessionId != sessionID) return@onFailure
                    val message = when (error) {
                        is AppProfileException.Server -> when (error.code) {
                            "invalid_body_id" -> "Choose one of the available morphs."
                            "age_required" -> "Complete your birthday before choosing a morph."
                            "not_authenticated" -> "Your sign-in has expired. Please sign in again."
                            else -> "We couldn’t save your morph. Please try again."
                        }
                        is AppProfileException.Unauthorized -> "Your sign-in has expired. Please sign in again."
                        else -> "We couldn’t save your morph. Please try again."
                    }
                    update {
                        copy(
                            morphSaving = false,
                            morphMessage = message,
                            morphMessageIsError = true,
                        )
                    }
                }
        }
    }

    fun clearMorphMessage() {
        update { copy(morphMessage = null, morphMessageIsError = false) }
    }


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
