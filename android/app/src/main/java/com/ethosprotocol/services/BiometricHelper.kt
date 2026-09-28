package com.ethosprotocol.services

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity

class BiometricHelper(private val activity: FragmentActivity) {

    /**
     * Outcome of checking whether biometric authentication can be used.
     */
    sealed class EnrollmentStatus {
        /** Biometric (or device credential) auth is ready to use. */
        object Enrolled : EnrollmentStatus()

        /** No biometrics are enrolled on the device; guidance should be shown. */
        object NotEnrolled : EnrollmentStatus()

        /** The device has no usable biometric hardware. */
        object Unsupported : EnrollmentStatus()

        /** Enrollment state could not be determined. */
        object Unknown : EnrollmentStatus()
    }

    fun isAvailable(): Boolean {
        return enrollmentStatus() == EnrollmentStatus.Enrolled
    }

    /**
     * Detects whether biometric enrollment is missing so callers can show
     * setup guidance instead of failing passkey authentication silently.
     */
    fun enrollmentStatus(): EnrollmentStatus {
        val mgr = BiometricManager.from(activity)
        return when (mgr.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)) {
            BiometricManager.BIOMETRIC_SUCCESS -> EnrollmentStatus.Enrolled
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> EnrollmentStatus.NotEnrolled
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> EnrollmentStatus.Unsupported
            else -> EnrollmentStatus.Unknown
        }
    }

    /**
     * Opens the device biometric enrollment settings so the user can set up
     * biometrics, then retries passkey authentication once they return.
     */
    fun openEnrollmentSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
                Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                BIOMETRIC_STRONG or DEVICE_CREDENTIAL,
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            activity.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * Retries passkey authentication after the user completes enrollment.
     * If enrollment is still missing, [onEnrollmentRequired] is invoked so the
     * guidance screen can be shown again.
     */
    fun authenticateWithEnrollmentRetry(
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        onEnrollmentRequired: () -> Unit,
    ) {
        when (enrollmentStatus()) {
            EnrollmentStatus.Enrolled -> authenticate(title, subtitle, onSuccess, onError)
            EnrollmentStatus.NotEnrolled -> onEnrollmentRequired()
            EnrollmentStatus.Unsupported -> onError("This device does not support biometric authentication.")
            EnrollmentStatus.Unknown -> onError("Biometric authentication is currently unavailable.")
        }
    }

    fun authenticate(
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(errString.toString())
            }

            override fun onAuthenticationFailed() {
                onError("Biometric not recognised — please try again.")
            }
        }

        val prompt = BiometricPrompt(activity, callback)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()

        prompt.authenticate(promptInfo)
    }
}
