package com.ethosprotocol.services

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.credentials.*
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialInterruptedException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import com.ethosprotocol.api.ApiCallFailedException
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.models.AuthChallenge
import com.ethosprotocol.models.PasskeyRegisterRequest
import com.ethosprotocol.models.PasskeyVerifyRequest
import com.ethosprotocol.models.RecoveryCompleteRequest
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** Carries a message already mapped to something actionable for [AuthUiState.error]. */
class PasskeyException(message: String) : Exception(message)

/**
 * Signals that the device has no biometric/screen-lock enrollment, so a passkey ceremony
 * cannot proceed. Carries the guidance copy plus the settings action the UI should surface
 * so onboarding can walk the user through enrollment and then retry (#423).
 */
class BiometricEnrollmentRequiredException(
    message: String,
    val settingsIntent: Intent
) : Exception(message)

private const val RP_ID = "ethos-protocol.app"

/**
 * Factory that produces a [CredentialManager] from an [Activity].
 *
 * Injecting this interface instead of calling [CredentialManager.create] directly lets unit
 * tests supply a fake implementation without requiring Robolectric or a real device.  The
 * production binding in [com.ethosprotocol.di.AppModule] simply wraps the static factory call.
 */
fun interface CredentialManagerFactory {
    fun create(activity: Activity): CredentialManager
}

