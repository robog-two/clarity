package net.robog.clarity.app.vault

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.lang.ref.WeakReference
import java.security.Signature
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** What the person sees on the system prompt. */
data class AuthPrompt(val title: String, val subtitle: String)

sealed interface AuthResult {
    /** Authenticated. When a [Signature] was passed in, this is the same one, now authorized to sign. */
    data class Authenticated(val signature: Signature? = null) : AuthResult
    data object Cancelled : AuthResult
    data class Unavailable(val message: String) : AuthResult
    data class Failed(val message: String) : AuthResult
}

/** Asks the person to prove it is them (fingerprint, face or device credential). */
interface PhoneAuth {
    /** With a [signature], the prompt is bound to it: it can sign exactly once after success. */
    suspend fun authenticate(prompt: AuthPrompt, signature: Signature? = null): AuthResult
}

/** The activity currently in front; the prompt needs one. Set by `MainActivity` while it is started. */
class ForegroundActivity {
    @Volatile private var ref: WeakReference<FragmentActivity>? = null
    fun set(activity: FragmentActivity?) { ref = activity?.let(::WeakReference) }
    fun get(): FragmentActivity? = ref?.get()
}

class BiometricPhoneAuth(private val foreground: ForegroundActivity) : PhoneAuth {
    override suspend fun authenticate(prompt: AuthPrompt, signature: Signature?): AuthResult {
        val activity = foreground.get() ?: return AuthResult.Unavailable("Open Clarity to approve this.")
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(activity).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            return AuthResult.Unavailable("Set a screen lock or a fingerprint on this phone to approve things.")
        }
        return suspendCancellableCoroutine { cont ->
            val executor = ContextCompat.getMainExecutor(activity)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(AuthResult.Authenticated(result.cryptoObject?.signature ?: signature))
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_CANCELED
                    if (cont.isActive) cont.resume(if (cancelled) AuthResult.Cancelled else AuthResult.Failed(errString.toString()))
                }
            }
            val biometricPrompt = BiometricPrompt(activity, executor, callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(prompt.title)
                .setSubtitle(prompt.subtitle)
                .setAllowedAuthenticators(authenticators)
                .setConfirmationRequired(false)
                .build()
            if (signature != null) biometricPrompt.authenticate(info, BiometricPrompt.CryptoObject(signature))
            else biometricPrompt.authenticate(info)
            cont.invokeOnCancellation { biometricPrompt.cancelAuthentication() }
        }
    }
}
