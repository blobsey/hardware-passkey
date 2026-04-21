package com.blobsey.hardwarepasskey

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import androidx.credentials.provider.CallingAppInfo
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import org.json.JSONObject

object WebAuthnCommon {
    /** Key into SharedPreferences for passkeys */
    const val SHARED_PREFS_KEY_PASSKEYS = "passkeys"

    /**
     * The WebAuthn specification strictly requires Base64Url encoding without padding or line wraps
     * Ref: [WebAuthn - Dependencies](https://www.w3.org/TR/webauthn-2/#sctn-dependencies)
     */
    const val WEBAUTHN_BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    // COSE Algorithm Identifiers
    // Ref: https://www.iana.org/assignments/cose/cose.xhtml#algorithms
    const val COSE_ALG_ES256 = -7

    // COSE Key Common Parameters (RFC 8152 §7.1)
    const val COSE_KEY_KTY = 1
    const val COSE_KEY_ALG = 3

    // COSE EC2 Key Type Parameters (RFC 8152 §13.1.1)
    const val COSE_KEY_EC2_CRV = -1
    const val COSE_KEY_EC2_X = -2
    const val COSE_KEY_EC2_Y = -3

    // COSE Key Type values
    const val COSE_KTY_EC2 = 2

    // COSE Elliptic Curves
    const val COSE_CRV_P256 = 1

    // WebAuthn Authenticator Data flags
    // Ref: https://www.w3.org/TR/webauthn-2/#authdata-flags
    const val AUTH_DATA_FLAG_UP = 0x01 // User Present
    const val AUTH_DATA_FLAG_UV = 0x04 // User Verified
    const val AUTH_DATA_FLAG_AT = 0x40

    // Attested Credential Data included
    @Suppress("unused") // Spec-complete; remove when extension support is added
    const val AUTH_DATA_FLAG_ED = 0x80 // Extension Data included

    /**
     * Helper class to allow entering of WebAuthn Attestation Credential Data to [buildAuthData]
     * Ref: https://www.w3.org/TR/webauthn-2/#sctn-attested-credential-data
     */
    class AttestedCredentialDataParams(
        val credentialId: ByteArray,
        val coseKeyBytes: ByteArray,
        val aaguid: ByteArray = ByteArray(16)
    )

