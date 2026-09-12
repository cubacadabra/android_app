package dev.andrewarrow.cubacadabra.app

import dev.andrewarrow.cubacadabra.game.AppAuthUser
import dev.andrewarrow.cubacadabra.game.AppProfileSnapshot
import dev.andrewarrow.cubacadabra.game.AppCatalogSnapshot
import dev.andrewarrow.cubacadabra.game.AppSafetySnapshot
import dev.andrewarrow.cubacadabra.game.AppAppearanceSnapshot

data class AppUiState(
    val isRestoring: Boolean = true,
    val authUser: AppAuthUser? = null,
    val loginDialogOpen: Boolean = false,
    val loginInProgress: Boolean = false,
    val loginErrorMessage: String? = null,
    val profileUsername: AppProfileSnapshot = AppProfileSnapshot(),
    val catalog: AppCatalogSnapshot = AppCatalogSnapshot(),
    val safety: AppSafetySnapshot = AppSafetySnapshot(),
    val appearance: AppAppearanceSnapshot = AppAppearanceSnapshot(),
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
    val appearanceJSON: String? = null,
    val blockedUserIDs: Set<String> = emptySet(),
)
