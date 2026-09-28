package com.ethosprotocol.models

import androidx.annotation.Keep
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.NumberFormat
import java.util.Locale

@Keep
@Serializable
data class Vault(
    val id: String,
    val owner: String,
    val beneficiary: String,
    val balance: Long,
    @SerialName("check_in_interval") val checkInInterval: Long,
    @SerialName("last_check_in") val lastCheckIn: String,
    @SerialName("ttl_remaining") val ttlRemaining: Long? = null,
    val status: VaultStatus,
    // Which Stellar asset `balance` is denominated in (#222). Every vault today
    // holds native XLM; this — and assetIssuer — exist so a future non-XLM vault
    // doesn't need a breaking schema change. Absent on a server response (every
    // response today) defaults to "XLM", mirroring iOS's Vault.assetCode.
    @SerialName("asset_code") val assetCode: String = "XLM",
    // The issuing account for assetCode, or null for native XLM (mirrors
    // AcceptedAsset's convention server-side). See api-contract.md §Vault (#222).
    @SerialName("asset_issuer") val assetIssuer: String? = null
) {
    val isExpiringSoon: Boolean get() = (ttlRemaining ?: Long.MAX_VALUE) < 86_400L

    // Assumes the 7-decimal stroop scale that applies to every Stellar classic
    // asset regardless of code — only the unit label varies (#222).
    // Uses device locale for decimal separator and digit grouping.
    val formattedBalance: String
        get() {
            val amountInAsset = balance / 10_000_000.0
            val formatter = NumberFormat.getInstance(Locale.getDefault())
            formatter.minimumFractionDigits = 7
            formatter.maximumFractionDigits = 7
            formatter.isGroupingUsed = true
            return "${formatter.format(amountInAsset)} $assetCode"
        }
}

// A single real-time event delivered over the `wss://.../ws?vault_id={id}` socket
// (see shared/api-contract.md). `vault` carries the full updated Vault so consumers
// can update state in place without an extra round trip.
/**
 * Guards an irreversible action (delete/archive a vault, etc.) behind a typed
 * confirmation rather than a plain Yes/No tap — codified now as a guardrail
 * before any delete/archive endpoint is wired up on either client (#220),
 * given the financial and beneficiary implications of getting this wrong.
 * [requiredText] is typically the vault's own name/ID, so confirming requires
 * the user to actually read and type it back exactly. Mirrors iOS's
 * `DestructiveConfirmation` (Sources/Models/Models.swift).
 */
data class DestructiveConfirmation(
    val requiredText: String,
    val enteredText: String = ""
) {
    val isConfirmed: Boolean get() = requiredText.isNotEmpty() && enteredText == requiredText

    /**
     * Runs [action] only if [isConfirmed] — the single choke point the
     * destructive-confirmation dialog uses, so "the button is disabled" and
     * "the underlying action never fires" can't drift apart from each other.
     */
    fun confirmIfMatched(action: () -> Unit) {
        if (isConfirmed) action()
    }
}

@Keep
@Serializable
data class VaultEvent(
    val type: String,
    val vault: Vault? = null
)

@Keep
@Serializable
enum class VaultStatus { active, expired, released, paused }

@Keep
@Serializable
data class AuthChallenge(
    val challenge: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("existing_credential_ids") val existingCredentialIds: List<String> = emptyList()
)

@Keep
@Serializable
data class AuthToken(
    val token: String,
    @SerialName("expires_at") val expiresAt: String
)

@Keep
@Serializable
data class CreateVaultRequest(
    val beneficiary: String,
    @SerialName("check_in_interval") val checkInInterval: Long
)

@Keep
@Serializable
data class BeneficiaryUpdateRequest(val beneficiary: String)

@Keep
@Serializable
data class PushRegistration(
    val token: String,
    val platform: String = "android"
)

@Keep
@Serializable
data class PasskeyVerifyRequest(
    @SerialName("credential_id") val credentialId: String,
    @SerialName("client_data_json") val clientDataJson: String,
    val signature: String
)

