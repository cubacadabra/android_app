package dev.andrewarrow.cubacadabra.game

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import dev.andrewarrow.cubacadabra.R

class NativeGoogleSignInService(context: Context) {
    private companion object {
        const val TAG = "NativeGoogleSignIn"
    }

    private val applicationContext = context.applicationContext
    private val clientId = applicationContext.getString(R.string.google_client_id)

    suspend fun signIn(activity: Activity): String {
        val credentialManager = CredentialManager.create(activity)
        val result = try {
            credentialManager.getCredential(
                context = activity,
                request = credentialRequest(filterByAuthorizedAccounts = true),
            )
        } catch (error: NoCredentialException) {
            Log.d(TAG, "No authorized Google credential; retrying with account selection", error)
            try {
                credentialManager.getCredential(
                    context = activity,
                    request = credentialRequest(filterByAuthorizedAccounts = false),
                )
            } catch (_: NoCredentialException) {
                Log.d(TAG, "No Google credential from account selection; retrying button flow")
                credentialManager.getCredential(
                    context = activity,
                    request = signInWithGoogleRequest(),
                )
            }
        } catch (error: GetCredentialCancellationException) {
            Log.w(
                TAG,
                "Credential Manager cancelled Google sign-in " +
                    "type=${error::class.java.name} message=${error.message}",
                error,
            )
            throw AppAuthException.Cancelled
        }

        val credential = result.credential
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            throw AppAuthException.InvalidResponse()
        }

        return try {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (error: GoogleIdTokenParsingException) {
            Log.w(TAG, "Google returned an invalid ID token credential", error)
            throw AppAuthException.InvalidResponse(error)
        }
    }

    suspend fun signOut(activity: Activity) {
        try {
            CredentialManager.create(activity).clearCredentialState(
                ClearCredentialStateRequest(),
            )
        } catch (error: Throwable) {
            Log.w(TAG, "Could not clear Google credential state", error)
        }
    }

    private fun credentialRequest(filterByAuthorizedAccounts: Boolean): GetCredentialRequest {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
            .setServerClientId(clientId)
            .build()
        return GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
    }

    private fun signInWithGoogleRequest(): GetCredentialRequest {
        val option = GetSignInWithGoogleOption.Builder(clientId)
            .build()
        return GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
    }
}
