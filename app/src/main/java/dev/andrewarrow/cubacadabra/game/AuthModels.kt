package dev.andrewarrow.cubacadabra.game

data class AppAuthUser(
    val id: String,
    val email: String?,
    val name: String,
    val dateOfBirth: String?,
    val username: String?,
)

data class AppAuthResult(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresIn: Int,
    val user: AppAuthUser,
    val browserHandoffCode: String?,
)

data class AppProfileUpdateResult(val user: AppAuthUser, val age: Int?)

sealed class AppProfileException : Exception() {
    data object Unauthorized : AppProfileException()
    data class Server(val statusCode: Int, val code: String?) : AppProfileException()
}

sealed class AppAuthException : Exception() {
    data object Cancelled : AppAuthException()
    class InvalidResponse(cause: Throwable? = null) : AppAuthException() {
        init {
            cause?.let { initCause(it) }
        }
    }
    data object Unavailable : AppAuthException()
    data class Server(val statusCode: Int) : AppAuthException()
}
