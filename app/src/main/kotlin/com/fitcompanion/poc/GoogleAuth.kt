package com.fitcompanion.poc

import android.app.Activity
import android.app.PendingIntent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google sign-in for the Google Health API using Google Play services' AuthorizationClient.
 *
 * - No client secret is in the app: Google identifies the app by its package name + signing
 *   certificate (SHA-1) registered in your Google Cloud project.
 * - The app only ever holds a short-lived access token (about 1 hour) in memory. When it expires,
 *   calling [authorize] again returns a fresh one without a prompt if consent is still valid.
 * - Read-only scopes only.
 */
object GoogleAuth {
    private const val BASE = "https://www.googleapis.com/auth/googlehealth."
    val SCOPES = listOf(
        BASE + "activity_and_fitness.readonly",          // exercise, steps, distance
        BASE + "health_metrics_and_measurements.readonly", // heart rate, resting HR, HRV
        BASE + "sleep.readonly",
        BASE + "location.readonly",                      // GPS route in TCX export
    )

    sealed interface Outcome {
        data class Token(val accessToken: String, val grantedScopes: List<String>) : Outcome
        data class NeedsUserConsent(val pendingIntent: PendingIntent) : Outcome
    }

    suspend fun authorize(activity: Activity): Outcome {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(SCOPES.map { Scope(it) })
            .build()
        val result = Identity.getAuthorizationClient(activity).authorize(request).await()
        return toOutcome(result)
    }

    /** Call with the Intent returned from the consent screen. */
    fun fromConsentResult(activity: Activity, data: android.content.Intent?): Outcome {
        val result = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
        return toOutcome(result)
    }

    private fun toOutcome(result: AuthorizationResult): Outcome {
        val pi = result.pendingIntent
        return if (result.hasResolution() && pi != null) {
            Outcome.NeedsUserConsent(pi)
        } else {
            val token = result.accessToken ?: error("No access token returned")
            Outcome.Token(token, result.grantedScopes)
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