    /**
     * Builds WebAuthn Authenticator Data
     * Ref: https://www.w3.org/TR/webauthn-2/#sctn-authenticator-data
     *
     * @param attestedCredentialData Optional attested credential data blob to append.
     *   Must be provided when [flags] includes [AUTH_DATA_FLAG_AT].
     */
    fun buildAuthData(
        rpId: String,
        flags: Int,
        signCount: Int = 0,
        attestedCredentialData: AttestedCredentialDataParams? = null
    ): ByteArray {
        // Validations
        val hasAtFlag = (flags and AUTH_DATA_FLAG_AT) != 0
        require(hasAtFlag == (attestedCredentialData != null)) {
            "AT flag and attestedCredentialData must be set together " +
                "(flags=0x${flags.toString(
                    16
                )}, attestedCredentialData=${attestedCredentialData != null})"
        }
        attestedCredentialData?.let {
            require(it.aaguid.size == 16) {
                "aaguid must be exactly 16 bytes, got ${it.aaguid.size}"
            }
            require(it.credentialId.size in 1..1023) {
                "credentialId must be 1-1023 bytes, got ${it.credentialId.size}"
            }
        }

        return ByteArrayOutputStream()
            .also { baos ->
                DataOutputStream(baos).use { stream ->
                    stream.write(
                        MessageDigest
                            .getInstance("SHA-256")
                            .digest(rpId.toByteArray(Charsets.UTF_8))
                    )
                    stream.writeByte(flags)
                    stream.writeInt(signCount)
                    attestedCredentialData?.let { acd ->
                        stream.write(acd.aaguid)
                        stream.writeShort(acd.credentialId.size)
                        stream.write(acd.credentialId)
                        stream.write(acd.coseKeyBytes)
                    }
                }
            }.toByteArray()
    }

    /**
     * Android CredentialProvider docs tell us to supply a dummy "{}" for
     * clientDataJSON, since the actual value is already hashed into clientDataHash.
     * Ref: https://developer.android.com/identity/sign-in/credential-provider#obtain-allowlist
     */
    val DUMMY_CLIENT_DATA_JSON_B64: String =
        Base64.encodeToString("{}".toByteArray(), WEBAUTHN_BASE64_FLAGS)

    /**
     * Generates a unique, namespaced Credential ID.
     *
     * The WebAuthn specification considers the Credential ID to be a completely opaque byte array
     * that identifies the passkey, so it's safe to reuse it for our own purposes (i.e. as a key into
     * SharedPreferences)
     */
    fun generateCredentialId(): String = "passkey-${java.util.UUID.randomUUID()}"

    /**
     * Checks if a given SharedPreferences key is a Passkey Credential ID.
     */
    fun isCredentialId(key: String): Boolean = key.startsWith("passkey-")

    /**
     * Verifies the caller against the trusted allowlist ([R.raw.apps]).
     *
     * @return The origin if trusted and requested by a privileged app, or null for native-apps. The
     * null has ramifications for clientDataJson construction, see [buildClientDataJson]
     * @throws IllegalStateException If the caller is untrusted.
     * @throws IllegalArgumentException If the JSON allowlist is malformed.
     */
    fun verifyAndGetOrigin(context: Context, callingAppInfo: CallingAppInfo): String? {
        val appsJsonStream = context.resources.openRawResource(R.raw.apps)
        val appsJsonString = java.io.InputStreamReader(appsJsonStream).use { it.readText() }
        return callingAppInfo.getOrigin(appsJsonString)
    }

    /**
     * Builds a WebAuthn `clientDataJSON` payload for the given ceremony. Privileged browser
     * callers carry a real web origin in [privilegedOrigin]; native-app callers pass null and we
     * synthesise `android:apk-key-hash:<base64url(SHA-256(signing cert))>` plus the
     * `androidPackageName` field. Callers are responsible for base64url-encoding and, in
     * assertion flows, hashing the returned string themselves.
     *
     * Ref: https://developer.android.com/identity/sign-in/credential-provider
     */
    fun buildClientDataJson(
        callingAppInfo: CallingAppInfo,
        privilegedOrigin: String?,
        type: String,
        challenge: String
    ): String = JSONObject().apply {
        put("type", type)
        put("challenge", challenge)
        if (privilegedOrigin != null) {
            put("origin", privilegedOrigin)
        } else {
            val origin = MessageDigest.getInstance("SHA-256")
                .digest(callingAppInfo.signingInfo.apkContentsSigners[0].toByteArray())
                .let { "android:apk-key-hash:${Base64.encodeToString(it, WEBAUTHN_BASE64_FLAGS)}" }
            put("origin", origin)
            put("androidPackageName", callingAppInfo.packageName)
        }
    }.toString()

    /**
     * Returns every stored passkey. Entries that fail to parse are silently skipped.
     * Callers apply their own filtering/sorting as needed.
     */
    fun loadPasskeys(context: Context): List<PasskeyData> =
        context.getSharedPreferences(SHARED_PREFS_KEY_PASSKEYS, MODE_PRIVATE).all.entries
            .filter { isCredentialId(it.key) }
            .mapNotNull {
                runCatching { PasskeyData.fromJsonString(it.value as String) }.getOrNull()
            }

    /**
     * Updates the [PasskeyData.lastUsedAt] timestamp for the given [keyAlias].
     */
    fun touchPasskeyLastUsed(context: Context, keyAlias: String) {
        val prefs = context.getSharedPreferences(SHARED_PREFS_KEY_PASSKEYS, MODE_PRIVATE)
        val json = prefs.getString(keyAlias, null) ?: return
        val data =
            try {
                PasskeyData.fromJsonString(json)
            } catch (_: Exception) {
                return
            }
        val updated = data.copy(lastUsedAt = System.currentTimeMillis())
        prefs.edit { putString(keyAlias, updated.toJsonString()) }
    }

    /**
     * Deletes a passkey if it exists, both from the AndroidKeyStore and SharedPreferences
     * Doesn't throw errors if the passkey doesn't exist, just logs a warning
     */
    fun cleanupPasskey(context: Context, keyAlias: String) {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
            }
        } catch (e: Exception) {
            Log.w("WebAuthnCommon", "Failed to delete keystore entry: $keyAlias", e)
        }
        context.getSharedPreferences(SHARED_PREFS_KEY_PASSKEYS, MODE_PRIVATE).edit {
            remove(keyAlias)
        }
    }
}

fun java.math.BigInteger.toBytesPadded(length: Int): ByteArray {
    val bytes = this.toByteArray()
    return when {
        bytes.size == length -> bytes
        bytes.size > length -> bytes.copyOfRange(bytes.size - length, bytes.size)
        else -> ByteArray(length - bytes.size) + bytes // Pad with leading zeroes
    }
}