@Keep
@Serializable
data class PasskeyRegisterRequest(
    @SerialName("credential_id") val credentialId: String,
    // `public_key` is the WebAuthn COSE_Key (RFC 9052) extracted from the attestation
    // object's authData, base64url-encoded — not the raw CBOR attestation object.
    // See shared/api-contract.md's PasskeyRegisterRequest (#1) section.
    @SerialName("public_key") val publicKey: String,
    @SerialName("client_data_json") val clientDataJson: String
)

// MARK: - Account Recovery ("lost your device?")
//
// Shared contract with iOS's #5: initiate() sends a recovery code to the account's
// verified email, complete() links a newly-created passkey to that existing account once
// the recovery token proves the requester received that code.

@Keep
@Serializable
data class RecoveryInitiateRequest(val username: String)

@Keep
@Serializable
data class RecoveryInitiateResponse(
    @SerialName("recovery_token") val recoveryToken: String,
    @SerialName("expires_at") val expiresAt: String
)

@Keep
@Serializable
data class RecoveryCompleteRequest(
    @SerialName("recovery_token") val recoveryToken: String,
    @SerialName("credential_id") val credentialId: String,
    @SerialName("public_key") val publicKey: String,
    @SerialName("client_data_json") val clientDataJson: String
)

// MARK: - 2FA Models

@Keep
@Serializable
enum class TwoFactorMethod { totp, sms, email }

/**
 * #227: `availableMethods` lists the 2FA methods the server accepts for this account/vault.
 * Clients must filter the method-selection UI to only what is listed here.
 * Decoded defensively: absent field (older server) defaults to all three methods.
 */
@Keep
@Serializable
data class TwoFactorStatus(
    @SerialName("vault_id") val vaultId: String,
    val enabled: Boolean,
    val method: TwoFactorMethod? = null,
    val verified: Boolean = false,
    val phone: String? = null,
    val email: String? = null,
    /** #227: Methods available for this account. Defaults to all when the server omits the field. */
    @SerialName("available_methods") val availableMethods: List<TwoFactorMethod> = TwoFactorMethod.values().toList()
)

@Keep
@Serializable
data class Enable2FARequest(
    val method: TwoFactorMethod,
    val phone: String? = null,
    val email: String? = null
)

@Keep
@Serializable
data class Enable2FAResponse(
    @SerialName("vault_id") val vaultId: String,
    val method: TwoFactorMethod,
    val secret: String? = null,
    @SerialName("provisioning_uri") val provisioningUri: String? = null
)

/** #226: `trustDevice` opt-in — when true the server issues a device trust token valid 30 days. */
@Keep
@Serializable
data class Verify2FARequest(
    val otp: String,
    @SerialName("trust_device") val trustDevice: Boolean = false
)

/** #226: Response after verify; carries optional device trust token when opt-in was true. */
@Keep
@Serializable
data class Verify2FAResponse(
    @SerialName("device_trust_token") val deviceTrustToken: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null
)

// MARK: - #226 Trusted-Device Models

@Keep
@Serializable
data class TrustDeviceRequest(@SerialName("trust_device") val trustDevice: Boolean = true)

@Keep
@Serializable
data class TrustDeviceResponse(
    @SerialName("device_trust_token") val deviceTrustToken: String,
    @SerialName("expires_at") val expiresAt: String
)

// MARK: - #224 Backup Codes Models

@Keep
@Serializable
data class BackupCodesResponse(
    val codes: List<String>,
    @SerialName("generated_at") val generatedAt: String
)

@Keep
@Serializable
data class BackupCodesStatus(
    val generated: Boolean,
    @SerialName("remaining_count") val remainingCount: Int
)

// MARK: - #225 Switch 2FA Method Models

@Keep
@Serializable
data class Switch2FARequest(
    @SerialName("new_method") val newMethod: TwoFactorMethod,
    val phone: String? = null,
    val email: String? = null
)

// #109: Beneficiary acceptance request body.
// The token is parsed from the accept deep-link URL query parameter and is
// required by the server to authorise acceptance. See api-contract.md §POST /vaults/{id}/accept.
@Keep
@Serializable
data class BeneficiaryAcceptRequest(
    @SerialName("vault_id") val vaultId: String,
    val token: String
)

// #112: Paginated vault list response. See api-contract.md §Pagination.
@Keep
@Serializable
data class VaultPage(
    val vaults: List<Vault>,
    /** Opaque cursor for the next page, or null when this is the last page. */
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean
)
