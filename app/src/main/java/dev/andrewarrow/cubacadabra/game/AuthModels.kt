package dev.andrewarrow.cubacadabra.game

data class AppAuthUser(
    val id: String,
    val email: String?,
    val name: String,
    val dateOfBirth: String?,
    val username: String?,
    val bodyID: String?,
)

data class AppAuthResult(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresIn: Int,
    val user: AppAuthUser,
    val browserHandoffCode: String?,
)


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
