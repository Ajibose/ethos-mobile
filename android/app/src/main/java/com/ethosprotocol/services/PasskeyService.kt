package com.ethosprotocol.services

// ADR-0003: CredentialManager for Android passkeys — see docs/adr/adr-0003-credentialmanager-android-passkeys.md

import android.app.Activity
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
    suspend fun register(activity: Activity, username: String): Result<Unit> = runCatching {
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
        is NoCredentialException ->
            "No passkey found for this account on this device. Create an account or use the device you registered with."
        is GetCredentialProviderConfigurationException ->
            "No passkey provider is set up on this device."
        is GetCredentialInterruptedException -> "Sign-in was interrupted — please try again."
        else -> "Couldn't sign in with a passkey. Please try again."
    }

    internal fun <T> requireSuccess(result: ApiResult<T>): T {
        return when (result) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> throw ApiCallFailedException(result.message, result.code)
            ApiResult.NetworkUnavailable -> throw ApiCallFailedException("No network connection")
        }
    }
}

// Top-level (rather than private to the class) and `internal` so PasskeyServiceTest can
// verify the WebAuthn JSON shape directly, without driving a real CredentialManager ceremony.
internal fun buildRegistrationRequestJson(challenge: AuthChallenge, username: String): String =
    JSONObject().apply {
        put("challenge", challenge.challenge)
        put("rp", JSONObject().put("id", RP_ID).put("name", "Ethos-Protocol"))
        put("user", JSONObject()
            .put("id", Base64.getUrlEncoder().withoutPadding().encodeToString(username.toByteArray()))
            .put("name", username).put("displayName", username))
        put("pubKeyCredParams", JSONArray().put(JSONObject().put("type", "public-key").put("alg", -7)))
        put("authenticatorSelection", JSONObject()
            .put("authenticatorAttachment", "platform")
            .put("requireResidentKey", true)
            .put("userVerification", "required"))
        // Without this, CredentialManager has no way to know the user already has a
        // passkey for this account, so it happily creates a second one with no warning.
        if (challenge.existingCredentialIds.isNotEmpty()) {
            put("excludeCredentials", JSONArray(challenge.existingCredentialIds.map {
                JSONObject().put("type", "public-key").put("id", it)
            }))
        }
    }.toString()

// The backend expects the WebAuthn COSE_Key (RFC 9052) extracted from the attestation
// object's authData, base64url-encoded — not the raw attestation object, which was being
// sent under `public_key` before (see docs/mobile-passkey-flow.md's description of
// extracting a separate public key rather than forwarding the attestation object as-is).
// `internal` (not private) so PasskeyServiceTest can verify it against a synthetic fixture.
internal fun extractCosePublicKey(attestationObjectB64: String): String {
    val attestationBytes = decodeBase64Url(attestationObjectB64)
    val attestationMap = CborReader(attestationBytes).readItem() as? Map<*, *>
        ?: error("Invalid attestation object: not a CBOR map")
    val authData = attestationMap["authData"] as? ByteArray
        ?: error("attestationObject missing authData")
    return Base64.getUrlEncoder().withoutPadding().encodeToString(cosePublicKeyBytes(authData))
}

// authData layout (WebAuthn §6.1): rpIdHash(32) | flags(1) | signCount(4) |
// [attestedCredentialData: aaguid(16) | credIdLen(2) | credId(credIdLen) | credentialPublicKey (COSE_Key, CBOR)]
private fun cosePublicKeyBytes(authData: ByteArray): ByteArray {
    require(authData.size > 37) { "authData too short to contain attested credential data" }
    val flags = authData[32].toInt()
    require((flags and 0x40) != 0) { "authData has no attested credential data (AT flag unset)" }
    var offset = 37 + 16 // rpIdHash + flags + signCount, then aaguid
    val credentialIdLength = ((authData[offset].toInt() and 0xFF) shl 8) or (authData[offset + 1].toInt() and 0xFF)
    offset += 2 + credentialIdLength
    // The COSE_Key may be followed by an extensions CBOR item (if the ED flag is set);
    // reading exactly one CBOR item from this offset yields just the public key.
    val coseKeyReader = CborReader(authData, offset)
    coseKeyReader.readItem()
    return authData.copyOfRange(offset, coseKeyReader.position)
}

private fun decodeBase64Url(value: String): ByteArray {
    val padded = value.padEnd((value.length + 3) / 4 * 4, '=')
    return Base64.getUrlDecoder().decode(padded)
}

// Minimal CBOR (RFC 8949) decoder covering just what's needed to read a WebAuthn
// attestationObject and a COSE_Key map: unsigned/negative integers, byte/text strings,
// arrays, and maps. Not a general-purpose CBOR implementation (no floats, no
// indefinite-length items — neither appears in this data).
private class CborReader(private val data: ByteArray, startPos: Int = 0) {
    var position = startPos
        private set

    fun readItem(): Any? {
        val initial = data[position].toInt() and 0xFF
        val majorType = initial shr 5
        val info = initial and 0x1F
        position++
        val length = readLength(info)
        return when (majorType) {
            0 -> length
            1 -> -1L - length
            2 -> readBytes(length.toInt())
            3 -> String(readBytes(length.toInt()), Charsets.UTF_8)
            4 -> (0 until length).map { readItem() }
            5 -> {
                val map = LinkedHashMap<Any?, Any?>()
                repeat(length.toInt()) { map[readItem()] = readItem() }
                map
            }
            6 -> readItem() // tag: decode and return the wrapped item, ignore the tag itself
            7 -> when (info) {
                20 -> false
                21 -> true
                22 -> null
                else -> length
            }
            else -> error("Unsupported CBOR major type $majorType")
        }
    }

    private fun readLength(info: Int): Long = when (info) {
        in 0..23 -> info.toLong()
        24 -> readUInt(1)
        25 -> readUInt(2)
        26 -> readUInt(4)
        27 -> readUInt(8)
        else -> error("Unsupported CBOR length encoding: $info")
    }

    private fun readUInt(numBytes: Int): Long {
        var result = 0L
        repeat(numBytes) {
            result = (result shl 8) or (data[position].toLong() and 0xFF)
            position++
        }
        return result
    }

    private fun readBytes(length: Int): ByteArray {
        val result = data.copyOfRange(position, position + length)
        position += length
        return result
    }
}
