package com.blobsey.hardwarepasskey

import android.app.Activity
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.widget.Toast
import androidx.core.content.edit
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import co.nstant.`in`.cbor.CborBuilder
import co.nstant.`in`.cbor.CborEncoder
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Launched by [PasskeyCredentialProviderService] to handle a [CreatePublicKeyCredentialRequest]
 * invoked when user selects this app to save the passkey in the Android system's bottom sheet.
 * Generates an ECDSA key pair in the Android Keystore, saves the passkey metadata to
 * [android.content.SharedPreferences]
 *
 * Returns a [CreatePublicKeyCredentialResponse] via [PendingIntentHandler]
 */
class CreatePasskeyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val request =
            PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent) ?: run {
                finishWithError("No request in intent")
                return
            }

        // Extract the WebAuthn JSON Request
        val callingRequest =
            request.callingRequest as? CreatePublicKeyCredentialRequest ?: run {
                finishWithError("Not a public key request")
                return
            }

        // Fetch origin and verify the caller against the trusted allowlist
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

        val requestJson =
            try {
                JSONObject(callingRequest.requestJson)
            } catch (e: Exception) {
                finishWithError("Invalid WebAuthn request JSON: ${e.message}")
                return
            }

        // Verify the RP accepts ES256
        val supportsEs256 =
            requestJson.optJSONArray("pubKeyCredParams")?.let { params ->
                (0 until params.length()).any { i ->
                    val p = params.getJSONObject(i)
                    p.optString("type") == "public-key" &&
                        p.optInt("alg") == WebAuthnCommon.COSE_ALG_ES256
                }
            } ?: false
        if (!supportsEs256) {
            finishWithError("This authenticator's only supports ES256")
            return
        }

        val passkeyData =
            try {
                val user = requestJson.getJSONObject("user")
                PasskeyData(
                    rpId = requestJson.getJSONObject("rp").getString("id"),
                    userId = user.getString("id"),
                    userName = user.getString("name"),
                    userDisplayName = user.getString("displayName"),
                    keyAlias = WebAuthnCommon.generateCredentialId(),
                    createdAt = System.currentTimeMillis(),
                    lastUsedAt = null
                )
            } catch (e: Exception) {
                finishWithError("Invalid WebAuthn request: ${e.message}")
                return
            }

        val prefs = getSharedPreferences(WebAuthnCommon.SHARED_PREFS_KEY_PASSKEYS, MODE_PRIVATE)

        // Honor excludeCredentials, scoped to this RP
        // If any listed credential already exists for this rpId, reject before we mutate anything
        val excludeCredentials = requestJson.optJSONArray("excludeCredentials")
        if (excludeCredentials != null) {
            for (i in 0 until excludeCredentials.length()) {
                val entry = excludeCredentials.optJSONObject(i) ?: continue
                val id = entry.optString("id")
                // Our credential IDs are UTF-8 strings. The incoming ID is base64url-encoded bytes.
                val decoded =
                    try {
                        String(
                            Base64.decode(id, WebAuthnCommon.WEBAUTHN_BASE64_FLAGS),
                            Charsets.UTF_8
                        )
                    } catch (_: Exception) {
                        continue
                    }
                if (!WebAuthnCommon.isCredentialId(decoded)) continue
                if (!prefs.contains(decoded)) continue

                val existing =
                    prefs.getString(decoded, null)?.let {
                        try {
                            PasskeyData.fromJsonString(it)
                        } catch (_: Exception) {
                            null
                        }
                    } ?: continue

                if (existing.rpId == passkeyData.rpId) {
                    finishWithError("Passkey matching excludeCredentials already exists for the RP")
                    return
                }
            }
        }

        val keyPair =
            try {
                val kpg =
                    KeyPairGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_EC,
                        "AndroidKeyStore"
                    )
                val parameterSpec =
                    KeyGenParameterSpec
                        .Builder(
                            passkeyData.keyAlias,
                            KeyProperties.PURPOSE_SIGN
                        ).run {
                            setDigests(KeyProperties.DIGEST_SHA256)
                            setUserAuthenticationRequired(true)
                            // Bind to lockscreen credential so biometric changes don't invalidate
                            setUserAuthenticationParameters(
                                0,
                                KeyProperties.AUTH_BIOMETRIC_STRONG or
                                    KeyProperties.AUTH_DEVICE_CREDENTIAL
                            )
                            build()
                        }
                kpg.initialize(parameterSpec)
                kpg.generateKeyPair()
            } catch (e: Exception) {
                WebAuthnCommon.cleanupPasskey(this, passkeyData.keyAlias)
                finishWithError("Failed to generate hardware keys: ${e.message}")
                return
            }

        // Convert Android EC Public Key to COSE format
        val coseKeyBytes =
            try {
                val ecPublicKey = keyPair.public as ECPublicKey
                val x = ecPublicKey.w.affineX.toBytesPadded(32)
                val y = ecPublicKey.w.affineY.toBytesPadded(32)

                // Do not reorder these puts! Keys must be CTAP2 Canonical CBOR encoding
                // Reference: https://www.w3.org/TR/webauthn-2/#sctn-encoded-credPubKey-examples
                val coseKey =
                    CborBuilder()
                        .addMap()
                        .put(
                            WebAuthnCommon.COSE_KEY_KTY.toLong(),
                            WebAuthnCommon.COSE_KTY_EC2.toLong()
                        )
                        .put(
                            WebAuthnCommon.COSE_KEY_ALG.toLong(),
                            WebAuthnCommon.COSE_ALG_ES256.toLong()
                        )
                        .put(
                            WebAuthnCommon.COSE_KEY_EC2_CRV.toLong(),
                            WebAuthnCommon.COSE_CRV_P256.toLong()
                        )
                        .put(WebAuthnCommon.COSE_KEY_EC2_X.toLong(), x)
                        .put(WebAuthnCommon.COSE_KEY_EC2_Y.toLong(), y)
                        .end()
                        .build()
                ByteArrayOutputStream()
                    .apply {
                        CborEncoder(this).encode(coseKey)
                    }.toByteArray()
            } catch (e: Exception) {
                WebAuthnCommon.cleanupPasskey(this, passkeyData.keyAlias)
                finishWithError("Failed to encode COSE key: ${e.message}")
                return
            }

        val credentialId = passkeyData.keyAlias.toByteArray(Charsets.UTF_8)
        val authData =
            try {
                WebAuthnCommon.buildAuthData(
                    rpId = passkeyData.rpId,
                    flags = WebAuthnCommon.AUTH_DATA_FLAG_UP or WebAuthnCommon.AUTH_DATA_FLAG_AT,
                    attestedCredentialData =
                    WebAuthnCommon.AttestedCredentialDataParams(
                        credentialId = credentialId,
                        coseKeyBytes = coseKeyBytes
                    )
                )
            } catch (e: Exception) {
                WebAuthnCommon.cleanupPasskey(this, passkeyData.keyAlias)
                finishWithError("Failed to build authData: ${e.message}")
                return
            }

        // Build Attestation Object (CBOR)
        val attestationObjectBytes =
            try {
                // Do not reorder these puts! WebAuthn expects CTAP2 Canonical CBOR encoding
                // Ref: https://www.w3.org/TR/webauthn-2/#sctn-attestation
                val attObj =
                    CborBuilder()
                        .addMap()
                        .put("fmt", "none")
                        .putMap("attStmt")
                        .end()
                        .put("authData", authData)
                        .end()
                        .build()

                ByteArrayOutputStream()
                    .apply {
                        CborEncoder(this).encode(attObj)
                    }.toByteArray()
            } catch (e: Exception) {
                WebAuthnCommon.cleanupPasskey(this, passkeyData.keyAlias)
                finishWithError("Failed to encode attestation object: ${e.message}")
                return
            }

        val clientDataJson =
            WebAuthnCommon.buildClientDataJson(
                callingAppInfo = request.callingAppInfo,
                privilegedOrigin = privilegedOrigin,
                type = "webauthn.create",
                challenge = requestJson.optString("challenge")
            )
        val clientDataJsonB64 =
            Base64.encodeToString(
                clientDataJson.toByteArray(Charsets.UTF_8),
                WebAuthnCommon.WEBAUTHN_BASE64_FLAGS
            )

        // Construct Final WebAuthn JSON
        val responseJson =
            try {
                JSONObject()
                    .apply {
                        val b64CredId = Base64.encodeToString(
                            credentialId,
                            WebAuthnCommon.WEBAUTHN_BASE64_FLAGS
                        )
                        put("id", b64CredId)
                        put("rawId", b64CredId)
                        put("type", "public-key")
                        put("authenticatorAttachment", "platform")
                        put("clientExtensionResults", JSONObject()) // Required by Chromium
                        put(
                            "response",
                            JSONObject().apply {
                                put("clientDataJSON", clientDataJsonB64)
                                put(
                                    "attestationObject",
                                    Base64.encodeToString(
                                        attestationObjectBytes,
                                        WebAuthnCommon.WEBAUTHN_BASE64_FLAGS
                                    )
                                )
                                put("transports", JSONArray(listOf("internal")))
                                put("publicKeyAlgorithm", WebAuthnCommon.COSE_ALG_ES256)
                                put(
                                    "publicKey",
                                    Base64.encodeToString(
                                        keyPair.public.encoded,
                                        WebAuthnCommon.WEBAUTHN_BASE64_FLAGS
                                    )
                                )
                                put(
                                    "authenticatorData",
                                    Base64.encodeToString(
                                        authData,
                                        WebAuthnCommon.WEBAUTHN_BASE64_FLAGS
                                    )
                                )
                            }
                        )
                    }.toString()
            } catch (e: Exception) {
                WebAuthnCommon.cleanupPasskey(this, passkeyData.keyAlias)
                finishWithError("Failed to build response JSON: ${e.message}")
                return
            }

        // Replace any existing passkey for (rpId, userId). Per CTAP2 §6.1.2, a new
        // discoverable credential for the same (rp.id, user.id) supersedes the old one
        WebAuthnCommon.loadPasskeys(this)
            .filter { it.rpId == passkeyData.rpId && it.userId == passkeyData.userId }
            .forEach { WebAuthnCommon.cleanupPasskey(this, it.keyAlias) }

        // Passkeys are tracked in SharedPreferences by their keyAlias
        prefs.edit { putString(passkeyData.keyAlias, passkeyData.toJsonString()) }

        val createResponse = CreatePublicKeyCredentialResponse(responseJson)
        val resultIntent = android.content.Intent()
        PendingIntentHandler.setCreateCredentialResponse(resultIntent, createResponse)
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun finishWithError(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        val resultIntent = android.content.Intent()
        PendingIntentHandler.setCreateCredentialException(
            resultIntent,
            CreateCredentialUnknownException(msg)
        )
        setResult(RESULT_OK, resultIntent)
        finish()
    }
}