@Singleton
class PasskeyService @Inject constructor(
    private val apiClient: ApiClient,
    private val tokenProvider: TokenProvider,
    private val credentialManagerFactory: CredentialManagerFactory
) {
    /**
     * #423: true when the device can't run a passkey ceremony because no biometric or
     * screen lock is enrolled. Checked before prompting so onboarding can show guidance
     * instead of a dead-end CredentialManager error.
     */
    fun isBiometricEnrollmentMissing(context: Context): Boolean =
        BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL) != BiometricManager.BIOMETRIC_SUCCESS

    /**
     * #423: intent that opens the device's biometric/screen-lock enrollment settings so the
     * guidance screen can link the user straight to setup.
     */
    fun biometricEnrollmentSettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
                Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }

    /**
     * #423: throws [BiometricEnrollmentRequiredException] when enrollment is missing so the
     * caller can render the setup guidance screen and retry after the user returns.
     */
    fun requireBiometricEnrollment(context: Context) {
        if (isBiometricEnrollmentMissing(context)) {
            throw BiometricEnrollmentRequiredException(
                "Set up a fingerprint, face unlock, or device PIN/passcode to use passkeys.",
                biometricEnrollmentSettingsIntent()
            )
        }
    }

    suspend fun register(activity: Activity, username: String): Result<Unit> = runCatching {
        requireBiometricEnrollment(activity)
        val normalizedUsername = UsernameValidator.sanitize(username)
        require(UsernameValidator.isValid(normalizedUsername)) { "Invalid username" }
        val challenge = requireSuccess(apiClient.getChallenge()).challenge
        val requestJson = PasskeyRequestBuilder.registrationRequestJson(challenge, normalizedUsername)

        val credManager = credentialManagerFactory.create(activity)
        // #210: devices with no biometric enrolled (or biometrics disabled by policy) throw
        // here — map to tailored copy instead of letting a generic CredentialManager error
        // reach the user with no indication of what to actually do about it.
        val resp = try {
            credManager.createCredential(activity, CreatePublicKeyCredentialRequest(requestJson))
                    as CreatePublicKeyCredentialResponse
        } catch (e: CreateCredentialException) {
            throw PasskeyException(mapCreateCredentialError(e))
        }
        val json = JSONObject(resp.registrationResponseJson)
        val regReq = PasskeyRegisterRequest(
            credentialId = json.getString("id"),
            publicKey = extractCosePublicKey(json.getJSONObject("response").getString("attestationObject")),
            clientDataJson = json.getJSONObject("response").getString("clientDataJSON")
        )
        // The backend returns a session token straight from registration, so there's no
        // need to immediately run a second CredentialManager ceremony (and second
        // biometric prompt) just to sign in with the passkey we just created.
        val authToken = requireSuccess(apiClient.registerPasskey(regReq))
        tokenProvider.setSession(authToken)
    }.onFailure { if (it is CancellationException) throw it }

    // Links a freshly-created passkey to an existing account for a user who lost their
    // original device — the recovery token proves they completed initiateRecovery() first.
    suspend fun recoverAccount(activity: Activity, username: String, recoveryToken: String): Result<Unit> = runCatching {
        requireBiometricEnrollment(activity)
        val json = createPasskeyCredential(activity, username)
        val completeReq = RecoveryCompleteRequest(
            recoveryToken = recoveryToken,
            credentialId = json.getString("id"),
            publicKey = extractCosePublicKey(json.getJSONObject("response").getString("attestationObject")),
            clientDataJson = json.getJSONObject("response").getString("clientDataJSON")
        )
        requireSuccess(apiClient.completeRecovery(completeReq))
    }.onFailure { if (it is CancellationException) throw it }

    private suspend fun createPasskeyCredential(activity: Activity, username: String): JSONObject {
        val challenge = requireSuccess(apiClient.getChallenge())
        val requestJson = buildRegistrationRequestJson(challenge, username)

        val credManager = credentialManagerFactory.create(activity)
        val resp = try {
            credManager.createCredential(activity, CreatePublicKeyCredentialRequest(requestJson))
                    as CreatePublicKeyCredentialResponse
        } catch (e: CreateCredentialException) {
            throw PasskeyException(mapCreateCredentialError(e))
        }
        return JSONObject(resp.registrationResponseJson)
    }

    suspend fun authenticate(activity: Activity): Result<Unit> = runCatching {
        requireBiometricEnrollment(activity)
        val challenge = requireSuccess(apiClient.getChallenge())
        val requestJson = JSONObject()
            .put("challenge", challenge.challenge).put("rpId", RP_ID)
            .put("userVerification", "required").toString()

        val credManager = credentialManagerFactory.create(activity)
        val request = GetCredentialRequest(listOf(GetPublicKeyCredentialOption(requestJson)))
        val credential = try {
            credManager.getCredential(activity, request).credential as PublicKeyCredential
        } catch (e: GetCredentialException) {
            throw PasskeyException(mapGetCredentialError(e))
        }
        val json = JSONObject(credential.authenticationResponseJson)
        val verifyReq = PasskeyVerifyRequest(
            credentialId = json.getString("id"),
            clientDataJson = json.getJSONObject("response").getString("clientDataJSON"),
            signature = json.getJSONObject("response").getString("signature")
        )
        tokenProvider.setSession(requireSuccess(apiClient.verifyPasskey(verifyReq)))
    }.onFailure { if (it is CancellationException) throw it }

    // Every CredentialManager failure previously collapsed into one generic message. Map the
    // subtypes that carry actionable meaning; anything else falls back to a generic retry prompt.
    private fun mapCreateCredentialError(e: CreateCredentialException): String = when (e) {
        is CreateCredentialCancellationException -> "Sign-up canceled."
        // #210: this fires on devices with no biometric enrolled (or an unlockable screen
        // lock at all) — e.g. older/budget hardware below this app's usual assumptions, or
        // biometrics disabled by MDM policy. Direct the user to a fix instead of a dead end.
        is CreateCredentialNoCreateOptionException ->
            "This device has no biometric or screen lock set up, so it can't create a passkey. " +
            "Enroll a fingerprint or face unlock, or set a device PIN/passcode, in Settings and try again."
        is CreateCredentialProviderConfigurationException ->
            "No passkey provider is set up on this device."
        is CreateCredentialInterruptedException -> "Setup was interrupted — please try again."
        else -> "Couldn't create a passkey on this device. Please try again."
    }

    private fun mapGetCredentialError(e: GetCredentialException): String = when (e) {
        is GetCredentialCancellationException -> "Sign-in canceled."
        is GetCredentialInterruptedException -> "Sign-in was interrupted — please try again."
        is GetCredentialProviderConfigurationException ->
            "No passkey provider is set up on this device."
        is NoCredentialException ->
            "No passkey found for this account on this device. Sign up or recover your account first."
        else -> "Couldn't sign in with a passkey. Please try again."
    }

    private fun buildRegistrationRequestJson(challenge: AuthChallenge, username: String): String =
        PasskeyRequestBuilder.registrationRequestJson(challenge, UsernameValidator.sanitize(username))

    private fun extractCosePublicKey(attestationObject: String): String {
        val bytes = Base64.getUrlDecoder().decode(attestationObject)
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun <T> requireSuccess(result: ApiResult<T>): T = when (result) {
        is ApiResult.Success -> result.data
        is ApiResult.Failure -> throw ApiCallFailedException(result.message)
    }
}
