package com.blobsey.hardwarepasskey

import org.json.JSONObject

/**
 * Represents the metadata of a Passkey stored in SharedPreferences.
 *
 * @property rpId The Relying Party ID (usually the domain name, e.g., "github.com"). Used to match
 *   the credential to the website or app requesting it during the sign-in flow.
 * @property userId The Relying Party's internal user ID, Base64Url encoded. Stored during creation
 *   and required by the WebAuthn spec to be returned during authentication as the `userHandle`.
 * @property userName The user's account name/handle (e.g., "alice@example.com"). Used to label the
 *   account in the Android Credential selector bottom-sheet.
 * @property userDisplayName The user's friendly name (e.g., "Alice Smith"). Also used for display
 *   in the Android Credential selector UI.
 * @property keyAlias The alias of the ECDSA KeyPair generated in the Android Keystore. Used during
 *   sign-in to retrieve the private key and cryptographically sign the WebAuthn challenge.
 * @property createdAt Epoch millis when this passkey was created.
 * @property lastUsedAt Epoch millis when this passkey was last used, or null if never used.
 */
data class PasskeyData(
    val rpId: String,
    val userId: String,
    val userName: String,
    val userDisplayName: String,
    val keyAlias: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long? = null
) {
    init {
        require(rpId.isNotEmpty()) { "rpId cannot be empty" }
        require(userId.isNotEmpty()) { "userId cannot be empty" }
        require(userName.isNotEmpty()) { "userName cannot be empty" }
        require(userDisplayName.isNotEmpty()) { "userDisplayName cannot be empty" }
        require(keyAlias.isNotEmpty()) { "keyAlias cannot be empty" }
    }

    fun toJsonString(): String = JSONObject()
        .apply {
            put("rpId", rpId)
            put("userId", userId)
            put("userName", userName)
            put("userDisplayName", userDisplayName)
            put("keyAlias", keyAlias)
            put("createdAt", createdAt)
            put("lastUsedAt", lastUsedAt ?: JSONObject.NULL)
        }.toString()

    companion object {
        fun fromJsonString(json: String): PasskeyData {
            val jsonObject = JSONObject(json)
            return PasskeyData(
                rpId = jsonObject.getString("rpId"),
                userId = jsonObject.getString("userId"),
                userName = jsonObject.getString("userName"),
                userDisplayName = jsonObject.getString("userDisplayName"),
                keyAlias = jsonObject.getString("keyAlias"),
                createdAt = jsonObject.getLong("createdAt"),
                lastUsedAt = if (jsonObject.isNull(
                        "lastUsedAt"
                    )
                ) {
                    null
                } else {
                    jsonObject.getLong("lastUsedAt")
                }
            )
        }
    }
}
