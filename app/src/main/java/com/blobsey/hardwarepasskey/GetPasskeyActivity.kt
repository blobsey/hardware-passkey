package com.blobsey.hardwarepasskey

import android.app.Activity
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import android.widget.Toast
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import org.json.JSONObject

/**
 * Launched by [PasskeyCredentialProviderService] to handle a [GetPublicKeyCredentialOption]
 * invoked when the user selects a specific passkey to sign in with from the Android system's bottom
 * sheet. Retrieves the selected ECDSA private key from the Android Keystore and uses it to
 * cryptographically* sign the WebAuthn challenge upon successful biometric authentication.
 *
 * Returns a [GetCredentialResponse] via [PendingIntentHandler]
 */
class GetPasskeyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val keyAlias =
            intent.getStringExtra("keyAlias") ?: run {
                finishWithError("No key alias provided")
                return
            }

        val request =
            PendingIntentHandler.retrieveProviderGetCredentialRequest(intent) ?: run {
                finishWithError("No request in intent")
                return
            }

        // Verify the caller against the trusted allowlist. Returns a web origin for privileged
        // browser callers; null for unprivileged native-app callers (handled below when building
        // clientDataJSON / clientDataHash).
        val privilegedOrigin =
            try {
                WebAuthnCommon.verifyAndGetOrigin(this, request.callingAppInfo)
            } catch (_: IllegalStateException) {
                finishWithError(
                    "Untrusted caller attempting to claim origin: ${request.callingAppInfo.packageName}"
                )
                return
            } catch (e: IllegalArgumentException) {
                finishWithError("Failed to parse trusted apps list: ${e.message}")
                return
            }

        // Try to find a passkey with the given keyAlias in SharedPreferences
        val passkeyDataJson =
            getSharedPreferences(WebAuthnCommon.SHARED_PREFS_KEY_PASSKEYS, MODE_PRIVATE)
                .getString(keyAlias, null)
                ?: return finishWithError("Passkey not found")

        val passkeyData =
            try {
                PasskeyData.fromJsonString(passkeyDataJson)
            } catch (e: Exception) {
                return finishWithError("Failed to parse passkey data: ${e.message}")
            }

        val signature =
            try {
                val keyStore = KeyStore.getInstance("AndroidKeyStore")
                keyStore.load(null)
                val privateKey =
                    keyStore.getKey(keyAlias, null) as? PrivateKey ?: run {
                        finishWithError("Private key not found in Keystore (alias=$keyAlias)")
                        return
                    }
                Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey) }
            } catch (_: KeyPermanentlyInvalidatedException) {
                WebAuthnCommon.cleanupPasskey(this, keyAlias)
                finishWithError(
                    "Passkey is no longer valid; please re-register with ${passkeyData.rpId}"
                )
                return
            } catch (e: Exception) {
                finishWithError("Failed to load passkey: ${e.message}")
                return
            }

        val promptInfo =
            BiometricPrompt
                .Builder(this)
                .setTitle("Use your screen lock")
                .setSubtitle("Sign in to ${passkeyData.rpId}")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()

        promptInfo.authenticate(
            BiometricPrompt.CryptoObject(signature),
            CancellationSignal(),
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    finishWithError(errString.toString())
                }

                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    super.onAuthenticationSucceeded(result)

                    val authData =
                        try {
                            WebAuthnCommon.buildAuthData(
                                rpId = passkeyData.rpId,
                                flags =
                                WebAuthnCommon.AUTH_DATA_FLAG_UP or
                                    WebAuthnCommon.AUTH_DATA_FLAG_UV
                            )
                        } catch (e: Exception) {
                            finishWithError("Failed to build authData: ${e.message}")
                            return
                        }

                    val option =
                        request.credentialOptions
                            .filterIsInstance<GetPublicKeyCredentialOption>()
                            .firstOrNull() ?: run {
                            finishWithError("Missing GetPublicKeyCredentialOption")
                            return
                        }

                    // For privileged browser callers, the system already computed clientDataHash
                    // from the browser's real clientDataJSON so use it as-is and return a
                    // placeholder clientDataJSON. For non-privileged native-app callers, construct
                    // our own clientDataJSON (with android:apk-key-hash origin) and sign over its
                    // SHA-256; the RP verifies the signature against the returned clientDataJSON.
                    val (clientDataHash, clientDataJsonB64) =
                        if (privilegedOrigin != null) {
                            val systemHash =
                                option.clientDataHash ?: run {
                                    finishWithError(
                                        "Client data hash missing for privileged caller"
                                    )
                                    return
                                }
                            systemHash to WebAuthnCommon.DUMMY_CLIENT_DATA_JSON_B64
                        } else {
                            val challenge =
                                runCatching {
                                    JSONObject(option.requestJson).optString("challenge")
                                }
                                    .getOrNull()
                                    ?.takeIf { it.isNotEmpty() } ?: run {
                                    finishWithError("Challenge missing in GET requestJson")
                                    return
                                }
                            val ourJson =
                                WebAuthnCommon.buildClientDataJson(
                                    callingAppInfo = request.callingAppInfo,
                                    privilegedOrigin = null,
                                    type = "webauthn.get",
                                    challenge = challenge
                                )
                            val ourJsonBytes = ourJson.toByteArray(Charsets.UTF_8)
                            val ourHash = MessageDigest.getInstance("SHA-256").digest(ourJsonBytes)
                            ourHash to Base64.encodeToString(
                                ourJsonBytes,
                                WebAuthnCommon.WEBAUTHN_BASE64_FLAGS
                            )
                        }

                    val signatureBytes =
                        try {
                            val activeSignature =
                                result.cryptoObject?.signature ?: run {
                                    finishWithError("Signature object missing")
                                    return
                                }
                            val dataToSign = authData + clientDataHash
                            activeSignature.update(dataToSign)
                            activeSignature.sign()
                        } catch (e: Exception) {
                            finishWithError("Cryptographic operation failed: ${e.message}")
                            return
                        }

                    val responseJson =
                        try {
                            val credentialId = keyAlias.toByteArray(Charsets.UTF_8)
                            val credentialIdBase64 = Base64.encodeToString(
                                credentialId,
                                WebAuthnCommon.WEBAUTHN_BASE64_FLAGS
                            )

                            JSONObject()
                                .apply {
                                    put("id", credentialIdBase64)
                                    put("rawId", credentialIdBase64)
                                    put("type", "public-key")
                                    put("authenticatorAttachment", "platform")
                                    put("clientExtensionResults", JSONObject())
                                    put(
                                        "response",
                                        JSONObject().apply {
                                            put("clientDataJSON", clientDataJsonB64)
                                            put(
                                                "authenticatorData",
                                                Base64.encodeToString(
                                                    authData,
                                                    WebAuthnCommon.WEBAUTHN_BASE64_FLAGS
                                                )
                                            )
                                            put(
                                                "signature",
                                                Base64.encodeToString(
                                                    signatureBytes,
                                                    WebAuthnCommon.WEBAUTHN_BASE64_FLAGS
                                                )
                                            )
                                            put("userHandle", passkeyData.userId)
                                        }
                                    )
                                }.toString()
                        } catch (e: Exception) {
                            finishWithError("Failed to build JSON response: ${e.message}")
                            return
                        }

                    try {
                        val passkeyCredential = PublicKeyCredential(responseJson)
                        val resultIntent = Intent()
                        PendingIntentHandler.setGetCredentialResponse(
                            resultIntent,
                            GetCredentialResponse(passkeyCredential)
                        )
                        WebAuthnCommon.touchPasskeyLastUsed(this@GetPasskeyActivity, keyAlias)
                        setResult(RESULT_OK, resultIntent)
                        finish()
                    } catch (e: Exception) {
                        finishWithError("Failed to set credential response: ${e.message}")
                    }
                }
            }
        )
    }

    private fun finishWithError(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        val resultIntent = Intent()
        PendingIntentHandler.setGetCredentialException(
            resultIntent,
            GetCredentialUnknownException(msg)
        )
        setResult(RESULT_OK, resultIntent)
        finish()
    }
}
