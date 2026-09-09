package dev.andrewarrow.cubacadabra.app

import dev.andrewarrow.cubacadabra.game.AppAuthUser
import dev.andrewarrow.cubacadabra.game.AppProfileSnapshot

data class AppUiState(
    val isRestoring: Boolean = true,
    val authUser: AppAuthUser? = null,
    val loginDialogOpen: Boolean = false,
    val loginInProgress: Boolean = false,
    val loginErrorMessage: String? = null,
    val profileUsername: AppProfileSnapshot = AppProfileSnapshot(),
    val morphSaving: Boolean = false,
    val morphMessage: String? = null,
    val morphMessageIsError: Boolean = false,
) {
    val isAuthenticated: Boolean get() = authUser != null
}

// Read-only input to gameplay. Credentials never enter the public UI snapshot.
data class AccountGameSession(
    val sessionID: Long = 0,
    val accountID: String? = null,
    val accessToken: String? = null,
    val username: String? = null,
    val bodyID: String? = null,
)
