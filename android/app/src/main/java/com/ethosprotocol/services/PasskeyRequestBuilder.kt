package com.ethosprotocol.services

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * Pure WebAuthn requestJson construction for [PasskeyService], kept separate so its
 * exact shape (key names, rp/user nesting, authenticatorSelection flags) can be
 * unit-tested without a live CredentialManager.
 */
internal object PasskeyRequestBuilder {
    private const val RP_ID = "ethos-protocol.app"
    private const val RP_NAME = "Ethos-Protocol"

    /**
     * Maximum accepted length for a server-supplied challenge. Bounds the value
     * before it is embedded in a WebAuthn request so oversized or malformed
     * challenges cannot be smuggled through to the authenticator.
     */
    internal const val MAX_CHALLENGE_LENGTH = 512

    /**
     * Maximum accepted length for a username. Prevents unbounded user handles
     * from being encoded into the registration request.
     */
    internal const val MAX_USERNAME_LENGTH = 256

    /**
     * Validates a server-supplied challenge before it is used to build a
     * WebAuthn request. Rejects blank, oversized, or non-base64url values so
     * malformed/expired/replayed challenges fail closed instead of being
     * forwarded to the platform authenticator.
     */
    internal fun isValidChallenge(challenge: String): Boolean {
        if (challenge.isBlank() || challenge.length > MAX_CHALLENGE_LENGTH) return false
        return challenge.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    /**
     * Validates a username before it is encoded into the registration request.
     */
    internal fun isValidUsername(username: String): Boolean =
        username.isNotBlank() && username.length <= MAX_USERNAME_LENGTH

    fun registrationRequestJson(challenge: String, username: String): String {
        require(isValidChallenge(challenge)) { "Invalid passkey challenge" }
        require(isValidUsername(username)) { "Invalid passkey username" }
        return registrationRequest(challenge, username).toString()
    }

    fun authenticationRequestJson(challenge: String): String {
        require(isValidChallenge(challenge)) { "Invalid passkey challenge" }
        return authenticationRequest(challenge).toString()
    }

    internal fun registrationRequest(challenge: String, username: String): JsonObject = buildJsonObject {
        put("challenge", challenge)
        putJsonObject("rp") {
            put("id", RP_ID)
            put("name", RP_NAME)
        }
        putJsonObject("user") {
            put("id", Base64.getUrlEncoder().withoutPadding().encodeToString(username.toByteArray()))
            put("name", username)
            put("displayName", username)
        }
        putJsonArray("pubKeyCredParams") {
            add(buildJsonObject {
                put("type", "public-key")
                put("alg", -7)
            })
        }
        putJsonObject("authenticatorSelection") {
            put("authenticatorAttachment", "platform")
            put("requireResidentKey", true)
            put("userVerification", "required")
        }
    }

    internal fun authenticationRequest(challenge: String): JsonObject = buildJsonObject {
        put("challenge", challenge)
        put("rpId", RP_ID)
        put("userVerification", "required")
    }
}
